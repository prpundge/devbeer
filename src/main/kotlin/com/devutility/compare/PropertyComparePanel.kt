package com.devutility.compare

import com.devutility.DevUtilityBundle
import com.devutility.props.DiffOptions
import com.devutility.props.DiffResult
import com.devutility.props.DiffRow
import com.devutility.props.DiffStatus
import com.devutility.props.PropertyDiffEngine
import com.devutility.props.PropertyFileReader
import com.devutility.props.PropertyFileScanner
import com.devutility.props.PropertySnapshot
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.ide.scratch.ScratchRootType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.JBColor
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.datatransfer.StringSelection
import java.awt.event.ItemEvent
import java.awt.event.MouseEvent
import javax.swing.BoxLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JList
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JTable
import javax.swing.ListSelectionModel
import javax.swing.SwingConstants
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.TableRowSorter

private val COLOR_DIFFERENT = JBColor(0xFFECEC, 0x5A3A3A)
private val COLOR_ONLY_LEFT = JBColor(0xFFF6DF, 0x54492A)
private val COLOR_ONLY_RIGHT = JBColor(0xE5F0FF, 0x2B3C55)
private const val ABSENT = "—"

/** The interactive key-by-key comparison view. */
class PropertyComparePanel(private val project: Project) : SimpleToolWindowPanel(true, true), Disposable {

    private data class FileItem(val file: VirtualFile?, val label: String, val tooltip: String)

    private val leftModel = DefaultComboBoxModel<FileItem>()
    private val rightModel = DefaultComboBoxModel<FileItem>()
    private val leftCombo = ComboBox(leftModel)
    private val rightCombo = ComboBox(rightModel)

    private val onlyDifferences = JBCheckBox(DevUtilityBundle.message("compare.only.differences"), false)
    private val ignoreCase = JBCheckBox(DevUtilityBundle.message("compare.ignore.case"), false)
    private val trimValues = JBCheckBox(DevUtilityBundle.message("compare.trim.values"), true)
    private val search = SearchTextField()
    private val summary = JBLabel(DevUtilityBundle.message("compare.empty"))

    private val tableModel = DiffTableModel()
    private val table = JBTable(tableModel)

    private var leftSnapshot: PropertySnapshot? = null
    private var rightSnapshot: PropertySnapshot? = null
    private var result: DiffResult? = null

    /** Guards the combo listeners while we repopulate them programmatically. */
    private var suppressReload = false

    init {
        toolbar = buildToolbar()
        setContent(buildContent())
        reloadFileList(null, null)
    }

    // ------------------------------------------------------------------ public API

    /** Loads the given files, refreshing the picker first so external files show up in it. */
    fun load(left: VirtualFile?, right: VirtualFile?) {
        reloadFileList(left, right)
    }

    // ------------------------------------------------------------------ UI

