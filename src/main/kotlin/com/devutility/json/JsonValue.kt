package com.devutility.json

/**
 * A parsed JSON document.
 *
 * Numbers keep their original text and objects keep an ordered list of entries rather than a
 * map, so formatting is lossless: `1.0`, `1e3` and `0.1000` survive a round trip, and duplicate
 * keys — which the JSON grammar permits — are not silently collapsed.
 */
sealed class JsonValue {

    object JsonNull : JsonValue()

    data class JsonBool(val value: Boolean) : JsonValue()

    data class JsonNumber(val raw: String) : JsonValue()

    data class JsonString(val value: String) : JsonValue()

    data class JsonArray(val items: List<JsonValue>) : JsonValue()

    data class JsonObject(val entries: List<JsonEntry>) : JsonValue()
}

data class JsonEntry(val key: String, val value: JsonValue)

/** A syntax error with the position that caused it, so the UI can point at the offending line. */
class JsonSyntaxException(
    val reason: String,
    val line: Int,
    val column: Int,
    val offset: Int,
) : RuntimeException("$reason (line $line, column $column)")
