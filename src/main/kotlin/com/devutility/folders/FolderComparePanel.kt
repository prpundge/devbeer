package com.devutility.folders

import com.devutility.DevUtilityBundle
import com.devutility.compare.PropertyCompareToolWindow
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
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.JBColor
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseEvent
import javax.swing.BoxLayout
import javax.swing.JButton
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

/**
 * Directory-tree comparison.
 *
 * The IDE already ships a plain directory diff, so the reason this exists is the extra column:
 * for `.properties` and YAML pairs it reports how the *keys* differ, and drilling into one opens
 * the key-level Property Compare rather than a text diff.
 */
class FolderComparePanel(private val project: Project) : SimpleToolWindowPanel(true, true), Disposable {

    private val leftPath = JBTextField()
    private val rightPath = JBTextField()

    private val onlyDifferences = JBCheckBox(DevUtilityBundle.message("folders.only.differences"), true)
    private val compareContents = JBCheckBox(DevUtilityBundle.message("folders.compare.contents"), true)
    private val skipGenerated = JBCheckBox(DevUtilityBundle.message("folders.skip.generated"), true)
    private val search = SearchTextField()
    private val summary = JBLabel(DevUtilityBundle.message("folders.empty"))

    private val tableModel = FolderTableModel()
    private val table = JBTable(tableModel)

    private var result: FolderDiffResult? = null

    init {
        toolbar = buildToolbar()
        setContent(buildContent())
    }

    // ------------------------------------------------------------------ public API

    fun load(left: VirtualFile?, right: VirtualFile?) {
        left?.let { leftPath.text = it.path }
        right?.let { rightPath.text = it.path }
        if (leftPath.text.isNotBlank() && rightPath.text.isNotBlank()) compare()
    }

    // ------------------------------------------------------------------ UI

