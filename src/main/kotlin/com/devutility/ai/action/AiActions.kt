package com.devutility.ai.action

import com.devutility.ai.AiService
import com.devutility.ai.fix.FixApplier
import com.devutility.ai.model.FixSuggestion
import com.intellij.openapi.application.ApplicationManager
import com.devutility.ai.model.AiResultType
import com.devutility.ai.parse.AiResponseParser
import com.devutility.ai.prompt.PromptBuilder
import com.devutility.ai.ui.AiToolWindow
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages

/** Tools → DevBeer → AI Assistant. */
class AskDevBeerAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        AiToolWindow.show(e.project ?: return)
    }
}

/**
 * Shared plumbing for the editor actions: they all take a selection, build a small prompt
 * around it and render the reply in a dialog rather than modifying anything.
 */
abstract class SelectionAiAction internal constructor(private val title: String) : AnAction(), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        e.presentation.isEnabledAndVisible = e.project != null && editor?.selectionModel?.hasSelection() == true
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val selection = editor.selectionModel.selectedText ?: return

        val service = AiService.getInstance(project)
        if (!service.isConfigured()) {
            Messages.showInfoMessage(
                project,
                "DevBeer AI is not configured.\n\n" +
                    "Open Settings → Tools → DevBeer AI Assistant and choose a provider.\n" +
                    "No network call is made until you do.",
                "DevBeer AI",
            )
            return
        }

        val language = e.getData(CommonDataKeys.PSI_FILE)?.language?.id?.lowercase()
        val location = describeLocation(editor, project)
        val target = editTarget(editor, selection) ?: return

        service.runInBackground(
            title = title,
            build = { indicator ->
                indicator.text = "Collecting context"
                buildPrompt(project, service, selection, language, location)
            },
            type = resultType,
        ) { outcome ->
            if (!outcome.succeeded) {
                Messages.showWarningDialog(project, outcome.failure?.userMessage() ?: "Request failed.", title)
                return@runInBackground
            }
            render(project, outcome.content!!, outcome.redactions, target)
        }
    }

    /** Converts the current selection into 1-based line / 0-based column bounds. */
    private fun editTarget(editor: com.intellij.openapi.editor.Editor, selection: String): EditTarget? {
        val file = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance()
            .getFile(editor.document) ?: return null
        val document = editor.document
        val start = editor.selectionModel.selectionStart
        val end = editor.selectionModel.selectionEnd
        val startLine = document.getLineNumber(start)
        val endLine = document.getLineNumber(end)
        return EditTarget(
            filePath = file.path,
            startLine = startLine + 1,
            startColumn = start - document.getLineStartOffset(startLine),
            endLine = endLine + 1,
            endColumn = end - document.getLineStartOffset(endLine),
            originalText = selection,
        )
    }

    protected abstract val resultType: AiResultType

    protected abstract fun buildPrompt(
        project: Project,
        service: AiService,
        selection: String,
        language: String?,
        location: String?,
    ): PromptBuilder

    protected abstract fun render(project: Project, content: String, redactions: Int, target: EditTarget)

    /** Where the selection lives, so a suggestion can be turned into a validated edit. */
    data class EditTarget(val filePath: String, val startLine: Int, val startColumn: Int, val endLine: Int, val endColumn: Int, val originalText: String)

    private fun describeLocation(editor: Editor, project: Project): String? {
        val file = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance()
            .getFile(editor.document) ?: return null
        val line = editor.document.getLineNumber(editor.selectionModel.selectionStart) + 1
        return "${file.path}:$line"
    }

    protected fun footer(redactions: Int): String =
        if (redactions > 0) "\n\n($redactions secret-shaped value(s) were redacted before sending.)" else ""
}

/** Editor → DevBeer → Explain Selection. */
class ExplainSelectionAction : SelectionAiAction("DevBeer AI: explaining selection") {

    override val resultType = AiResultType.CODE_EXPLANATION

    override fun buildPrompt(
        project: Project,
        service: AiService,
        selection: String,
        language: String?,
        location: String?,
    ): PromptBuilder = PromptBuilder(AiResultType.CODE_EXPLANATION)
        .projectContext(project.name, language)
        .also { builder -> location?.let { builder.sourceLocation(it.substringBeforeLast(':'), 0) } }
        .selectedCode(selection, language)
        // Only a little surrounding context: the selection is the subject, not the file (§11).
        .relevantCode(service.resolveContext(selection.take(200)).selected.take(3))
        .task("Explain this code: what it does, how it works, what it depends on, and any risks.")

