package com.devutility.ai

import com.devutility.ai.context.ContextCandidate
import com.devutility.ai.context.ContextRanker
import com.devutility.ai.context.MatchKind
import com.devutility.ai.error.ErrorParser
import com.devutility.ai.fix.FixSuggestionValidator
import com.devutility.ai.model.AiResultType
import com.devutility.ai.model.ErrorKind
import com.devutility.ai.model.LlmFailure
import com.devutility.ai.model.LlmRequest
import com.devutility.ai.model.LlmResult
import com.devutility.ai.parse.AiResponseParser
import com.devutility.ai.prompt.PromptBuilder
import com.devutility.ai.settings.ContextBudget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end tests over the whole chain that does *not* need the IDE:
 *
 *     input → context → prompt → FakeLlmProvider → parse → validate
 *
 * The VFS-dependent links (ContextResolver, SourceLocationResolver) are exercised separately
 * in a sandbox IDE; everything either side of them is covered here (§28).
 */
class AiPipelineIntegrationTest {

    // ------------------------------------------------------------------ ask → answer

    @Test
    fun `question to answer with locations`() {
        val context = ContextRanker.select(
            listOf(
                ContextCandidate(
                    "/p/src/PaymentValidator.java", "PaymentValidator.java",
                    symbol = "PaymentValidator", kind = MatchKind.EXACT_SYMBOL,
                    line = 25, text = "25: public void validate(Payment p) {",
                ),
                ContextCandidate(
                    "/p/src/PaymentService.java", "PaymentService.java",
                    kind = MatchKind.REFERENCE, line = 81, text = "81: validator.validate(payment);",
                ),
            ),
            ContextBudget(),
        )

        val messages = PromptBuilder(AiResultType.REPOSITORY_ANSWER)
            .projectContext("demo")
            .relevantCode(context.selected)
            .task("Where is payment validation handled?")
            .build()

        val provider = FakeLlmProvider.replying(
            """{"summary":"In PaymentValidator","detail":"Called from PaymentService.",
                "locations":[{"filePath":"/p/src/PaymentValidator.java","line":25},
                             {"filePath":"/p/src/PaymentService.java","line":81}]}""",
        )
        val result = provider.complete(LlmRequest(messages, "m"))
        val content = (result as LlmResult.Success).response.content
        val answer = AiResponseParser.repositoryAnswer(content).value!!

        assertEquals("In PaymentValidator", answer.summary)
        assertEquals(2, answer.locations.size)
        // Nothing is navigable until the resolver has confirmed it exists.
        assertTrue(answer.locations.none { it.verified })
    }

    @Test
    fun `the prompt carries only the selected context`() {
        val many = (1..100).map {
            ContextCandidate("/p/F$it.java", "F$it.java", text = "class F$it {}\n".repeat(20))
        }
        val selection = ContextRanker.select(many, ContextBudget(maxFiles = 4, maxCharacters = 5_000))

        val provider = FakeLlmProvider.replying("{}")
        provider.complete(
            LlmRequest(
                PromptBuilder(AiResultType.REPOSITORY_ANSWER).relevantCode(selection.selected).task("q").build(),
                "m",
            ),
        )

        val sent = provider.transmittedText()
        assertTrue("F1 should be present", sent.contains("F1.java"))
        assertFalse("F99 is outside the budget", sent.contains("F99.java"))
        assertTrue("payload should stay small, was ${sent.length}", sent.length < 8_000)
    }

    // ------------------------------------------------------------------ error → fix

    @Test
    fun `spring startup error through to a validated fix`() {
        val raw = """
            ***************************
            APPLICATION FAILED TO START
            ***************************

            Parameter 0 of constructor in com.example.PaymentController required a bean of type
            'com.example.PaymentService' that could not be found.
        """.trimIndent()

        val parsed = ErrorParser.parse(raw)
        assertEquals(ErrorKind.SPRING_STARTUP, parsed.kind)

        val messages = PromptBuilder(AiResultType.ERROR_ANALYSIS)
            .projectContext("demo")
            .error(parsed, 100)
            .task("Find the root cause and a fix.")
            .build()

        val provider = FakeLlmProvider.replying(
            """{"summary":"Missing bean","rootCause":"PaymentServiceImpl is not registered as a Spring bean",
                "why":"It implements PaymentService but carries no stereotype annotation",
                "confidence":0.94,
                "likelyLocation":{"filePath":"PaymentServiceImpl.java","line":3},
                "fixes":[{"filePath":"PaymentServiceImpl.java","startLine":3,"startColumn":0,
                          "endLine":3,"endColumn":42,
                          "originalText":"public class PaymentServiceImpl implements",
                          "replacement":"@Service\npublic class PaymentServiceImpl implements",
                          "explanation":"Register the bean"}]}""",
        )

        val content = (provider.complete(LlmRequest(messages, "m")) as LlmResult.Success).response.content
        val analysis = AiResponseParser.errorAnalysis(content).value!!

        assertEquals(94, analysis.confidencePercent)
        assertEquals(1, analysis.fixes.size)

        // The fix is then validated against the real file before it may be applied.
        val fileLines = listOf(
            "package com.example;",
            "",
            "public class PaymentServiceImpl implements PaymentService {",
            "}",
        )
        val verdict = FixSuggestionValidator.validate(analysis.fixes[0], fileExists = true, lines = fileLines)
        assertTrue(verdict.toString(), verdict is FixSuggestionValidator.Verdict.Valid)
    }

