package com.devutility.ai

import com.devutility.ai.llm.LlmProvider
import com.devutility.ai.model.LlmFailure
import com.devutility.ai.model.LlmRequest
import com.devutility.ai.model.LlmResponse
import com.devutility.ai.model.LlmResult

/**
 * Deterministic stand-in for a model, so tests never touch Ollama or a cloud API (§27).
 *
 * Records what it was asked, which is how the prompt-construction tests assert that secrets
 * were redacted and the budget respected *before* anything would have been transmitted.
 */
class FakeLlmProvider(
    override val name: String = "Fake",
    private val available: Boolean = true,
    private val behaviour: (LlmRequest) -> LlmResult = { reply("{}") },
) : LlmProvider {

    val requests = mutableListOf<LlmRequest>()

    override fun isAvailable(): Boolean = available

    override fun complete(request: LlmRequest, cancelled: () -> Boolean): LlmResult {
        requests += request
        if (cancelled()) {
            return LlmResult.Failure(LlmFailure(LlmFailure.Kind.CANCELLED, name, ""))
        }
        return behaviour(request)
    }

    /** Everything the provider was asked to send, concatenated. */
    fun transmittedText(): String = requests.flatMap { it.messages }.joinToString("\n") { it.content }

    companion object {
        fun reply(content: String): LlmResult = LlmResult.Success(
            LlmResponse(content = content, model = "fake-model", providerName = "Fake", durationMillis = 1),
        )

        fun failing(kind: LlmFailure.Kind, detail: String = "test"): FakeLlmProvider =
            FakeLlmProvider(behaviour = { LlmResult.Failure(LlmFailure(kind, "Fake", detail)) })

        fun unavailable(): FakeLlmProvider = FakeLlmProvider(available = false)

        fun replying(content: String): FakeLlmProvider = FakeLlmProvider(behaviour = { reply(content) })
    }
}
