package com.devutility.ai.llm

import com.devutility.ai.model.LlmFailure
import com.devutility.json.JsonEntry
import com.devutility.json.JsonFormatter
import com.devutility.json.JsonParser
import com.devutility.json.JsonSyntaxException
import com.devutility.json.JsonValue
import java.net.ConnectException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.nio.charset.StandardCharsets
import java.time.Duration

/**
 * The entire HTTP + JSON layer for the AI module.
 *
 * Transport is the JDK's [HttpClient] and JSON is the plugin's own parser from the 2.2.0
 * JSON module, so the AI feature adds **no third-party dependency at all** (§18, §29).
 */
internal object HttpJson {

    /** One shared client. Building an HttpClient per request leaks threads. */
    private val client: HttpClient by lazy {
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()
    }

    data class HttpOutcome(val status: Int, val body: String)

    /**
     * POSTs [body] as JSON. Returns the raw outcome, or a typed failure — exceptions are not
     * allowed to escape into feature code.
     */
    fun postJson(
        url: String,
        body: String,
        timeoutSeconds: Int,
        headers: Map<String, String> = emptyMap(),
    ): Result<HttpOutcome> = try {
        val builder = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(timeoutSeconds.toLong().coerceIn(5, 600)))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
        headers.forEach { (k, v) -> builder.header(k, v) }

        val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        Result.success(HttpOutcome(response.statusCode(), response.body()))
    } catch (e: Exception) {
        Result.failure(e)
    }

    /** GET, used only for reachability probes. */
    fun get(url: String, timeoutSeconds: Int = 5): Result<HttpOutcome> = try {
        val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(timeoutSeconds.toLong()))
            .GET()
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        Result.success(HttpOutcome(response.statusCode(), response.body()))
    } catch (e: Exception) {
        Result.failure(e)
    }

    /** Maps a transport exception onto a failure the UI can explain. Never leaks a key. */
    fun classify(providerName: String, e: Throwable): LlmFailure = when (e) {
        is HttpTimeoutException -> LlmFailure(LlmFailure.Kind.TIMEOUT, providerName, "Request timed out.")
        is ConnectException -> LlmFailure(
            LlmFailure.Kind.UNREACHABLE, providerName,
            "Connection refused. Is the server running?",
        )
        else -> LlmFailure(
            LlmFailure.Kind.UNREACHABLE, providerName,
            e.message?.take(200) ?: e.javaClass.simpleName,
        )
    }

    fun classifyStatus(providerName: String, status: Int, body: String): LlmFailure = when (status) {
        401, 403 -> LlmFailure(LlmFailure.Kind.AUTH, providerName, "HTTP $status")
        429 -> LlmFailure(LlmFailure.Kind.RATE_LIMITED, providerName, "HTTP 429")
        else -> LlmFailure(
            LlmFailure.Kind.OTHER, providerName,
            "$providerName returned HTTP $status. ${summarise(body)}",
        )
    }

    /** Keeps an error body short and free of anything that might echo a prompt back. */
    private fun summarise(body: String): String =
        body.replace(Regex("\\s+"), " ").trim().take(200)

    // ---------------------------------------------------------------- JSON helpers

    fun string(value: String): String {
        val sb = StringBuilder()
        sb.append('"')
        for (c in value) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c.isISOControl() -> sb.append("\\u").append("%04x".format(c.code))
                else -> sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }

    fun parse(text: String): JsonValue? = try {
        JsonParser.parse(text, lenient = true)
    } catch (e: JsonSyntaxException) {
        null
    }

    fun JsonValue.entries(): List<JsonEntry> = (this as? JsonValue.JsonObject)?.entries.orEmpty()

    fun JsonValue.field(name: String): JsonValue? = entries().firstOrNull { it.key == name }?.value

    /** Depth-first search for the first field with [name] — models nest differently. */
    fun JsonValue.findField(name: String): JsonValue? {
        field(name)?.let { return it }
        when (this) {
            is JsonValue.JsonObject -> entries.forEach { e -> e.value.findField(name)?.let { return it } }
            is JsonValue.JsonArray -> items.forEach { i -> i.findField(name)?.let { return it } }
            else -> Unit
        }
        return null
    }

    fun JsonValue?.asString(): String? = when (this) {
        is JsonValue.JsonString -> value
        is JsonValue.JsonNumber -> raw
        is JsonValue.JsonBool -> value.toString()
        else -> null
    }

    fun JsonValue?.asDouble(): Double? = when (this) {
        is JsonValue.JsonNumber -> raw.toDoubleOrNull()
        is JsonValue.JsonString -> value.trim().removeSuffix("%").toDoubleOrNull()
        else -> null
    }

    fun JsonValue?.asInt(): Int? = asDouble()?.toInt()

    fun JsonValue?.asArray(): List<JsonValue> = (this as? JsonValue.JsonArray)?.items.orEmpty()

    fun render(value: JsonValue): String = JsonFormatter.minify(value)
}