    private fun buildToolbar(): JPanel {
        val row1 = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), JBUI.scale(3)))
        row1.add(JBLabel(DevUtilityBundle.message("compare.left") + ":"))
        row1.add(pathField(leftPath))
        row1.add(browseButton(leftPath))
        row1.add(JBLabel(DevUtilityBundle.message("compare.right") + ":"))
        row1.add(pathField(rightPath))
        row1.add(browseButton(rightPath))

        val swap = JButton("⇄")
        swap.toolTipText = DevUtilityBundle.message("compare.action.swap")
        swap.addActionListener {
            val tmp = leftPath.text
            leftPath.text = rightPath.text
            rightPath.text = tmp
            compare()
        }
        row1.add(swap)

        val row2 = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), JBUI.scale(3)))
        row2.add(button(DevUtilityBundle.message("folders.action.compare")) { compare() })

        search.textEditor.emptyText.text = DevUtilityBundle.message("folders.search.placeholder")
        search.preferredSize = Dimension(JBUI.scale(200), search.preferredSize.height)
        search.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = refreshTable()
            override fun removeUpdate(e: DocumentEvent) = refreshTable()
            override fun changedUpdate(e: DocumentEvent) = refreshTable()
        })
        row2.add(search)

        onlyDifferences.addActionListener { refreshTable() }
        compareContents.toolTipText = DevUtilityBundle.message("folders.compare.contents.tip")
        row2.add(onlyDifferences)
        row2.add(compareContents)
        row2.add(skipGenerated)
        row2.add(button(DevUtilityBundle.message("compare.action.copy.report")) { copyReport() })
        row2.add(button(DevUtilityBundle.message("compare.action.save.report")) { openReport() })

        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.add(row1)
        panel.add(row2)
        return panel
    }

    private fun pathField(field: JBTextField) = field.apply {
        preferredSize = Dimension(JBUI.scale(240), preferredSize.height)
        emptyText.text = DevUtilityBundle.message("folders.choose")
    }

    private fun browseButton(target: JBTextField) = JButton("…").apply {
        addActionListener {
            val descriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor()
                .withTitle(DevUtilityBundle.message("folders.choose"))
            val current = target.text.takeIf { it.isNotBlank() }
                ?.let { LocalFileSystem.getInstance().findFileByPath(it) }
            FileChooser.chooseFile(descriptor, project, current) { chosen -> target.text = chosen.path }
        }
    }

    private fun button(text: String, action: () -> Unit) = JButton(text).apply { addActionListener { action() } }

    private fun buildContent(): JPanel {
        table.setShowGrid(false)
        table.selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
        table.autoResizeMode = JTable.AUTO_RESIZE_SUBSEQUENT_COLUMNS
        table.rowSorter = TableRowSorter(tableModel)
        val renderer = FolderCellRenderer()
        table.setDefaultRenderer(Any::class.java, renderer)
        table.setDefaultRenderer(String::class.java, renderer)
        table.emptyText.text = DevUtilityBundle.message("folders.empty")

        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean {
                val viewRow = table.rowAtPoint(event.point)
                if (viewRow < 0) return false
                openRow(tableModel.rowAt(table.convertRowIndexToModel(viewRow)))
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
                if (row.left != null && row.right != null) {
                    if (row.isConfigPair) {
                        menu.add(menuItem(DevUtilityBundle.message("folders.menu.compare.keys")) {
                            PropertyCompareToolWindow.show(project, row.left, row.right)
                        })
                    }
                    menu.add(menuItem(DevUtilityBundle.message("folders.menu.diff")) { showDiff(row) })
                    menu.addSeparator()
                }
                row.left?.let { menu.add(menuItem(DevUtilityBundle.message("folders.menu.open.left")) { open(it) }) }
                row.right?.let { menu.add(menuItem(DevUtilityBundle.message("folders.menu.open.right")) { open(it) }) }
                menu.addSeparator()
                menu.add(menuItem(DevUtilityBundle.message("folders.menu.copy.path")) {
                    copyToClipboard(row.relativePath)
                })
                menu.show(table, e.x, e.y)
            }
        })
    }

    private fun menuItem(text: String, action: () -> Unit) = JMenuItem(text).apply { addActionListener { action() } }

    // ------------------------------------------------------------------ behaviour

    private fun compare() {
        val left = resolve(leftPath.text) ?: run {
            summary.text = DevUtilityBundle.message("folders.invalid.left")
            return
        }
        val right = resolve(rightPath.text) ?: run {
            summary.text = DevUtilityBundle.message("folders.invalid.right")
            return
        }

        val options = FolderDiffOptions(
            compareContents = compareContents.isSelected,
            skipGeneratedDirs = skipGenerated.isSelected,
        )

        // Reading two whole trees can take a while, so it runs as a cancellable background task.
        ProgressManager.getInstance().run(
            object : Task.Backgroundable(project, DevUtilityBundle.message("folders.progress"), true) {
                private var computed: FolderDiffResult? = null

                override fun run(indicator: ProgressIndicator) {
                    computed = FolderDiffEngine.compare(project, left, right, options, indicator)
                }

                override fun onSuccess() {
                    result = computed
                    refreshTable()
                }
            },
        )
    }

    private fun resolve(path: String): VirtualFile? {
        if (path.isBlank()) return null
        val file = LocalFileSystem.getInstance().refreshAndFindFileByPath(path.trim().replace('\\', '/'))
        return file?.takeIf { it.isValid && it.isDirectory }
    }

    private fun refreshTable() {
        val current = result ?: return
        val query = search.text.trim().lowercase()

        val rows = current.rows.filter { row ->
            if (onlyDifferences.isSelected && row.status == FileStatus.SAME) return@filter false
            query.isEmpty() || row.relativePath.lowercase().contains(query)
        }

        tableModel.update(rows)
        summary.text = DevUtilityBundle.message(
            "folders.summary",
            current.total, current.same, current.different, current.onlyLeft, current.onlyRight,
        ) +
            (if (rows.size != current.total) "   (showing ${rows.size})" else "") +
            (if (current.truncated) "   ⚠ truncated" else "")

        table.columnModel.getColumn(0).preferredWidth = JBUI.scale(360)
        table.columnModel.getColumn(3).preferredWidth = JBUI.scale(90)
    }

    private fun openRow(row: FolderRow) {
        when {
            // A config pair goes to the key-level view — a text diff of YAML vs .properties is useless.
            row.isConfigPair -> PropertyCompareToolWindow.show(project, row.left, row.right)
            row.left != null && row.right != null -> showDiff(row)
            else -> open(row.left ?: row.right ?: return)
        }
    }

    private fun showDiff(row: FolderRow) {
        val left = row.left ?: return
        val right = row.right ?: return
        val factory = DiffContentFactory.getInstance()
        DiffManager.getInstance().showDiff(
            project,
            SimpleDiffRequest(
                row.relativePath,
                factory.create(project, left),
                factory.create(project, right),
                left.path,
                right.path,
            ),
        )
    }

    private fun open(file: VirtualFile) {
        OpenFileDescriptor(project, file).navigate(true)
    }

    private fun buildReport(): String? {
        val current = result ?: return null
        return FolderDiffEngine.report(current, current.rows)
    }

    private fun copyReport() {
        copyToClipboard(buildReport() ?: return)
    }

    private fun openReport() {
        val text = buildReport() ?: return
        val file = ScratchRootType.getInstance()
            .createScratchFile(project, "folder-diff.txt", PlainTextFileType.INSTANCE.language, text)
            ?: return
        FileEditorManager.getInstance(project).openFile(file, true)
    }

    private fun copyToClipboard(text: String) {
        CopyPasteManager.getInstance().setContents(StringSelection(text))
    }

    override fun dispose() = Unit

    // ------------------------------------------------------------------ table

    private class FolderTableModel : AbstractTableModel() {
        private var rows: List<FolderRow> = emptyList()

        fun update(newRows: List<FolderRow>) {
            rows = newRows
            fireTableDataChanged()
        }

        fun rowAt(index: Int): FolderRow = rows[index]

        override fun getRowCount(): Int = rows.size

        override fun getColumnCount(): Int = 5

        override fun getColumnName(column: Int): String = when (column) {
            0 -> DevUtilityBundle.message("folders.column.path")
            1 -> DevUtilityBundle.message("folders.column.left")
            2 -> DevUtilityBundle.message("folders.column.right")
            3 -> DevUtilityBundle.message("compare.column.status")
            else -> DevUtilityBundle.message("folders.column.keys")
        }

        override fun getColumnClass(columnIndex: Int): Class<*> = String::class.java

        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
            val row = rows[rowIndex]
            return when (columnIndex) {
                0 -> row.relativePath
                1 -> size(row.leftSize)
                2 -> size(row.rightSize)
                3 -> row.status.label
                else -> row.keyDelta?.describe() ?: ""
            }
        }

        private fun size(bytes: Long): String = when {
            bytes < 0 -> ABSENT
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
            else -> "%.1f MB".format(bytes / (1024.0 * 1024))
        }
    }

    private inner class FolderCellRenderer : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(
            table: JTable,
            value: Any?,
            isSelected: Boolean,
            hasFocus: Boolean,
            row: Int,
            column: Int,
        ): Component {
            val component = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
            horizontalAlignment = when (column) {
                1, 2 -> SwingConstants.TRAILING
                3 -> SwingConstants.CENTER
                else -> SwingConstants.LEADING
            }
            val diffRow = runCatching { tableModel.rowAt(table.convertRowIndexToModel(row)) }.getOrNull()
            if (!isSelected) {
                background = when (diffRow?.status) {
                    FileStatus.DIFFERENT -> COLOR_DIFFERENT
                    FileStatus.ONLY_LEFT -> COLOR_ONLY_LEFT
                    FileStatus.ONLY_RIGHT -> COLOR_ONLY_RIGHT
                    else -> table.background
                }
                foreground = if (value == ABSENT) JBColor.GRAY else table.foreground
            }
            toolTipText = diffRow?.relativePath
            return component
        }
    }
}