    @Test
    fun `a fix against a stale file is refused, not applied`() {
        val content = """{"fixes":[{"filePath":"A.java","startLine":1,"startColumn":0,"endLine":1,"endColumn":11,
                          "originalText":"class Old {}","replacement":"class New {}","explanation":"x"}]}"""
        val fix = AiResponseParser.errorAnalysis(content).value!!.fixes[0]

        // The file now reads differently from what the model patched against.
        val verdict = FixSuggestionValidator.validate(fix, true, listOf("class Other {}"))
        assertTrue(verdict is FixSuggestionValidator.Verdict.Invalid)
        assertTrue(
            (verdict as FixSuggestionValidator.Verdict.Invalid).reason,
            verdict.reason.contains("changed"),
        )
    }

    @Test
    fun `a fix for a file outside the project is refused`() {
        val content = """{"fixes":[{"filePath":"Imaginary.java","startLine":1,"replacement":"x"}]}"""
        val fix = AiResponseParser.errorAnalysis(content).value!!.fixes[0]
        val verdict = FixSuggestionValidator.validate(fix, fileExists = false, lines = null)
        assertTrue(verdict is FixSuggestionValidator.Verdict.Invalid)
    }

    // ------------------------------------------------------------------ degraded paths

    @Test
    fun `a malformed reply degrades to raw text rather than failing`() {
        val provider = FakeLlmProvider.replying("I am not going to answer in JSON today.")
        val content = (provider.complete(LlmRequest(emptyList(), "m")) as LlmResult.Success).response.content
        val outcome = AiResponseParser.errorAnalysis(content)

        assertNull(outcome.value)
        assertFalse(outcome.structured)
        assertEquals("I am not going to answer in JSON today.", outcome.rawText)
    }

    @Test
    fun `an unconfigured provider never transmits`() {
        val provider = FakeLlmProvider.failing(LlmFailure.Kind.NOT_CONFIGURED)
        val result = provider.complete(LlmRequest(emptyList(), ""))
        assertEquals(LlmFailure.Kind.NOT_CONFIGURED, (result as LlmResult.Failure).failure.kind)
    }

    @Test
    fun `insufficient context is reported instead of a confident guess`() {
        val provider = FakeLlmProvider.replying(
            """{"summary":"Not enough information","insufficientContext":true,"confidence":0.1}""",
        )
        val content = (provider.complete(LlmRequest(emptyList(), "m")) as LlmResult.Success).response.content
        val analysis = AiResponseParser.errorAnalysis(content).value!!

        assertTrue(analysis.insufficientContext)
        assertTrue(analysis.fixes.isEmpty())
        assertEquals(10, analysis.confidencePercent)
    }

    @Test
    fun `an empty project produces a prompt with no code section`() {
        val selection = ContextRanker.select(emptyList(), ContextBudget())
        val text = PromptBuilder(AiResultType.REPOSITORY_ANSWER)
            .relevantCode(selection.selected)
            .task("anything")
            .userMessage()

        assertFalse(text, text.contains("RELEVANT CODE"))
        assertTrue(text, text.contains("TASK"))
    }

    @Test
    fun `secrets never reach the provider on the error path either`() {
        val raw = """
            java.lang.RuntimeException: failed to connect
            	at com.example.Db.connect(Db.java:12)
            url=jdbc:postgresql://admin:s3cr3tpassword@db:5432/app
        """.trimIndent()

        val provider = FakeLlmProvider.replying("{}")
        provider.complete(
            LlmRequest(
                PromptBuilder(AiResultType.ERROR_ANALYSIS).error(ErrorParser.parse(raw), 100).build(),
                "m",
            ),
        )

        assertFalse(provider.transmittedText(), provider.transmittedText().contains("s3cr3tpassword"))
    }

    @Test
    fun `every result type has a working parser`() {
        assertNotNull(AiResponseParser.repositoryAnswer("""{"summary":"a"}""").value)
        assertNotNull(AiResponseParser.explanation("""{"summary":"a"}""").value)
        assertNotNull(AiResponseParser.suggestion("""{"problem":"a"}""").value)
        assertNotNull(AiResponseParser.errorAnalysis("""{"summary":"a"}""").value)
        assertNotNull(AiResponseParser.codeFlow("""{"summary":"a","nodes":[]}""").value)
    }
}
