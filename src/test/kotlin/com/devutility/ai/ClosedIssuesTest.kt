package com.devutility.ai

import com.devutility.ai.context.ContextCandidate
import com.devutility.ai.context.ContextRanker
import com.devutility.ai.context.MatchKind
import com.devutility.ai.context.Signal
import com.devutility.ai.error.ErrorParser
import com.devutility.ai.model.ErrorKind
import com.devutility.ai.settings.ContextBudget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression cover for the gaps found by the reachability audit: values that were declared in
 * the model but never produced by any code path, so the behaviour they described did not
 * actually exist.
 */
class ClosedIssuesTest {

    // ---------------------------------------------------------------- #3 stack-frame signal

    @Test
    fun `being named in the stack trace is the largest single boost`() {
        val plain = ContextCandidate("/p/A.java", "A.java", kind = MatchKind.TEXT, text = "x")
        val inTrace = plain.copy(signals = setOf(Signal.STACK_FRAME))

        assertEquals(40, ContextRanker.score(plain))
        assertEquals(90, ContextRanker.score(inTrace))
        // Larger than any other signal, because a trace is evidence of where execution was,
        // not an inference from the wording of the question.
        assertTrue(Signal.STACK_FRAME.score > Signal.SAME_PACKAGE.score)
        assertTrue(Signal.STACK_FRAME.score > Signal.SPRING_COMPONENT.score)
    }

    @Test
    fun `a traced file beats an equally matched untraced one`() {
        val untraced = ContextCandidate("/p/Other.java", "Other.java", kind = MatchKind.CLASS_NAME, text = "x")
        val traced = ContextCandidate(
            "/p/Culprit.java", "Culprit.java",
            kind = MatchKind.CLASS_NAME, signals = setOf(Signal.STACK_FRAME), text = "x",
        )

        val ranked = ContextRanker.rank(listOf(untraced, traced))
        assertEquals("Culprit.java", ranked.first().candidate.fileName)
    }

    @Test
    fun `an unrelated exact symbol still edges out a text-only trace hit`() {
        // 100 vs 90, and deliberately so: a bare text hit in a traced file is weaker evidence
        // than an exact symbol match. In practice the traced file also matches by name and
        // scores 150, so this only decides genuinely ambiguous cases.
        val textInTrace = ContextCandidate(
            "/p/Traced.java", "Traced.java",
            kind = MatchKind.TEXT, signals = setOf(Signal.STACK_FRAME), text = "x",
        )
        val exactElsewhere = ContextCandidate(
            "/p/Exact.java", "Exact.java", kind = MatchKind.EXACT_SYMBOL, text = "x",
        )

        assertEquals(90, ContextRanker.score(textInTrace))
        assertEquals(100, ContextRanker.score(exactElsewhere))
        assertEquals(
            150,
            ContextRanker.score(textInTrace.copy(kind = MatchKind.EXACT_SYMBOL)),
        )
    }

    @Test
    fun `stack frame files survive a tight budget`() {
        val noise = (1..30).map { ContextCandidate("/p/N$it.java", "N$it.java", text = "y".repeat(400)) }
        val traced = ContextCandidate(
            "/p/Culprit.java", "Culprit.java",
            kind = MatchKind.TEXT, signals = setOf(Signal.STACK_FRAME), text = "y".repeat(400),
        )

        val selected = ContextRanker.select(noise + traced, ContextBudget(maxFiles = 2))
            .selected.map { it.candidate.fileName }

        assertTrue("the traced file must not be dropped, got $selected", selected.contains("Culprit.java"))
    }

    // ---------------------------------------------------------------- #4 reference tier

    @Test
    fun `a reference scores above a plain text hit and below a class name`() {
        val reference = ContextCandidate("/p/A.java", "A.java", kind = MatchKind.REFERENCE, text = "")
        val text = ContextCandidate("/p/B.java", "B.java", kind = MatchKind.TEXT, text = "")
        val className = ContextCandidate("/p/C.java", "C.java", kind = MatchKind.CLASS_NAME, text = "")

        assertEquals(60, ContextRanker.score(reference))
        assertTrue(ContextRanker.score(reference) > ContextRanker.score(text))
        assertTrue(ContextRanker.score(reference) < ContextRanker.score(className))
    }

    // ---------------------------------------------------------------- #5 inspection kind

    @Test
    fun `text from the editor that is not a trace or diagnostic is classified as an inspection`() {
        val parsed = ErrorParser.parse(
            "Condition 'payment != null' is always true",
            fallbackKind = ErrorKind.INSPECTION,
        )
        assertEquals(ErrorKind.INSPECTION, parsed.kind)
    }

    @Test
    fun `the fallback never overrides a real classification`() {
        val trace = """
            java.lang.NullPointerException: boom
            	at com.example.Foo.bar(Foo.java:12)
        """.trimIndent()
        assertEquals(
            ErrorKind.JAVA_EXCEPTION,
            ErrorParser.parse(trace, fallbackKind = ErrorKind.INSPECTION).kind,
        )
        assertEquals(
            ErrorKind.COMPILATION,
            ErrorParser.parse("/a/Foo.java:42: error: cannot find symbol", fallbackKind = ErrorKind.INSPECTION).kind,
        )
    }

    @Test
    fun `without a fallback the kind is still unknown`() {
        assertEquals(ErrorKind.UNKNOWN, ErrorParser.parse("something odd happened").kind)
    }
}
