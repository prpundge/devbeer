package com.devutility.ai.llm

import com.devutility.ai.llm.HttpJson.asString
import com.devutility.ai.llm.HttpJson.field
import com.devutility.ai.llm.HttpJson.findField
import com.devutility.ai.model.LlmFailure
import com.devutility.ai.model.LlmRequest
import com.devutility.ai.model.LlmResponse
import com.devutility.ai.model.LlmResult
import com.devutility.ai.model.Role

/**
 * Local models over the Ollama HTTP API.
 *
 * Ollama is reached over HTTP only — there is no compile-time dependency on it, so anything
 * exposing the same endpoints works just as well (§4).
 */
class OllamaLlmProvider(
    private val endpoint: String,
    private val model: String,
) : LlmProvider {

    override val name: String = "Ollama"

    override fun isAvailable(): Boolean {
        if (endpoint.isBlank() || model.isBlank()) return false
        val outcome = HttpJson.get("$endpoint/api/tags", timeoutSeconds = 3).getOrNull() ?: return false
        return outcome.status in 200..299
    }

    override fun complete(request: LlmRequest, cancelled: () -> Boolean): LlmResult {
        if (endpoint.isBlank() || model.isBlank()) {
            return LlmResult.Failure(LlmFailure(LlmFailure.Kind.NOT_CONFIGURED, name, "No endpoint or model set."))
        }
        if (cancelled()) return LlmResult.Failure(LlmFailure(LlmFailure.Kind.CANCELLED, name, ""))

        val system = request.messages.filter { it.role == Role.SYSTEM }.joinToString("\n\n") { it.content }
        val user = request.messages.filter { it.role == Role.USER }.joinToString("\n\n") { it.content }

        val body = buildString {
            append('{')
            append("\"model\":").append(HttpJson.string(request.model.ifBlank { model })).append(',')
            append("\"prompt\":").append(HttpJson.string(user)).append(',')
            if (system.isNotBlank()) append("\"system\":").append(HttpJson.string(system)).append(',')
            if (request.expectJson) append("\"format\":\"json\",")
            append("\"stream\":false,")
            append("\"options\":{\"temperature\":").append(request.temperature).append('}')
            append('}')
        }

        val started = System.currentTimeMillis()
        val outcome = HttpJson.postJson("$endpoint/api/generate", body, request.timeoutSeconds)
            .getOrElse { return LlmResult.Failure(HttpJson.classify(name, it)) }

        if (cancelled()) return LlmResult.Failure(LlmFailure(LlmFailure.Kind.CANCELLED, name, ""))
        if (outcome.status !in 200..299) {
            return LlmResult.Failure(HttpJson.classifyStatus(name, outcome.status, outcome.body))
        }

        val json = HttpJson.parse(outcome.body)
            ?: return LlmResult.Failure(LlmFailure(LlmFailure.Kind.BAD_RESPONSE, name, "Response was not JSON."))
        val content = json.field("response").asString()
            ?: return LlmResult.Failure(
                LlmFailure(LlmFailure.Kind.BAD_RESPONSE, name, "Response had no 'response' field."),
            )

        return LlmResult.Success(
            LlmResponse(
                content = content,
                model = json.field("model").asString() ?: model,
                providerName = name,
                durationMillis = System.currentTimeMillis() - started,
            ),
        )
    }
}

/**
 * Any endpoint speaking the OpenAI chat-completions API.
 *
 * Endpoint, key, model and timeout are all configuration, so a compatible gateway can be
 * pointed at without a code change (§3, §5).
 */
class OpenAiCompatibleLlmProvider(
    private val endpoint: String,
    private val model: String,
    /** Read lazily so the key is never held in a field longer than a single call. */
    private val apiKeySupplier: () -> String?,
) : LlmProvider {

    override val name: String = "OpenAI-compatible endpoint"

    override fun isAvailable(): Boolean = endpoint.isNotBlank() && model.isNotBlank()

    override fun complete(request: LlmRequest, cancelled: () -> Boolean): LlmResult {
        if (endpoint.isBlank() || model.isBlank()) {
            return LlmResult.Failure(LlmFailure(LlmFailure.Kind.NOT_CONFIGURED, name, "No endpoint or model set."))
        }
        if (cancelled()) return LlmResult.Failure(LlmFailure(LlmFailure.Kind.CANCELLED, name, ""))

        val body = buildString {
            append('{')
            append("\"model\":").append(HttpJson.string(request.model.ifBlank { model })).append(',')
            append("\"temperature\":").append(request.temperature).append(',')
            if (request.expectJson) append("\"response_format\":{\"type\":\"json_object\"},")
            append("\"messages\":[")
            request.messages.forEachIndexed { i, message ->
                if (i > 0) append(',')
                append("{\"role\":")
                append(if (message.role == Role.SYSTEM) "\"system\"" else "\"user\"")
                append(",\"content\":").append(HttpJson.string(message.content)).append('}')
            }
            append("]}")
        }

        val headers = buildMap {
            apiKeySupplier()?.takeIf { it.isNotBlank() }?.let { put("Authorization", "Bearer $it") }
        }

        val started = System.currentTimeMillis()
        val outcome = HttpJson.postJson("$endpoint/chat/completions", body, request.timeoutSeconds, headers)
            .getOrElse { return LlmResult.Failure(HttpJson.classify(name, it)) }

        if (cancelled()) return LlmResult.Failure(LlmFailure(LlmFailure.Kind.CANCELLED, name, ""))
        if (outcome.status !in 200..299) {
            return LlmResult.Failure(HttpJson.classifyStatus(name, outcome.status, outcome.body))
        }

        val json = HttpJson.parse(outcome.body)
            ?: return LlmResult.Failure(LlmFailure(LlmFailure.Kind.BAD_RESPONSE, name, "Response was not JSON."))

        // choices[0].message.content, located structurally then by search, because gateways
        // differ in how deeply they nest it.
        val content = json.field("choices").let { choices ->
            (choices as? com.devutility.json.JsonValue.JsonArray)?.items?.firstOrNull()
                ?.field("message")?.field("content").asString()
        } ?: json.findField("content").asString()
        ?: return LlmResult.Failure(
            LlmFailure(LlmFailure.Kind.BAD_RESPONSE, name, "Response had no message content."),
        )

        return LlmResult.Success(
            LlmResponse(
                content = content,
                model = json.field("model").asString() ?: model,
                providerName = name,
                durationMillis = System.currentTimeMillis() - started,
            ),
        )
    }
}
