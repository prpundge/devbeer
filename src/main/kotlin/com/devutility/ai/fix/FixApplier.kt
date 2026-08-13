package com.devutility.ai.fix

import com.devutility.ai.context.SourceSnippets
import com.devutility.ai.error.SourceLocationResolver
import com.devutility.ai.model.FixSuggestion
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/**
 * The only path from a suggested edit to the document.
 *
 * Enforces the safety model end to end: validate against the *current* file, show a preview,
 * then apply inside a write command so the change lands in one undoable step. There is no
 * method here that writes without validating first (§17, §31).
 */
object FixApplier {

    data class Prepared(
        val fix: FixSuggestion,
        val file: VirtualFile?,
        val verdict: FixSuggestionValidator.Verdict,
    ) {
        val canApply: Boolean get() = file != null && verdict is FixSuggestionValidator.Verdict.Valid

        val rejection: String?
            get() = (verdict as? FixSuggestionValidator.Verdict.Invalid)?.reason

        /** Side-by-side preview text, shown whether or not the fix can be applied. */
        fun preview(): String = buildString {
            append("File: ").append(fix.filePath).append('\n')
            append("Lines ").append(fix.startLine)
            if (fix.endLine != fix.startLine) append('–').append(fix.endLine)
            append("\n\n")
            val valid = verdict as? FixSuggestionValidator.Verdict.Valid
            if (valid != null) {
                append("--- current ---\n").append(valid.currentText).append("\n\n")
            }
            append("--- suggested ---\n").append(fix.replacement).append('\n')
            if (fix.explanation.isNotBlank()) append("\n").append(fix.explanation).append('\n')
            rejection?.let { append("\nCannot apply: ").append(it).append('\n') }
        }
    }

    /** Resolves and validates. Must be called off the EDT. */
    fun prepare(project: Project, fix: FixSuggestion): Prepared {
        val file = SourceLocationResolver.findFile(project, fix.filePath)
        val lines = if (file == null) {
            null
        } else {
            ReadAction.compute<List<String>?, RuntimeException> {
                SourceSnippets.documentOf(file)?.text?.split("\n")
            }
        }
        return Prepared(fix, file, FixSuggestionValidator.validate(fix, file != null, lines))
    }

    /**
     * Applies a prepared fix. Returns false and changes nothing unless [Prepared.canApply].
     *
     * Re-validates immediately before writing: the file may have changed between the preview
     * and the click.
     */
    fun apply(project: Project, prepared: Prepared): Boolean {
        if (!prepared.canApply) return false
        val file = prepared.file ?: return false

        val fresh = prepare(project, prepared.fix)
        val verdict = fresh.verdict as? FixSuggestionValidator.Verdict.Valid ?: return false

        val document = ReadAction.compute<com.intellij.openapi.editor.Document?, RuntimeException> {
            FileDocumentManager.getInstance().getDocument(file)
        } ?: return false
        if (!document.isWritable) return false

        WriteCommandAction.runWriteCommandAction(
            project,
            "Apply DevBeer AI Fix",
            "DevBeer",
            {
                document.replaceString(verdict.startOffset, verdict.endOffset, prepared.fix.replacement)
            },
        )
        return true
    }
}
