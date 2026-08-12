package com.devutility.json

/** How a document should be laid out. */
data class JsonFormatOptions(
    val indent: String = "  ",
    val sortKeys: Boolean = false,
    /** Accept comments, trailing commas and single quotes on the way in. */
    val lenient: Boolean = false,
)

object JsonFormatter {

    /** Parses then re-emits [text] indented. Throws [JsonSyntaxException] if it is not JSON. */
    fun format(text: String, options: JsonFormatOptions = JsonFormatOptions()): String =
        pretty(JsonParser.parse(text, options.lenient), options)

    /** Parses then re-emits [text] with no insignificant whitespace. */
    fun minify(text: String, options: JsonFormatOptions = JsonFormatOptions()): String =
        minify(JsonParser.parse(text, options.lenient), options)

    fun pretty(value: JsonValue, options: JsonFormatOptions = JsonFormatOptions()): String {
        val sb = StringBuilder()
        writePretty(value, 0, options, sb)
        return sb.toString()
    }

    fun minify(value: JsonValue, options: JsonFormatOptions = JsonFormatOptions()): String {
        val sb = StringBuilder()
        writeMinified(value, options, sb)
        return sb.toString()
    }

    /** Validates without producing output. Returns null when [text] is well-formed. */
    fun validate(text: String, lenient: Boolean = false): JsonSyntaxException? = try {
        JsonParser.parse(text, lenient)
        null
    } catch (e: JsonSyntaxException) {
        e
    }

    // ------------------------------------------------------------------ writing

    private fun writePretty(value: JsonValue, depth: Int, options: JsonFormatOptions, sb: StringBuilder) {
        when (value) {
            is JsonValue.JsonObject -> {
                // Empty containers stay on one line; "{\n}" is noise.
                if (value.entries.isEmpty()) {
                    sb.append("{}")
                    return
                }
                val entries = if (options.sortKeys) value.entries.sortedBy { it.key } else value.entries
                sb.append("{\n")
                for ((i, entry) in entries.withIndex()) {
                    sb.append(options.indent.repeat(depth + 1))
                    writeString(entry.key, sb)
                    sb.append(": ")
                    writePretty(entry.value, depth + 1, options, sb)
                    if (i < entries.size - 1) sb.append(',')
                    sb.append('\n')
                }
                sb.append(options.indent.repeat(depth)).append('}')
            }

            is JsonValue.JsonArray -> {
                if (value.items.isEmpty()) {
                    sb.append("[]")
                    return
                }
                sb.append("[\n")
                for ((i, item) in value.items.withIndex()) {
                    sb.append(options.indent.repeat(depth + 1))
                    writePretty(item, depth + 1, options, sb)
                    if (i < value.items.size - 1) sb.append(',')
                    sb.append('\n')
                }
                sb.append(options.indent.repeat(depth)).append(']')
            }

            else -> writeScalar(value, sb)
        }
    }

    private fun writeMinified(value: JsonValue, options: JsonFormatOptions, sb: StringBuilder) {
        when (value) {
            is JsonValue.JsonObject -> {
                val entries = if (options.sortKeys) value.entries.sortedBy { it.key } else value.entries
                sb.append('{')
                for ((i, entry) in entries.withIndex()) {
                    if (i > 0) sb.append(',')
                    writeString(entry.key, sb)
                    sb.append(':')
                    writeMinified(entry.value, options, sb)
                }
                sb.append('}')
            }

            is JsonValue.JsonArray -> {
                sb.append('[')
                for ((i, item) in value.items.withIndex()) {
                    if (i > 0) sb.append(',')
                    writeMinified(item, options, sb)
                }
                sb.append(']')
            }

            else -> writeScalar(value, sb)
        }
    }

    private fun writeScalar(value: JsonValue, sb: StringBuilder) {
        when (value) {
            is JsonValue.JsonNull -> sb.append("null")
            is JsonValue.JsonBool -> sb.append(if (value.value) "true" else "false")
            is JsonValue.JsonNumber -> sb.append(value.raw)
            is JsonValue.JsonString -> writeString(value.value, sb)
            else -> error("not a scalar: $value")
        }
    }

    private fun writeString(value: String, sb: StringBuilder) {
        sb.append('"')
        for (c in value) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c.code == 8 -> sb.append("\\b")
                c.code == 12 -> sb.append("\\f")
                c.isISOControl() -> sb.append("\\u").append("%04x".format(c.code))
                else -> sb.append(c)
            }
        }
        sb.append('"')
    }
}
