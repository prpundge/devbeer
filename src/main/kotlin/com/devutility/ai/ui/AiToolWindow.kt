package com.devutility.ai.ui

import com.devutility.ai.AiService
import com.devutility.ai.error.ErrorParser
import com.devutility.ai.error.SourceLocationResolver
import com.devutility.ai.fix.FixApplier
import com.devutility.ai.flow.CodeFlowAnalyzer
import com.devutility.ai.model.AiResultType
import com.devutility.ai.model.CodeLocation
import com.devutility.ai.model.ErrorKind
import com.devutility.ai.parse.AiResponseParser
import com.devutility.ai.prompt.PromptBuilder
import com.devutility.ai.settings.DevBeerAiConfigurable
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.JBColor
import com.intellij.ui.JBSplitter
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.content.ContentFactory
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.event.MouseEvent
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel

class AiToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = AiToolWindowPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, null, false)
        content.isCloseable = false
        content.setDisposer(panel)
        toolWindow.contentManager.addContent(content)
    }
}

object AiToolWindow {
    const val ID = "DevBeer AI"

    fun show(project: Project, prefill: String? = null, runNow: Boolean = false) {
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(ID) ?: return
        toolWindow.activate {
            val panel = toolWindow.contentManager.contents
                .firstNotNullOfOrNull { it.component as? AiToolWindowPanel }
            prefill?.let { panel?.prefill(it, runNow) }
        }
    }
}

/**
 * The conversation surface: ask a question, read the answer, click through to real code.
 *
 * Native Swing only — no embedded browser, no web frontend (§20).
 */
class AiToolWindowPanel(private val project: Project) : SimpleToolWindowPanel(true, true), Disposable {

    private val input = JBTextArea(3, 60)
    private val output = JBTextArea()
    private val locationsModel = DefaultListModel<SourceLocationResolver.Resolved>()
    private val locations = JBList(locationsModel)
    private val status = JBLabel(" ")

    private val askButton = JButton("Ask")
    private val analyzeButton = JButton("Analyze as error")
    private val flowButton = JButton("Explain flow")

    private var lastFixes: List<FixApplier.Prepared> = emptyList()
    private val applyFixButton = JButton("Preview fix…")

    init {
        setContent(buildContent())
        refreshStatus()
    }

    fun prefill(text: String, runNow: Boolean) {
        input.text = text
        if (runNow) analyze()
    }

    // ------------------------------------------------------------------ UI

