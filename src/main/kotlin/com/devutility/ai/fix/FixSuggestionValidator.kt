package com.devutility.ai.fix

import com.devutility.ai.model.FixSuggestion

/**
 * Gatekeeper between a suggested edit and the document.
 *
 * A model will confidently produce a patch for a file that does not exist, at a line past the
 * end of it, against text that has since changed. Applying any of those corrupts the file, so
 * a fix is refused unless every check passes and the user has seen a preview (§17, §31).
 *
 * Deliberately free of IntelliJ types: it takes the document as a list of lines, so the whole
 * safety model is unit-testable without a fixture.
 */
object FixSuggestionValidator {

    sealed class Verdict {
        /** Safe to offer *Apply*. */
        data class Valid(val startOffset: Int, val endOffset: Int, val currentText: String) : Verdict()

        /** Show the explanation, but preview only — never apply. */
        data class Invalid(val reason: String) : Verdict()
    }

    /**
     * @param fileExists whether the resolver found the file in the project
     * @param lines the current content of that file, one entry per line
     */
    fun validate(fix: FixSuggestion, fileExists: Boolean, lines: List<String>?): Verdict {
        if (!fileExists || lines == null) {
            return Verdict.Invalid("The file '${fix.filePath}' does not exist in this project.")
        }
        if (fix.startLine < 1) {
            return Verdict.Invalid("Start line ${fix.startLine} is not a valid line number.")
        }
        if (fix.endLine < fix.startLine) {
            return Verdict.Invalid("End line ${fix.endLine} is before start line ${fix.startLine}.")
        }
        if (fix.startLine > lines.size) {
            return Verdict.Invalid("Start line ${fix.startLine} is past the end of the file (${lines.size} lines).")
        }
        if (fix.endLine > lines.size) {
            return Verdict.Invalid("End line ${fix.endLine} is past the end of the file (${lines.size} lines).")
        }

        val startLineText = lines[fix.startLine - 1]
        val endLineText = lines[fix.endLine - 1]
        if (fix.startColumn < 0 || fix.startColumn > startLineText.length) {
            return Verdict.Invalid("Start column ${fix.startColumn} is outside line ${fix.startLine}.")
        }
        val endColumn = if (fix.endColumn <= 0) endLineText.length else fix.endColumn
        if (endColumn > endLineText.length) {
            return Verdict.Invalid("End column $endColumn is outside line ${fix.endLine}.")
        }

        val startOffset = offsetOf(lines, fix.startLine, fix.startColumn)
        val endOffset = offsetOf(lines, fix.endLine, endColumn)
        if (endOffset < startOffset) {
            return Verdict.Invalid("The replacement range is inverted.")
        }

        val currentText = joined(lines).substring(startOffset, endOffset)

        // The decisive check: if the document no longer reads as the model believed, the
        // patch was computed against different content and must not be applied.
        val expected = fix.originalText
        if (expected != null && normalise(expected) != normalise(currentText)) {
            return Verdict.Invalid(
                "The file has changed since the suggestion was produced, " +
                    "so the replacement no longer matches what is there.",
            )
        }

        if (fix.replacement == currentText) {
            return Verdict.Invalid("The suggested replacement is identical to the current text.")
        }

        return Verdict.Valid(startOffset, endOffset, currentText)
    }

    /** Character offset of (1-based line, 0-based column) in a `\n`-joined document. */
    fun offsetOf(lines: List<String>, line: Int, column: Int): Int {
        var offset = 0
        for (i in 0 until (line - 1).coerceAtMost(lines.size)) {
            offset += lines[i].length + 1
        }
        return offset + column
    }

    private fun joined(lines: List<String>): String = lines.joinToString("\n")

    /** Whitespace-insensitive comparison: indentation drift must not block a correct fix. */
    private fun normalise(text: String): String = text.trim().replace(Regex("\\s+"), " ")
}
