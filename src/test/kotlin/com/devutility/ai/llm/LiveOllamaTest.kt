package com.devutility.ai.llm

import com.devutility.ai.model.AiResultType
import com.devutility.ai.model.LlmRequest
import com.devutility.ai.model.LlmResult
import com.devutility.ai.parse.AiResponseParser
import com.devutility.ai.prompt.PromptBuilder
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Exercises the real HTTP path against a locally running Ollama.
 *
 * Every other LLM test uses [com.devutility.ai.FakeLlmProvider]; this one exists because a
 * fake cannot catch a wrong endpoint path, a malformed request body, or a response envelope
 * read from the wrong field — the failures that only appear against a real server.
 *
 * **Skipped automatically when Ollama is not running**, so the suite never depends on it
 * (§27: unit tests must not require a live model). Run it deliberately with a model present.
 */
class LiveOllamaTest {

    private val endpoint = System.getenv("DEVBEER_TEST_OLLAMA_ENDPOINT") ?: "http://localhost:11434"
    private val model = System.getenv("DEVBEER_TEST_OLLAMA_MODEL") ?: "qwen2.5:3b"

    private fun provider() = OllamaLlmProvider(endpoint, model)

    private fun requireOllama() {
        assumeTrue("Ollama is not reachable at $endpoint — skipping live test", provider().isAvailable())
    }

    @Test
    fun `availability probe reaches a running server`() {
        requireOllama()
        assertTrue(provider().isAvailable())
    }

    @Test
    fun `availability probe reports false for a dead endpoint without throwing`() {
        // No assumption: this must hold whether or not Ollama is running.
        assertFalse(OllamaLlmProvider("http://localhost:59999", "any").isAvailable())
    }

    @Test
    fun `a real completion returns content`() {
        requireOllama()
        val result = provider().complete(
            LlmRequest(
                messages = PromptBuilder(AiResultType.REPOSITORY_ANSWER)
                    .projectContext("test")
                    .task("Reply with a JSON object having summary set to the word ok.")
                    .build(),
                model = model,
                timeoutSeconds = 120,
            ),
        )

        when (result) {
            is LlmResult.Success -> {
                assertTrue("content should not be empty", result.response.content.isNotBlank())
                assertTrue("duration should be recorded", result.response.durationMillis > 0)
            }
            is LlmResult.Failure -> throw AssertionError("live call failed: ${result.failure}")
        }
    }

    @Test
    fun `the json format hint produces parseable structured output`() {
        requireOllama()
        val result = provider().complete(
            LlmRequest(
                messages = PromptBuilder(AiResultType.ERROR_ANALYSIS)
                    .projectContext("test")
                    .error(
                        com.devutility.ai.error.ErrorParser.parse(
                            """
                            java.lang.NullPointerException: payment is null
                            	at com.example.PaymentService.create(PaymentService.java:81)
                            """.trimIndent(),
                        ),
                        100,
                    )
                    .task("Identify the root cause and reply as JSON.")
                    .build(),
                model = model,
                timeoutSeconds = 180,
            ),
        )

        val content = (result as? LlmResult.Success)?.response?.content
            ?: throw AssertionError("live call failed: $result")

        // The real assertion: our parser copes with what a real model actually emits.
        val outcome = AiResponseParser.errorAnalysis(content)
        assertNotNull("raw text is always kept", outcome.rawText)
        assertTrue(
            "expected structured output or a clean fallback, got: ${content.take(300)}",
            outcome.structured || outcome.value == null,
        )
    }

    @Test
    fun `an unknown model fails cleanly rather than throwing`() {
        requireOllama()
        val result = OllamaLlmProvider(endpoint, "definitely-not-a-real-model:0b").complete(
            LlmRequest(
                messages = PromptBuilder(AiResultType.REPOSITORY_ANSWER).task("hi").build(),
                model = "definitely-not-a-real-model:0b",
                timeoutSeconds = 30,
            ),
        )
        assertTrue("expected a typed failure, got $result", result is LlmResult.Failure)
    }

    @Test
    fun `cancellation stops the request`() {
        requireOllama()
        val result = provider().complete(
            LlmRequest(
                messages = PromptBuilder(AiResultType.REPOSITORY_ANSWER).task("hi").build(),
                model = model,
            ),
            cancelled = { true },
        )
        assertTrue(result is LlmResult.Failure)
    }
}