    private fun buildContent(): JComponent {
        val monospace = EditorColorsManager.getInstance().globalScheme.getFont(EditorFontType.PLAIN)
        input.font = monospace
        input.lineWrap = true
        input.wrapStyleWord = true
        output.font = monospace
        output.isEditable = false
        output.lineWrap = true
        output.wrapStyleWord = true

        askButton.addActionListener { ask() }
        analyzeButton.addActionListener { analyze() }
        flowButton.toolTipText = "Trace the call flow of a method, e.g. \"how does createPayment() work?\""
        flowButton.addActionListener { explainFlow() }
        applyFixButton.addActionListener { showFixPreview() }
        applyFixButton.isEnabled = false

        val buttons = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), JBUI.scale(3)))
        buttons.add(askButton)
        buttons.add(analyzeButton)
        buttons.add(flowButton)
        buttons.add(applyFixButton)
        buttons.add(JButton("Settings…").apply {
            addActionListener {
                ShowSettingsUtil.getInstance().showSettingsDialog(project, DevBeerAiConfigurable::class.java)
                refreshStatus()
            }
        })
        buttons.add(JButton("Clear").apply {
            addActionListener {
                input.text = ""
                output.text = ""
                locationsModel.clear()
                applyFixButton.isEnabled = false
            }
        })

        locations.cellRenderer = object : SimpleListCellRenderer<SourceLocationResolver.Resolved>() {
            override fun customize(
                list: JList<out SourceLocationResolver.Resolved>,
                value: SourceLocationResolver.Resolved?,
                index: Int,
                selected: Boolean,
                hasFocus: Boolean,
            ) {
                val location = value?.location ?: return
                // Unverified locations are shown but marked, and double-click does nothing —
                // a link that opens nothing is worse than no link (§13, §16).
                text = if (value.isNavigable) {
                    buildString {
                        append(location.display())
                        location.symbol?.let { append("   ").append(it) }
                        location.description?.let { append("   — ").append(it) }
                    }
                } else {
                    "${location.filePath}:${location.line}   (not found in this project)"
                }
                foreground = if (value.isNavigable) list.foreground else JBColor.GRAY
            }
        }

        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean {
                val selected = locations.selectedValue ?: return false
                if (!selected.isNavigable) return false
                val file = selected.file ?: return false
                OpenFileDescriptor(project, file, (selected.location.line - 1).coerceAtLeast(0), 0).navigate(true)
                return true
            }
        }.installOn(locations)

        val top = JPanel(BorderLayout())
        top.add(JBScrollPane(input), BorderLayout.CENTER)
        top.add(buttons, BorderLayout.SOUTH)
        top.minimumSize = Dimension(JBUI.scale(200), JBUI.scale(90))

        val answer = JPanel(BorderLayout())
        answer.add(JBScrollPane(output), BorderLayout.CENTER)

        val locationPanel = JPanel(BorderLayout())
        locationPanel.add(JBLabel("Locations").also { it.border = JBUI.Borders.empty(2, 6) }, BorderLayout.NORTH)
        locationPanel.add(JBScrollPane(locations), BorderLayout.CENTER)
        locationPanel.minimumSize = Dimension(JBUI.scale(200), JBUI.scale(80))

        val lower = JBSplitter(true, 0.65f)
        lower.firstComponent = answer
        lower.secondComponent = locationPanel

        val split = JBSplitter(true, 0.25f)
        split.firstComponent = top
        split.secondComponent = lower

        val root = JPanel(BorderLayout())
        root.add(split, BorderLayout.CENTER)
        status.border = JBUI.Borders.empty(3, 6)
        root.add(status, BorderLayout.SOUTH)
        return root
    }

    private fun refreshStatus() {
        val service = AiService.getInstance(project)
        status.text = if (service.isConfigured()) {
            "Provider: ${service.providerName()} · model ${service.settings().model}"
        } else {
            "AI is not configured. Open Settings → Tools → DevBeer AI Assistant."
        }
    }

    // ------------------------------------------------------------------ actions

    private fun ask() {
        val question = input.text.trim()
        if (question.isEmpty()) return
        if (!guardConfigured()) return

        val service = AiService.getInstance(project)
        setBusy(true)
        service.runInBackground(
            title = "DevBeer AI: answering",
            build = { indicator ->
                indicator.text = "Searching the project"
                val resolution = service.resolveContext(question)
                PromptBuilder(AiResultType.REPOSITORY_ANSWER)
                    .projectContext(project.name)
                    .relevantCode(resolution.selected)
                    .task(question)
                    .also { builder ->
                        if (resolution.truncated) {
                            builder.note("Context was truncated to stay within the configured budget.")
                        }
                        if (!resolution.javaSupport) {
                            builder.note("Java support is not available in this IDE; context came from text search only.")
                        }
                    }
            },
            type = AiResultType.REPOSITORY_ANSWER,
        ) { outcome ->
            setBusy(false)
            if (!handleFailure(outcome)) return@runInBackground

            val parsed = AiResponseParser.repositoryAnswer(outcome.content!!)
            val answer = parsed.value
            output.text = if (answer != null) {
                buildString {
                    append(answer.summary).append("\n\n").append(answer.detail)
                }
            } else {
                fallbackText(outcome.content)
            }
            output.caretPosition = 0
            showLocations(answer?.locations ?: emptyList())
            noteRedactions(outcome.redactions)
        }
    }

    private fun analyze() {
        val raw = input.text.trim()
        if (raw.isEmpty()) return
        if (!guardConfigured()) return

        val service = AiService.getInstance(project)
        val settings = service.settings()
        setBusy(true)

        service.runInBackground(
            title = "DevBeer AI: analysing error",
            build = { indicator ->
                indicator.text = "Locating the error"
                val parsed = ErrorParser.parse(raw, settings.maxStackTraceLines, com.devutility.ai.model.ErrorKind.INSPECTION)
                val located = SourceLocationResolver.locateError(project, parsed)

                // Search terms come from the exception and the failing symbol, not the whole blob.
                val query = listOfNotNull(
                    parsed.exceptionType?.substringAfterLast('.'),
                    parsed.firstApplicationFrame?.declaringClass?.substringAfterLast('.'),
                    parsed.firstApplicationFrame?.methodName,
                    parsed.message?.take(120),
                ).joinToString(" ")

                indicator.text = "Collecting context"
                // Files named in the trace carry the heaviest ranking signal (§7).
                val frameFiles = parsed.frames.mapNotNull { it.fileName }.toSet()
                val resolution = com.devutility.ai.context.ContextResolver.resolve(
                    project, query, settings.budget(), frameFiles,
                )

                PromptBuilder(AiResultType.ERROR_ANALYSIS)
                    .projectContext(project.name)
                    .error(parsed, settings.maxStackTraceLines)
                    .also { builder ->
                        located?.let { builder.sourceLocation(it.location.filePath, it.location.line) }
                        builder.relevantCode(resolution.selected)
                        if (parsed.kind == ErrorKind.UNKNOWN) {
                            builder.note("The text could not be classified as a known error format.")
                        }
                    }
                    .task("Identify the root cause, the exact location to fix, and a concrete fix.")
            },
            type = AiResultType.ERROR_ANALYSIS,
        ) { outcome ->
            setBusy(false)
            if (!handleFailure(outcome)) return@runInBackground

            val parsed = AiResponseParser.errorAnalysis(outcome.content!!)
            val analysis = parsed.value
            if (analysis == null) {
                output.text = fallbackText(outcome.content)
                output.caretPosition = 0
                return@runInBackground
            }

            output.text = buildString {
                if (analysis.insufficientContext) {
                    append("Insufficient project context to determine the fix confidently.\n\n")
                }
                append("Root cause:\n").append(analysis.rootCause.ifBlank { analysis.summary }).append("\n\n")
                if (analysis.why.isNotBlank()) append("Why:\n").append(analysis.why).append("\n\n")
                append("Confidence: ").append(analysis.confidencePercent).append("%\n")
            }
            output.caretPosition = 0

            showLocations(listOfNotNull(analysis.likelyLocation) + analysis.relatedLocations)
            prepareFixes(analysis.fixes)
            noteRedactions(outcome.redactions)
        }
    }

    /**
     * Feature 5 — code flow (§14).
     *
     * The chain is resolved from PSI first; the model only narrates it. If the project model
     * yields nothing there is no flow to explain, and saying so beats asking a model to invent
     * one.
     */
    private fun explainFlow() {
        val question = input.text.trim()
        if (question.isEmpty()) return
        if (!guardConfigured()) return

        val methodName = CodeFlowAnalyzer.methodNameFrom(question)
        if (methodName == null) {
            output.text = "Name a method to trace, for example:\n\n" +
                "    How does createPayment() work?\n\n" +
                "Use Ask for general questions about the project."
            return
        }

        val service = AiService.getInstance(project)
        var resolvedNodes: List<com.devutility.ai.model.FlowNode> = emptyList()
        setBusy(true)

        service.runInBackground(
            title = "DevBeer AI: tracing $methodName",
            build = { indicator ->
                indicator.text = "Resolving the call flow"
                resolvedNodes = CodeFlowAnalyzer.resolve(project, methodName, service.settings().budget())
                if (resolvedNodes.isEmpty()) null
                else CodeFlowAnalyzer.buildPrompt(project.name, methodName, resolvedNodes)
            },
            type = AiResultType.CODE_FLOW,
        ) { outcome ->
            setBusy(false)

            if (resolvedNodes.isEmpty()) {
                val enricher = com.devutility.ai.context.SymbolEnricher.get()
                output.text = if (!enricher.available) {
                    "Call-flow tracing needs Java support, which this IDE does not have.\n\n" +
                        "Use Ask instead — repository search works in every IDE."
                } else {
                    "No method named '$methodName' was found in this project.\n\n" +
                        "The flow is built from the IDE's call graph, so a method that does not " +
                        "exist has no flow to show."
                }
                locationsModel.clear()
                return@runInBackground
            }

            if (!handleFailure(outcome)) return@runInBackground

            val parsed = AiResponseParser.codeFlow(outcome.content!!).value
            output.text = buildString {
                append(parsed?.summary.orEmpty().ifBlank { "Call flow for $methodName" }).append("\n\n")
                // The resolved chain is printed from PSI, not from the reply, so the structure
                // shown is always the real one even if the narration drifts.
                resolvedNodes.forEachIndexed { index, node ->
                    append(if (index == 0) "" else "    ↓\n")
                    append(node.symbol)
                    node.location?.let { append("   ").append(it.display()) }
                    append('\n')
                }
                parsed?.nodes
                    ?.mapNotNull { it.description?.takeIf { d -> d.isNotBlank() } }
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { append("\n").append(it.joinToString("\n") { d -> "• $d" }) }
            }
            output.caretPosition = 0

            showLocations(resolvedNodes.mapNotNull { it.location })
            noteRedactions(outcome.redactions)
        }
    }

    // ------------------------------------------------------------------ shared

    private fun guardConfigured(): Boolean {
        if (AiService.getInstance(project).isConfigured()) return true
        output.text = "DevBeer AI is not configured.\n\n" +
            "Open Settings → Tools → DevBeer AI Assistant and choose a provider.\n" +
            "No network call is made until you do."
        return false
    }

    private fun handleFailure(outcome: AiService.Outcome): Boolean {
        if (outcome.succeeded) return true
        val failure = outcome.failure ?: return false
        output.text = buildString {
            append(failure.userMessage()).append("\n\n")
            append("Provider: ").append(failure.providerName).append('\n')
            if (failure.detail.isNotBlank()) append("Reason: ").append(failure.detail).append('\n')
            append("\nOpen Settings → Tools → DevBeer AI Assistant to change the configuration.")
        }
        locationsModel.clear()
        applyFixButton.isEnabled = false
        return false
    }

    private fun fallbackText(content: String): String =
        "The AI response could not be interpreted as structured output.\n" +
            "Raw reply follows.\n\n$content"

    private fun showLocations(claimed: List<CodeLocation>) {
        locationsModel.clear()
        if (claimed.isEmpty()) return
        ApplicationManager.getApplication().executeOnPooledThread {
            val verified = AiService.getInstance(project).verify(claimed)
            ApplicationManager.getApplication().invokeLater {
                locationsModel.clear()
                verified.forEach { locationsModel.addElement(it) }
                val navigable = verified.count { it.isNavigable }
                if (navigable < verified.size) {
                    status.text = "$navigable of ${verified.size} locations exist in this project."
                }
            }
        }
    }

    private fun prepareFixes(fixes: List<com.devutility.ai.model.FixSuggestion>) {
        lastFixes = emptyList()
        applyFixButton.isEnabled = false
        if (fixes.isEmpty()) return
        ApplicationManager.getApplication().executeOnPooledThread {
            val prepared = fixes.map { FixApplier.prepare(project, it) }
            ApplicationManager.getApplication().invokeLater {
                lastFixes = prepared
                applyFixButton.isEnabled = prepared.isNotEmpty()
            }
        }
    }

    private fun showFixPreview() {
        val prepared = lastFixes.firstOrNull() ?: return
        val message = prepared.preview()

        if (!prepared.canApply) {
            Messages.showInfoMessage(project, message, "Suggested Fix (preview only)")
            return
        }
        val choice = Messages.showOkCancelDialog(
            project,
            message,
            "Apply Suggested Fix?",
            "Apply",
            "Cancel",
            Messages.getQuestionIcon(),
        )
        if (choice == Messages.OK) {
            val applied = FixApplier.apply(project, prepared)
            status.text = if (applied) {
                "Fix applied. Use Undo to revert."
            } else {
                "The file changed since the suggestion was produced; nothing was applied."
            }
        }
    }

    private fun noteRedactions(count: Int) {
        if (count > 0) {
            status.text = "$count secret-shaped value(s) were redacted before sending."
        } else {
            refreshStatus()
        }
    }

    private fun setBusy(busy: Boolean) {
        askButton.isEnabled = !busy
        analyzeButton.isEnabled = !busy
        flowButton.isEnabled = !busy
        if (busy) status.text = "Working…"
    }

    override fun dispose() = Unit
}