    override fun render(project: Project, content: String, redactions: Int, target: EditTarget) {
        val parsed = AiResponseParser.explanation(content).value
        val text = if (parsed == null) {
            content
        } else {
            buildString {
                append(parsed.summary).append("\n\n")
                append("How it works\n").append(parsed.howItWorks).append('\n')
                if (parsed.dependencies.isNotEmpty()) {
                    append("\nDependencies\n").append(parsed.dependencies.joinToString("\n") { "• $it" }).append('\n')
                }
                if (parsed.risks.isNotEmpty()) {
                    append("\nRisks\n").append(parsed.risks.joinToString("\n") { "• $it" }).append('\n')
                }
            }
        }
        Messages.showInfoMessage(project, text + footer(redactions), "Explain Selection")
    }
}

/**
 * Editor → DevBeer → Suggest Improvement.
 *
 * Nothing is modified without an explicit Apply: the suggestion goes through the same
 * validator as an error fix, so a stale or mismatched edit is refused (§12, §30, §31).
 */
class SuggestImprovementAction : SelectionAiAction("DevBeer AI: reviewing selection") {

    override val resultType = AiResultType.CODE_SUGGESTION

    override fun buildPrompt(
        project: Project,
        service: AiService,
        selection: String,
        language: String?,
        location: String?,
    ): PromptBuilder = PromptBuilder(AiResultType.CODE_SUGGESTION)
        .projectContext(project.name, language)
        .selectedCode(selection, language)
        .relevantCode(service.resolveContext(selection.take(200)).selected.take(3))
        .task("Suggest one focused improvement to the selected code.")

    override fun render(project: Project, content: String, redactions: Int, target: EditTarget) {
        val parsed = AiResponseParser.suggestion(content).value
        if (parsed == null) {
            Messages.showInfoMessage(project, content + footer(redactions), "Suggest Improvement")
            return
        }

        val summary = buildString {
            append("Problem\n").append(parsed.problem).append("\n\n")
            append("Recommendation\n").append(parsed.recommendation).append("\n\n")
            append("Reason\n").append(parsed.reason)
        }

        val code = parsed.suggestedCode
        if (code.isNullOrBlank()) {
            Messages.showInfoMessage(project, summary + footer(redactions), "Suggest Improvement")
            return
        }

        // §12 requires Preview → Apply → Cancel. The suggestion is turned into a FixSuggestion
        // bound to the exact selection, then put through the same validator as an error fix —
        // so a stale or mismatched edit is refused here too rather than trusted.
        val fix = FixSuggestion(
            filePath = target.filePath,
            startLine = target.startLine,
            startColumn = target.startColumn,
            endLine = target.endLine,
            endColumn = target.endColumn,
            replacement = code,
            explanation = parsed.recommendation,
            originalText = target.originalText,
        )

        ApplicationManager.getApplication().executeOnPooledThread {
            val prepared = FixApplier.prepare(project, fix)
            ApplicationManager.getApplication().invokeLater {
                val body = summary + "\n\n" + prepared.preview() + footer(redactions)
                if (!prepared.canApply) {
                    Messages.showInfoMessage(project, body, "Suggest Improvement (preview only)")
                    return@invokeLater
                }
                val choice = Messages.showOkCancelDialog(
                    project, body, "Apply Suggested Improvement?", "Apply", "Cancel",
                    Messages.getQuestionIcon(),
                )
                if (choice == Messages.OK && !FixApplier.apply(project, prepared)) {
                    Messages.showWarningDialog(
                        project,
                        "The file changed since the suggestion was produced; nothing was applied.",
                        "Suggest Improvement",
                    )
                }
            }
        }
    }
}

/**
 * Editor → DevBeer → Analyze Error.
 *
 * Sends the selection to the tool window and runs the analysis there, because an error
 * analysis produces navigable locations and a fix preview that a message box cannot host.
 */
class AnalyzeErrorAction : AnAction(), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        e.presentation.isEnabledAndVisible = e.project != null && editor?.selectionModel?.hasSelection() == true
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val text = e.getData(CommonDataKeys.EDITOR)?.selectionModel?.selectedText ?: return
        AiToolWindow.show(project, text, runNow = true)
    }
}

/** Editor → DevBeer → Ask DevBeer about this. */
class AskAboutSelectionAction : AnAction(), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        e.presentation.isEnabledAndVisible = e.project != null && editor?.selectionModel?.hasSelection() == true
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val text = e.getData(CommonDataKeys.EDITOR)?.selectionModel?.selectedText ?: return
        AiToolWindow.show(project, text, runNow = false)
    }
}
