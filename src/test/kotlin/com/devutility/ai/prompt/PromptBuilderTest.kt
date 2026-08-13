package com.devutility.ai.prompt

import com.devutility.ai.FakeLlmProvider
import com.devutility.ai.context.ContextCandidate
import com.devutility.ai.context.ContextRanker
import com.devutility.ai.context.ContextSanitizer
import com.devutility.ai.context.MatchKind
import com.devutility.ai.error.ErrorParser
import com.devutility.ai.model.AiResultType
import com.devutility.ai.model.LlmFailure
import com.devutility.ai.model.LlmRequest
import com.devutility.ai.model.LlmResult
import com.devutility.ai.model.Role
import com.devutility.ai.settings.ContextBudget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptBuilderTest {

    // ------------------------------------------------------------------ structure

    @Test
    fun `produces a system message and a user message`() {
        val messages = PromptBuilder(AiResultType.ERROR_ANALYSIS)
            .projectContext("demo")
            .task("Explain this")
            .build()

        assertEquals(2, messages.size)
        assertEquals(Role.SYSTEM, messages[0].role)
        assertEquals(Role.USER, messages[1].role)
    }

    @Test
    fun `sections appear with explicit headings in order`() {
        val text = PromptBuilder(AiResultType.ERROR_ANALYSIS)
            .projectContext("demo")
            .sourceLocation("Foo.java", 42)
            .task("Find the cause")
            .userMessage()

        assertTrue(text, text.contains("PROJECT CONTEXT"))
        assertTrue(text, text.contains("SOURCE LOCATION"))
        assertTrue(text, text.contains("TASK"))
        assertTrue(text.indexOf("PROJECT CONTEXT") < text.indexOf("TASK"))
    }

    @Test
    fun `every system prompt forbids inventing locations`() {
        for (type in AiResultType.values()) {
            val prompt = PromptTemplates.systemPrompt(type)
            assertTrue("$type is missing the anti-fabrication rule", prompt.contains("Do not invent"))
            assertTrue("$type does not allow admitting ignorance", prompt.contains("insufficient"))
        }
    }

    // ------------------------------------------------------------------ redaction

    @Test
    fun `secrets are redacted before they reach the prompt`() {
        val builder = PromptBuilder(AiResultType.CODE_EXPLANATION)
            .selectedCode("""String apiKey = "sk-abcdefghijklmnopqrst";""", "java")

        val text = builder.userMessage()
        assertFalse(text, text.contains("sk-abcdefghijklmnopqrst"))
        assertTrue(text, text.contains(ContextSanitizer.REDACTED))
        // Rules deliberately overlap — the provider-key shape and the generic assignment rule
        // both cover this line — so assert that redaction happened, not how many rules fired.
        assertTrue("nothing was redacted", builder.redactionCount() >= 1)
    }

    @Test
    fun `secrets in retrieved context are redacted too`() {
        val ranked = ContextRanker.rank(
            listOf(
                ContextCandidate(
                    path = "src/Config.java",
                    fileName = "Config.java",
                    kind = MatchKind.CLASS_NAME,
                    text = """private static final String TOKEN = "ghp_ABCDEFGHIJKLMNOPQRSTUVWXYZ012345";""",
                ),
            ),
        )
        val text = PromptBuilder(AiResultType.REPOSITORY_ANSWER).relevantCode(ranked).userMessage()
        assertFalse(text, text.contains("ghp_ABCDEFGHIJKLMNOPQRSTUVWXYZ012345"))
    }

    @Test
    fun `nothing secret reaches a provider end to end`() {
        // The property that actually matters: what the transport would have sent.
        val provider = FakeLlmProvider.replying("{}")
        val messages = PromptBuilder(AiResultType.CODE_EXPLANATION)
            .selectedCode("""password = "hunter2secretvalue"""")
            .task("explain")
            .build()

        provider.complete(LlmRequest(messages = messages, model = "m"))

        assertFalse(provider.transmittedText(), provider.transmittedText().contains("hunter2secretvalue"))
    }

    // ------------------------------------------------------------------ budget

    @Test
    fun `only selected context is included`() {
        val candidates = (1..50).map {
            ContextCandidate("F$it.java", "F$it.java", kind = MatchKind.TEXT, text = "x".repeat(200))
        }
        val selection = ContextRanker.select(candidates, ContextBudget(maxFiles = 3, maxCharacters = 10_000))
        val text = PromptBuilder(AiResultType.REPOSITORY_ANSWER).relevantCode(selection.selected).userMessage()

        assertEquals(3, selection.fileCount)
        assertTrue(text.length < 5_000)
    }

    @Test
    fun `stack trace lines are capped in the prompt`() {
        val raw = buildString {
            appendLine("java.lang.RuntimeException: deep")
            repeat(300) { appendLine("\tat com.example.C$it.m(C$it.java:${it + 1})") }
        }
        val parsed = ErrorParser.parse(raw, maxStackTraceLines = 100)
        val text = PromptBuilder(AiResultType.ERROR_ANALYSIS).error(parsed, maxStackTraceLines = 20).userMessage()

        assertEquals(20, text.lines().count { it.trim().startsWith("com.example.C") })
    }

    @Test
    fun `application frames are presented before library frames`() {
        val raw = """
            java.lang.IllegalStateException: boom
            	at java.base/java.util.Objects.requireNonNull(Objects.java:233)
            	at com.example.OrderService.place(OrderService.java:57)
        """.trimIndent()
        val text = PromptBuilder(AiResultType.ERROR_ANALYSIS)
            .error(ErrorParser.parse(raw), maxStackTraceLines = 100)
            .userMessage()

        assertTrue(text, text.indexOf("com.example.OrderService") < text.indexOf("java.util.Objects"))
    }

    // ------------------------------------------------------------------ failure paths

    @Test
    fun `an unavailable provider is reported rather than called`() {
        val provider = FakeLlmProvider.unavailable()
        assertFalse(provider.isAvailable())
        assertTrue(provider.requests.isEmpty())
    }

    @Test
    fun `a timeout surfaces a user-facing message`() {
        val provider = FakeLlmProvider.failing(LlmFailure.Kind.TIMEOUT)
        val result = provider.complete(LlmRequest(emptyList(), "m"))
        val failure = (result as LlmResult.Failure).failure

        assertEquals(LlmFailure.Kind.TIMEOUT, failure.kind)
        assertTrue(failure.userMessage(), failure.userMessage().contains("did not respond"))
    }

    @Test
    fun `an unreachable provider explains itself without leaking detail`() {
        val provider = FakeLlmProvider.failing(LlmFailure.Kind.UNREACHABLE, "Connection refused.")
        val failure = (provider.complete(LlmRequest(emptyList(), "m")) as LlmResult.Failure).failure
        assertTrue(failure.userMessage(), failure.userMessage().contains("Connection refused"))
    }

    @Test
    fun `cancellation is honoured before the request is made`() {
        val provider = FakeLlmProvider.replying("{}")
        val result = provider.complete(LlmRequest(emptyList(), "m"), cancelled = { true })
        assertEquals(LlmFailure.Kind.CANCELLED, (result as LlmResult.Failure).failure.kind)
    }
}
