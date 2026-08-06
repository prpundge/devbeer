package com.devutility.strings

import com.devutility.DevUtilityBundle
import com.intellij.openapi.Disposable
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.JBSplitter
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.datatransfer.StringSelection
import javax.swing.BoxLayout
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/** Above this size the live preview is skipped and you have to press Apply. */
private const val LIVE_PREVIEW_LIMIT = 200_000

class StringToolboxPanel(private val project: Project) : SimpleToolWindowPanel(true, true), Disposable {

    private sealed class Row {
        class Header(val category: String) : Row()
        class Item(val transform: StringTransform) : Row()
    }

    private val search = SearchTextField()
    private val listModel = DefaultListModel<Row>()
    private val list = JBList(listModel)

    private val input = JBTextArea()
    private val output = JBTextArea()
    private val stats = JBLabel(" ")
    private val livePreview = JBCheckBox(DevUtilityBundle.message("toolbox.live"), true)

    /** Set while the selection listener is nudging past a category header. */
    private var adjustingSelection = false

    init {
        setContent(buildContent())
        populateList("")
    }

    fun setInput(text: String) {
        input.text = text
        apply()
    }

    // ------------------------------------------------------------------ UI

    private fun buildContent(): JComponent {
        val splitter = JBSplitter(false, 0.32f)
        splitter.firstComponent = buildTransformList()
        splitter.secondComponent = buildEditors()
        splitter.setHonorComponentsMinimumSize(true)

        val root = JPanel(BorderLayout())
        root.add(splitter, BorderLayout.CENTER)
        root.add(buildActionBar(), BorderLayout.SOUTH)
        return root
    }

