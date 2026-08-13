package com.devutility.json

import com.devutility.DevUtilityBundle
import com.intellij.openapi.Disposable
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.ui.JBColor
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.datatransfer.StringSelection
import javax.swing.BoxLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

private const val LIVE_LIMIT = 400_000

private val OK_COLOR = JBColor(0x1A7F37, 0x57A65F)
private val ERROR_COLOR = JBColor(0xC0392B, 0xE06C60)

class JsonFormatterPanel(private val project: Project) : SimpleToolWindowPanel(true, true), Disposable {

    private data class Indent(val label: String, val value: String) {
        override fun toString() = label
    }

    private val indents = DefaultComboBoxModel(
        arrayOf(Indent("2 spaces", "  "), Indent("4 spaces", "    "), Indent("Tab", "\t")),
    )
    private val indentCombo = ComboBox(indents)
    private val sortKeys = JBCheckBox(DevUtilityBundle.message("json.sort.keys"), false)
    private val lenient = JBCheckBox(DevUtilityBundle.message("json.lenient"), true)
    private val livePreview = JBCheckBox(DevUtilityBundle.message("toolbox.live"), true)

    private val input = JBTextArea()
    private val output = JBTextArea()
    private val status = JBLabel(" ")

    init {
        toolbar = buildToolbar()
        setContent(buildContent())
    }

    fun setInput(text: String) {
        input.text = text
        format()
    }

    // ------------------------------------------------------------------ UI

    private fun buildToolbar(): JComponent {
        val row = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), JBUI.scale(3)))
        row.add(button(DevUtilityBundle.message("json.format")) { format() })
        row.add(button(DevUtilityBundle.message("json.minify")) { minify() })
        row.add(JBLabel(DevUtilityBundle.message("json.indent") + ":"))
        indentCombo.preferredSize = Dimension(JBUI.scale(100), indentCombo.preferredSize.height)
        indentCombo.addActionListener { format() }
        row.add(indentCombo)
        sortKeys.addActionListener { format() }
        lenient.toolTipText = DevUtilityBundle.message("json.lenient.tip")
        lenient.addActionListener { format() }
        row.add(sortKeys)
        row.add(lenient)

        val row2 = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), JBUI.scale(3)))
        row2.add(button(DevUtilityBundle.message("toolbox.copy")) {
            CopyPasteManager.getInstance().setContents(StringSelection(output.text))
        })
        row2.add(button(DevUtilityBundle.message("toolbox.chain")) {
            input.text = output.text
            format()
        })
        row2.add(button(DevUtilityBundle.message("toolbox.clear")) {
            input.text = ""
            output.text = ""
        })
        row2.add(button(DevUtilityBundle.message("toolbox.from.editor")) { loadFromEditor() })
        row2.add(button(DevUtilityBundle.message("toolbox.to.editor")) { writeBackToEditor() })
        row2.add(livePreview)

        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.add(row)
        panel.add(row2)
        return panel
    }

    private fun button(text: String, action: () -> Unit) = JButton(text).apply { addActionListener { action() } }

    private fun buildContent(): JComponent {
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

        val splitter = JBSplitter(true, 0.45f)
        splitter.firstComponent = titled(DevUtilityBundle.message("toolbox.input"), input)
        splitter.secondComponent = titled(DevUtilityBundle.message("toolbox.output"), output)

        val center = JPanel(BorderLayout())
        center.add(splitter, BorderLayout.CENTER)
        status.border = JBUI.Borders.empty(3, 6)
        center.add(status, BorderLayout.SOUTH)
        return center
    }

    private fun titled(title: String, area: JBTextArea): JComponent {
        val panel = JPanel(BorderLayout())
        val label = JBLabel(title)
        label.border = JBUI.Borders.empty(2, 6)
        panel.add(label, BorderLayout.NORTH)
        panel.add(JBScrollPane(area), BorderLayout.CENTER)
        panel.minimumSize = Dimension(JBUI.scale(200), JBUI.scale(70))
        return panel
    }

    // ------------------------------------------------------------------ behaviour

    private fun options() = JsonFormatOptions(
        indent = (indentCombo.selectedItem as? Indent)?.value ?: "  ",
        sortKeys = sortKeys.isSelected,
        lenient = lenient.isSelected,
    )

    private fun onInputChanged() {
        if (livePreview.isSelected && input.document.length <= LIVE_LIMIT) format()
    }

    private fun format() = run { JsonFormatter.format(it, options()) }

    private fun minify() = run { JsonFormatter.minify(it, options()) }

    private fun run(transform: (String) -> String) {
        val text = input.text
        if (text.isBlank()) {
            output.text = ""
            status.text = " "
            return
        }
        try {
            output.text = transform(text)
            output.caretPosition = 0
            status.foreground = OK_COLOR
            status.text = DevUtilityBundle.message("json.valid", output.text.length)
        } catch (e: JsonSyntaxException) {
            // Leave the previous output alone; replacing it with an error message loses work.
            status.foreground = ERROR_COLOR
            status.text = DevUtilityBundle.message("json.invalid", e.reason, e.line, e.column)
            highlight(e.offset)
        } catch (e: Exception) {
            status.foreground = ERROR_COLOR
            status.text = e.message ?: e.javaClass.simpleName
        }
    }

    /** Puts the caret on the offending character so the eye goes straight to it. */
    private fun highlight(offset: Int) {
        val safe = offset.coerceIn(0, input.document.length)
        input.caretPosition = safe
    }

    private fun currentEditor(): Editor? = FileEditorManager.getInstance(project).selectedTextEditor

    private fun loadFromEditor() {
        val editor = currentEditor() ?: return
        setInput(editor.selectionModel.selectedText ?: editor.document.text)
    }

    private fun writeBackToEditor() {
        val editor = currentEditor() ?: return
        if (!editor.document.isWritable) return
        val model = editor.selectionModel
        val start = if (model.hasSelection()) model.selectionStart else 0
        val end = if (model.hasSelection()) model.selectionEnd else editor.document.textLength
        val replacement = output.text
        if (replacement.isEmpty()) return
        WriteCommandAction.runWriteCommandAction(project, "Format JSON", "DevBeer", {
            editor.document.replaceString(start, end, replacement)
        })
    }

    override fun dispose() = Unit
}
