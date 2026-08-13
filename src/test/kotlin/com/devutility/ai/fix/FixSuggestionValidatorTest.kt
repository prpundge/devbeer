package com.devutility.ai.fix

import com.devutility.ai.model.FixSuggestion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FixSuggestionValidatorTest {

    private val lines = listOf(
        "package com.example;",          // 1
        "",                              // 2
        "public class PaymentService {", // 3
        "    void create() {}",          // 4
        "}",                             // 5
    )

    private fun fix(
        startLine: Int = 3,
        startColumn: Int = 0,
        endLine: Int = 3,
        endColumn: Int = 0,
        replacement: String = "@Service\npublic class PaymentService {",
        originalText: String? = null,
        path: String = "PaymentService.java",
    ) = FixSuggestion(path, startLine, startColumn, endLine, endColumn, replacement, "test", originalText)

    private fun assertInvalid(verdict: FixSuggestionValidator.Verdict, expect: String) {
        assertTrue(
            "expected an Invalid verdict mentioning '$expect', got: $verdict",
            verdict is FixSuggestionValidator.Verdict.Invalid && verdict.reason.contains(expect, ignoreCase = true),
        )
    }

    // ------------------------------------------------------------------ rejections

    @Test
    fun `rejects a fix for a file that does not exist`() {
        assertInvalid(
            FixSuggestionValidator.validate(fix(), fileExists = false, lines = null),
            "does not exist",
        )
    }

    @Test
    fun `rejects a start line past the end of the file`() {
        assertInvalid(
            FixSuggestionValidator.validate(fix(startLine = 99, endLine = 99), true, lines),
            "past the end",
        )
    }

    @Test
    fun `rejects an end line past the end of the file`() {
        assertInvalid(
            FixSuggestionValidator.validate(fix(startLine = 3, endLine = 99), true, lines),
            "past the end",
        )
    }

    @Test
    fun `rejects a non positive line number`() {
        assertInvalid(FixSuggestionValidator.validate(fix(startLine = 0, endLine = 0), true, lines), "not a valid line")
    }

    @Test
    fun `rejects an inverted line range`() {
        assertInvalid(FixSuggestionValidator.validate(fix(startLine = 4, endLine = 2), true, lines), "before start")
    }

    @Test
    fun `rejects a column outside the line`() {
        assertInvalid(
            FixSuggestionValidator.validate(fix(startLine = 2, startColumn = 50, endLine = 2), true, lines),
            "outside line",
        )
    }

    @Test
    fun `rejects a patch whose original text no longer matches`() {
        // The decisive safety check: the model patched against content that has since changed.
        assertInvalid(
            FixSuggestionValidator.validate(
                fix(startLine = 3, endColumn = 29, originalText = "public class SomethingElse {"),
                true,
                lines,
            ),
            "has changed",
        )
    }

    @Test
    fun `rejects a no-op replacement`() {
        assertInvalid(
            FixSuggestionValidator.validate(
                fix(startLine = 1, startColumn = 0, endLine = 1, endColumn = 20, replacement = "package com.example;"),
                true,
                lines,
            ),
            "identical",
        )
    }

    // ------------------------------------------------------------------ acceptance

    @Test
    fun `accepts a well formed fix and reports the exact range`() {
        val verdict = FixSuggestionValidator.validate(
            fix(startLine = 3, startColumn = 0, endLine = 3, endColumn = 29),
            true,
            lines,
        )
        assertTrue(verdict.toString(), verdict is FixSuggestionValidator.Verdict.Valid)

        val valid = verdict as FixSuggestionValidator.Verdict.Valid
        assertEquals("public class PaymentService {", valid.currentText)
    }

    @Test
    fun `accepts when the original text matches`() {
        val verdict = FixSuggestionValidator.validate(
            fix(startLine = 3, endColumn = 29, originalText = "public class PaymentService {"),
            true,
            lines,
        )
        assertTrue(verdict.toString(), verdict is FixSuggestionValidator.Verdict.Valid)
    }

    @Test
    fun `tolerates whitespace differences in the original text`() {
        // Indentation drift must not block a correct fix.
        val verdict = FixSuggestionValidator.validate(
            fix(startLine = 3, endColumn = 29, originalText = "  public   class PaymentService {  "),
            true,
            lines,
        )
        assertTrue(verdict.toString(), verdict is FixSuggestionValidator.Verdict.Valid)
    }

    @Test
    fun `an end column of zero means the end of the line`() {
        val verdict = FixSuggestionValidator.validate(fix(startLine = 4, endLine = 4, endColumn = 0), true, lines)
        val valid = verdict as FixSuggestionValidator.Verdict.Valid
        assertEquals("    void create() {}", valid.currentText)
    }

    @Test
    fun `spans multiple lines correctly`() {
        val verdict = FixSuggestionValidator.validate(
            fix(startLine = 3, startColumn = 0, endLine = 5, endColumn = 1, replacement = "class X {}"),
            true,
            lines,
        )
        val valid = verdict as FixSuggestionValidator.Verdict.Valid
        assertEquals("public class PaymentService {\n    void create() {}\n}", valid.currentText)
    }

    @Test
    fun `offset calculation accounts for line separators`() {
        assertEquals(0, FixSuggestionValidator.offsetOf(lines, 1, 0))
        assertEquals("package com.example;".length + 1, FixSuggestionValidator.offsetOf(lines, 2, 0))
        assertEquals("package com.example;".length + 1 + 0 + 1, FixSuggestionValidator.offsetOf(lines, 3, 0))
    }

    @Test
    fun `handles an empty file`() {
        assertInvalid(FixSuggestionValidator.validate(fix(), true, emptyList()), "past the end")
    }
}