    private fun buildTransformList(): JComponent {
        search.textEditor.emptyText.text = DevUtilityBundle.message("toolbox.search.placeholder")
        search.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = populateList(search.text)
            override fun removeUpdate(e: DocumentEvent) = populateList(search.text)
            override fun changedUpdate(e: DocumentEvent) = populateList(search.text)
        })

        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.cellRenderer = object : ColoredListCellRenderer<Row>() {
            override fun customizeCellRenderer(
                list: JList<out Row>,
                value: Row?,
                index: Int,
                selected: Boolean,
                hasFocus: Boolean,
            ) {
                when (value) {
                    is Row.Header -> append(value.category.uppercase(), SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                    is Row.Item -> {
                        append("  ")
                        append(value.transform.title)
                        toolTipText = value.transform.description
                    }
                    else -> Unit
                }
            }
        }
        list.addListSelectionListener {
            if (it.valueIsAdjusting || adjustingSelection) return@addListSelectionListener
            skipHeader()
            apply()
        }

        val panel = JPanel(BorderLayout())
        panel.add(search, BorderLayout.NORTH)
        panel.add(JBScrollPane(list), BorderLayout.CENTER)
        panel.minimumSize = Dimension(JBUI.scale(180), JBUI.scale(120))
        return panel
    }

    private fun buildEditors(): JComponent {
        val monospace = EditorColorsManager.getInstance().globalScheme.getFont(EditorFontType.PLAIN)
        listOf(input, output).forEach {
            it.font = monospace
            it.tabSize = 2
            it.border = JBUI.Borders.empty(2)
        }
        output.isEditable = false

        input.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = onInputChanged()
            override fun removeUpdate(e: DocumentEvent) = onInputChanged()
            override fun changedUpdate(e: DocumentEvent) = onInputChanged()
        })

        val splitter = JBSplitter(true, 0.5f)
        splitter.firstComponent = titled(DevUtilityBundle.message("toolbox.input"), input)
        splitter.secondComponent = titled(DevUtilityBundle.message("toolbox.output"), output)
        return splitter
    }

    private fun titled(title: String, area: JBTextArea): JComponent {
        val panel = JPanel(BorderLayout())
        val label = JBLabel(title)
        label.border = JBUI.Borders.empty(2, 6)
        panel.add(label, BorderLayout.NORTH)
        panel.add(JBScrollPane(area), BorderLayout.CENTER)
        panel.minimumSize = Dimension(JBUI.scale(200), JBUI.scale(80))
        return panel
    }

    private fun buildActionBar(): JComponent {
        val buttons = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), JBUI.scale(3)))
        buttons.add(button(DevUtilityBundle.message("toolbox.apply")) { apply() })
        buttons.add(button(DevUtilityBundle.message("toolbox.copy")) {
            CopyPasteManager.getInstance().setContents(StringSelection(output.text))
        })
        buttons.add(button(DevUtilityBundle.message("toolbox.chain")) {
            input.text = output.text
            apply()
        })
        buttons.add(button(DevUtilityBundle.message("toolbox.clear")) {
            input.text = ""
            output.text = ""
        })
        buttons.add(button(DevUtilityBundle.message("toolbox.from.editor")) { loadFromEditor() })
        buttons.add(button(DevUtilityBundle.message("toolbox.to.editor")) { writeBackToEditor() })
        buttons.add(livePreview)

        stats.border = JBUI.Borders.empty(2, 6)

        val bar = JPanel()
        bar.layout = BoxLayout(bar, BoxLayout.Y_AXIS)
        bar.add(buttons)
        bar.add(stats)
        return bar
    }

    private fun button(text: String, action: () -> Unit) = JButton(text).apply { addActionListener { action() } }

    // ------------------------------------------------------------------ behaviour

    private fun populateList(query: String) {
        val previous = selectedTransform()?.id
        val needle = query.trim().lowercase()

        listModel.clear()
        for ((category, transforms) in StringTransforms.BY_CATEGORY) {
            val matching = transforms.filter {
                needle.isEmpty() ||
                    it.title.lowercase().contains(needle) ||
                    it.category.lowercase().contains(needle) ||
                    it.description.lowercase().contains(needle)
            }
            if (matching.isEmpty()) continue
            listModel.addElement(Row.Header(category))
            matching.forEach { listModel.addElement(Row.Item(it)) }
        }

        val restored = (0 until listModel.size()).firstOrNull {
            (listModel.get(it) as? Row.Item)?.transform?.id == previous
        }
        val firstItem = (0 until listModel.size()).firstOrNull { listModel.get(it) is Row.Item }
        adjustingSelection = true
        list.selectedIndex = restored ?: firstItem ?: -1
        adjustingSelection = false
    }

    /** Category rows are labels, not choices — step past them. */
    private fun skipHeader() {
        val index = list.selectedIndex
        if (index < 0 || listModel.get(index) !is Row.Header) return
        adjustingSelection = true
        val next = (index + 1 until listModel.size()).firstOrNull { listModel.get(it) is Row.Item }
            ?: (index - 1 downTo 0).firstOrNull { listModel.get(it) is Row.Item }
        list.selectedIndex = next ?: -1
        adjustingSelection = false
    }

    private fun selectedTransform(): StringTransform? =
        (list.selectedValue as? Row.Item)?.transform

    private fun onInputChanged() {
        updateStats()
        if (livePreview.isSelected && input.document.length <= LIVE_PREVIEW_LIMIT) apply()
    }

    private fun apply() {
        val transform = selectedTransform()
        val text = input.text
        if (transform == null) {
            output.text = ""
            return
        }
        output.text = try {
            transform.apply(text)
        } catch (e: Exception) {
            "⚠  ${transform.title} failed: ${e.message ?: e.javaClass.simpleName}"
        }
        output.caretPosition = 0
        updateStats()
    }

    private fun updateStats() {
        val text = input.text
        stats.text = "in: ${text.length} chars, ${text.lines().size} lines    ·    out: ${output.text.length} chars"
    }

    private fun currentEditor(): Editor? = FileEditorManager.getInstance(project).selectedTextEditor

    private fun loadFromEditor() {
        val editor = currentEditor() ?: return
        val selected = editor.selectionModel.selectedText ?: editor.document.text
        setInput(selected)
    }

    private fun writeBackToEditor() {
        val editor = currentEditor() ?: return
        if (!editor.document.isWritable) return
        val model = editor.selectionModel
        val start = if (model.hasSelection()) model.selectionStart else 0
        val end = if (model.hasSelection()) model.selectionEnd else editor.document.textLength
        val replacement = output.text
        WriteCommandAction.runWriteCommandAction(project, "Apply Dev Utility Transformation", "DevUtility", {
            editor.document.replaceString(start, end, replacement)
        })
    }

    override fun dispose() = Unit
}