    private fun buildToolbar(): JPanel {
        val filesRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), JBUI.scale(3)))
        filesRow.add(JBLabel(DevUtilityBundle.message("compare.left") + ":"))
        filesRow.add(configureCombo(leftCombo))
        filesRow.add(browseButton { setSelection(leftCombo, leftModel, it) })

        val swap = JButton("⇄")
        swap.toolTipText = DevUtilityBundle.message("compare.action.swap")
        swap.addActionListener { swapSides() }
        filesRow.add(swap)

        filesRow.add(JBLabel(DevUtilityBundle.message("compare.right") + ":"))
        filesRow.add(configureCombo(rightCombo))
        filesRow.add(browseButton { setSelection(rightCombo, rightModel, it) })

        val reload = JButton("↻")
        reload.toolTipText = DevUtilityBundle.message("compare.action.reload")
        reload.addActionListener { reloadFileList(selectedFile(leftCombo), selectedFile(rightCombo)) }
        filesRow.add(reload)

        val optionsRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), JBUI.scale(3)))
        search.textEditor.emptyText.text = DevUtilityBundle.message("compare.search.placeholder")
        search.preferredSize = Dimension(JBUI.scale(220), search.preferredSize.height)
        search.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = refreshTable()
            override fun removeUpdate(e: DocumentEvent) = refreshTable()
            override fun changedUpdate(e: DocumentEvent) = refreshTable()
        })
        optionsRow.add(search)

        onlyDifferences.addActionListener { refreshTable() }
        ignoreCase.addActionListener { recompute() }
        trimValues.addActionListener { recompute() }
        optionsRow.add(onlyDifferences)
        optionsRow.add(ignoreCase)
        optionsRow.add(trimValues)

        optionsRow.add(button(DevUtilityBundle.message("compare.action.diff.normalized")) { showNormalizedDiff() })
        optionsRow.add(button(DevUtilityBundle.message("compare.action.diff.raw")) { showRawDiff() })
        optionsRow.add(button(DevUtilityBundle.message("compare.action.copy.report")) { copyReport() })
        optionsRow.add(button(DevUtilityBundle.message("compare.action.save.report")) { openReport() })

        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.add(filesRow)
        panel.add(optionsRow)
        return panel
    }

    private fun buildContent(): JPanel {
        table.setShowGrid(false)
        table.selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
        table.autoResizeMode = JTable.AUTO_RESIZE_SUBSEQUENT_COLUMNS
        table.rowSorter = TableRowSorter(tableModel)
        val renderer = DiffCellRenderer()
        table.setDefaultRenderer(Any::class.java, renderer)
        table.setDefaultRenderer(String::class.java, renderer)
        table.emptyText.text = DevUtilityBundle.message("compare.empty")

        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean {
                val viewRow = table.rowAtPoint(event.point)
                val viewColumn = table.columnAtPoint(event.point)
                if (viewRow < 0) return false
                val row = tableModel.rowAt(table.convertRowIndexToModel(viewRow))
                // Clicking a value cell goes to that side; the key column falls back to
                // whichever side actually has the key.
                when {
                    viewColumn == 1 -> navigate(selectedFile(leftCombo), row.leftLine)
                    viewColumn == 2 -> navigate(selectedFile(rightCombo), row.rightLine)
                    row.left != null -> navigate(selectedFile(leftCombo), row.leftLine)
                    else -> navigate(selectedFile(rightCombo), row.rightLine)
                }
                return true
            }
        }.installOn(table)

        installContextMenu()

        val center = JPanel(BorderLayout())
        center.add(JBScrollPane(table), BorderLayout.CENTER)

        summary.border = JBUI.Borders.empty(3, 6)
        center.add(summary, BorderLayout.SOUTH)
        return center
    }

    private fun installContextMenu() {
        table.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mousePressed(e: MouseEvent) = maybeShow(e)
            override fun mouseReleased(e: MouseEvent) = maybeShow(e)

            private fun maybeShow(e: MouseEvent) {
                if (!e.isPopupTrigger) return
                val viewRow = table.rowAtPoint(e.point)
                if (viewRow < 0) return
                table.setRowSelectionInterval(viewRow, viewRow)
                val row = tableModel.rowAt(table.convertRowIndexToModel(viewRow))

                val menu = JPopupMenu()
                menu.add(menuItem("Copy key") { copyToClipboard(row.key) })
                row.left?.let { menu.add(menuItem("Copy left value") { copyToClipboard(it) }) }
                row.right?.let { menu.add(menuItem("Copy right value") { copyToClipboard(it) }) }
                menu.addSeparator()
                if (row.left != null) {
                    menu.add(menuItem("Jump to left (line ${row.leftLine})") {
                        navigate(selectedFile(leftCombo), row.leftLine)
                    })
                }
                if (row.right != null) {
                    menu.add(menuItem("Jump to right (line ${row.rightLine})") {
                        navigate(selectedFile(rightCombo), row.rightLine)
                    })
                }
                menu.show(table, e.x, e.y)
            }
        })
    }

    private fun menuItem(text: String, action: () -> Unit) = JMenuItem(text).apply { addActionListener { action() } }

    private fun button(text: String, action: () -> Unit) = JButton(text).apply { addActionListener { action() } }

    private fun configureCombo(combo: ComboBox<FileItem>): ComboBox<FileItem> {
        combo.preferredSize = Dimension(JBUI.scale(280), combo.preferredSize.height)
        combo.renderer = object : SimpleListCellRenderer<FileItem>() {
            override fun customize(
                list: JList<out FileItem>,
                value: FileItem?,
                index: Int,
                selected: Boolean,
                hasFocus: Boolean,
            ) {
                text = value?.label ?: "<none>"
                toolTipText = value?.tooltip?.takeIf { it.isNotEmpty() }
            }
        }
        combo.addItemListener { e ->
            if (e.stateChange == ItemEvent.SELECTED && !suppressReload) recompute()
        }
        return combo
    }

    private fun browseButton(onChosen: (VirtualFile) -> Unit) = JButton("…").apply {
        toolTipText = "Choose a file outside the project"
        addActionListener {
            val descriptor = FileChooserDescriptorFactory.createSingleFileDescriptor()
                .withTitle("Select Configuration File")
                .withFileFilter { PropertyFileScanner.isConfigFile(it) }
            FileChooser.chooseFile(descriptor, project, null) { chosen -> onChosen(chosen) }
        }
    }

    // ------------------------------------------------------------------ data flow

    private fun reloadFileList(selectLeft: VirtualFile?, selectRight: VirtualFile?) {
        ApplicationManager.getApplication().executeOnPooledThread {
            if (project.isDisposed) return@executeOnPooledThread
            val files = try {
                PropertyFileScanner.scan(project)
            } catch (e: Exception) {
                emptyList()
            }
            ApplicationManager.getApplication().invokeLater({
                if (project.isDisposed) return@invokeLater
                populate(files, selectLeft, selectRight)
            }, ModalityState.any())
        }
    }

    private fun populate(files: List<VirtualFile>, selectLeft: VirtualFile?, selectRight: VirtualFile?) {
        val previousLeft = selectLeft ?: selectedFile(leftCombo)
        val previousRight = selectRight ?: selectedFile(rightCombo)

        // Externally chosen files may live outside the project; keep them in the list.
        val all = LinkedHashSet<VirtualFile>(files)
        previousLeft?.let { all += it }
        previousRight?.let { all += it }

        val items = all.map { FileItem(it, labelFor(it), it.path) }

        suppressReload = true
        try {
            leftModel.removeAllElements()
            rightModel.removeAllElements()
            val none = FileItem(null, "<none>", "")
            leftModel.addElement(none)
            rightModel.addElement(none)
            items.forEach {
                leftModel.addElement(it)
                rightModel.addElement(it)
            }
            selectFile(leftCombo, leftModel, previousLeft)
            selectFile(rightCombo, rightModel, previousRight)
        } finally {
            suppressReload = false
        }
        recompute()
    }

    private fun labelFor(file: VirtualFile): String {
        val base = project.basePath?.replace('\\', '/')
        val path = file.parent?.path?.replace('\\', '/') ?: ""
        val relative = if (base != null && path.startsWith(base)) path.removePrefix(base).trimStart('/') else path
        return if (relative.isEmpty()) file.name else "${file.name}  —  $relative"
    }

    private fun selectFile(combo: ComboBox<FileItem>, model: DefaultComboBoxModel<FileItem>, file: VirtualFile?) {
        if (file == null) {
            combo.selectedIndex = 0
            return
        }
        for (i in 0 until model.size) {
            if (model.getElementAt(i).file?.path == file.path) {
                combo.selectedIndex = i
                return
            }
        }
        combo.selectedIndex = 0
    }

    private fun setSelection(combo: ComboBox<FileItem>, model: DefaultComboBoxModel<FileItem>, file: VirtualFile) {
        if ((0 until model.size).none { model.getElementAt(it).file?.path == file.path }) {
            model.addElement(FileItem(file, labelFor(file), file.path))
        }
        selectFile(combo, model, file)
        recompute()
    }

    private fun selectedFile(combo: ComboBox<FileItem>): VirtualFile? = (combo.selectedItem as? FileItem)?.file

    private fun swapSides() {
        val left = selectedFile(leftCombo)
        val right = selectedFile(rightCombo)
        suppressReload = true
        try {
            selectFile(leftCombo, leftModel, right)
            selectFile(rightCombo, rightModel, left)
        } finally {
            suppressReload = false
        }
        recompute()
    }

    private fun recompute() {
        val leftFile = selectedFile(leftCombo)
        val rightFile = selectedFile(rightCombo)
        if (leftFile == null || rightFile == null) {
            leftSnapshot = null
            rightSnapshot = null
            result = null
            tableModel.update(emptyList(), "Left", "Right")
            summary.text = DevUtilityBundle.message("compare.empty")
            return
        }

        // Snapshot the options on the EDT; the parse itself runs off it.
        val options = DiffOptions(ignoreCase = ignoreCase.isSelected, trimValues = trimValues.isSelected)

        ApplicationManager.getApplication().executeOnPooledThread {
            if (project.isDisposed) return@executeOnPooledThread
            val left = PropertyFileReader.read(project, leftFile)
            val right = PropertyFileReader.read(project, rightFile)
            val computed = PropertyDiffEngine.diff(left, right, options)

            ApplicationManager.getApplication().invokeLater({
                if (project.isDisposed) return@invokeLater
                leftSnapshot = left
                rightSnapshot = right
                result = computed
                refreshTable()
            }, ModalityState.any())
        }
    }

    private fun refreshTable() {
        val current = result ?: return
        val query = search.text.trim().lowercase()

        val rows = current.rows.filter { row ->
            if (onlyDifferences.isSelected && row.status == DiffStatus.SAME) return@filter false
            if (query.isEmpty()) return@filter true
            row.key.lowercase().contains(query) ||
                row.left?.lowercase()?.contains(query) == true ||
                row.right?.lowercase()?.contains(query) == true
        }

        tableModel.update(rows, current.leftName, current.rightName)
        summary.text = DevUtilityBundle.message(
            "compare.summary",
            current.total, current.same, current.different, current.onlyLeft, current.onlyRight,
        ) + if (rows.size != current.total) "   (showing ${rows.size})" else ""

        table.columnModel.getColumn(0).preferredWidth = JBUI.scale(320)
        table.columnModel.getColumn(3).preferredWidth = JBUI.scale(90)
    }

    // ------------------------------------------------------------------ actions

    private fun showNormalizedDiff() {
        val left = leftSnapshot
        val right = rightSnapshot
        if (left == null || right == null) return

        val keys = (left.keys + right.keys).sorted()
        val factory = DiffContentFactory.getInstance()
        val request = SimpleDiffRequest(
            "Dev Utility — normalized comparison",
            factory.create(project, PropertyDiffEngine.normalize(left, keys), PlainTextFileType.INSTANCE),
            factory.create(project, PropertyDiffEngine.normalize(right, keys), PlainTextFileType.INSTANCE),
            left.displayName,
            right.displayName,
        )
        DiffManager.getInstance().showDiff(project, request)
    }

    private fun showRawDiff() {
        val leftFile = selectedFile(leftCombo) ?: return
        val rightFile = selectedFile(rightCombo) ?: return
        val factory = DiffContentFactory.getInstance()
        val request = SimpleDiffRequest(
            "Dev Utility — raw file comparison",
            factory.create(project, leftFile),
            factory.create(project, rightFile),
            leftFile.name,
            rightFile.name,
        )
        DiffManager.getInstance().showDiff(project, request)
    }

    private fun buildReport(): String? {
        val current = result ?: return null
        return PropertyDiffEngine.report(current, current.rows)
    }

    private fun copyReport() {
        copyToClipboard(buildReport() ?: return)
    }

    private fun openReport() {
        val text = buildReport() ?: return
        val file = ScratchRootType.getInstance()
            .createScratchFile(project, "property-diff.txt", PlainTextFileType.INSTANCE.language, text)
            ?: return
        FileEditorManager.getInstance(project).openFile(file, true)
    }

    private fun copyToClipboard(text: String) {
        CopyPasteManager.getInstance().setContents(StringSelection(text))
    }

    private fun navigate(file: VirtualFile?, line: Int) {
        if (file == null || line <= 0) return
        OpenFileDescriptor(project, file, line - 1, 0).navigate(true)
    }

    override fun dispose() = Unit

    // ------------------------------------------------------------------ table model

    private class DiffTableModel : AbstractTableModel() {
        private var rows: List<DiffRow> = emptyList()
        private var leftName = "Left"
        private var rightName = "Right"

        fun update(newRows: List<DiffRow>, left: String, right: String) {
            rows = newRows
            leftName = left
            rightName = right
            fireTableStructureChanged()
        }

        fun rowAt(index: Int): DiffRow = rows[index]

        override fun getRowCount(): Int = rows.size

        override fun getColumnCount(): Int = 4

        override fun getColumnName(column: Int): String = when (column) {
            0 -> DevUtilityBundle.message("compare.column.key")
            1 -> leftName
            2 -> rightName
            else -> DevUtilityBundle.message("compare.column.status")
        }

        override fun getColumnClass(columnIndex: Int): Class<*> = String::class.java

        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
            val row = rows[rowIndex]
            return when (columnIndex) {
                0 -> row.key
                1 -> row.left?.replace('\n', '⏎') ?: ABSENT
                2 -> row.right?.replace('\n', '⏎') ?: ABSENT
                else -> row.status.label
            }
        }
    }

    private inner class DiffCellRenderer : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(
            table: JTable,
            value: Any?,
            isSelected: Boolean,
            hasFocus: Boolean,
            row: Int,
            column: Int,
        ): Component {
            val component = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
            horizontalAlignment = if (column == 3) SwingConstants.CENTER else SwingConstants.LEADING

            val diffRow = runCatching { tableModel.rowAt(table.convertRowIndexToModel(row)) }.getOrNull()
            if (!isSelected) {
                background = when (diffRow?.status) {
                    DiffStatus.DIFFERENT -> COLOR_DIFFERENT
                    DiffStatus.ONLY_LEFT -> COLOR_ONLY_LEFT
                    DiffStatus.ONLY_RIGHT -> COLOR_ONLY_RIGHT
                    else -> table.background
                }
                foreground = if (value == ABSENT) JBColor.GRAY else table.foreground
            }
            toolTipText = value?.toString()?.takeIf { it.isNotEmpty() && it != ABSENT }
            return component
        }
    }
}
