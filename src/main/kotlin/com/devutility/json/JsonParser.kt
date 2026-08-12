package com.devutility.json

/**
 * A recursive-descent JSON reader.
 *
 * The point of parsing rather than shuffling whitespace is diagnostics: a malformed document
 * gets a reason and a line/column instead of silently mangled output.
 *
 * [lenient] additionally accepts `//` and block comments, trailing commas, and single-quoted
 * strings — the dialect real config files are written in (`tsconfig.json`, `.eslintrc`, and
 * anything a human has hand-edited).
 */
class JsonParser(private val text: String, private val lenient: Boolean = false) {

    private var pos = 0

    fun parse(): JsonValue {
        skipIgnorable()
        if (pos >= text.length) fail("Empty input — expected a JSON value")
        val value = readValue(0)
        skipIgnorable()
        if (pos < text.length) fail("Unexpected trailing content after the JSON value")
        return value
    }

    // ------------------------------------------------------------------ values

    private fun readValue(depth: Int): JsonValue {
        if (depth > MAX_DEPTH) fail("Nesting is deeper than $MAX_DEPTH levels")
        skipIgnorable()
        if (pos >= text.length) fail("Expected a value but reached the end of the input")

        return when (val c = text[pos]) {
            '{' -> readObject(depth)
            '[' -> readArray(depth)
            '"' -> JsonValue.JsonString(readString())
            '\'' -> {
                if (!lenient) fail("Single-quoted strings are not valid JSON — enable lenient mode")
                JsonValue.JsonString(readString())
            }
            't', 'f', 'n' -> readLiteral()
            else -> {
                if (c == '-' || c in '0'..'9') readNumber()
                else fail("Unexpected character '${printable(c)}'")
            }
        }
    }

    private fun readObject(depth: Int): JsonValue {
        expect('{')
        val entries = mutableListOf<JsonEntry>()
        skipIgnorable()
        if (peek() == '}') {
            pos++
            return JsonValue.JsonObject(entries)
        }

        while (true) {
            skipIgnorable()
            if (lenient && peek() == '}') {
                pos++
                return JsonValue.JsonObject(entries)
            }
            val quote = peek()
            if (quote != '"' && !(lenient && quote == '\'')) {
                fail("Expected a quoted property name")
            }
            val key = readString()

            skipIgnorable()
            expect(':')
            val value = readValue(depth + 1)
            entries += JsonEntry(key, value)

            skipIgnorable()
            when (peek()) {
                ',' -> pos++
                '}' -> {
                    pos++
                    return JsonValue.JsonObject(entries)
                }
                null -> fail("Unclosed object — expected '}'")
                else -> fail("Expected ',' or '}' after a property value")
            }
        }
    }

    private fun readArray(depth: Int): JsonValue {
        expect('[')
        val items = mutableListOf<JsonValue>()
        skipIgnorable()
        if (peek() == ']') {
            pos++
            return JsonValue.JsonArray(items)
        }

        while (true) {
            skipIgnorable()
            if (lenient && peek() == ']') {
                pos++
                return JsonValue.JsonArray(items)
            }
            items += readValue(depth + 1)

            skipIgnorable()
            when (peek()) {
                ',' -> pos++
                ']' -> {
                    pos++
                    return JsonValue.JsonArray(items)
                }
                null -> fail("Unclosed array — expected ']'")
                else -> fail("Expected ',' or ']' after an array element")
            }
        }
    }

    private fun readLiteral(): JsonValue {
        for ((word, value) in LITERALS) {
            if (text.startsWith(word, pos)) {
                pos += word.length
                return value
            }
        }
        fail("Expected true, false or null")
    }

    private fun readNumber(): JsonValue {
        val start = pos
        if (peek() == '-') pos++
        if (peek() == '0') {
            pos++
        } else {
            if (peek()?.isDigit() != true) fail("Malformed number — expected a digit")
            while (peek()?.isDigit() == true) pos++
        }
        if (peek() == '.') {
            pos++
            if (peek()?.isDigit() != true) fail("Malformed number — expected a digit after '.'")
            while (peek()?.isDigit() == true) pos++
        }
        if (peek() == 'e' || peek() == 'E') {
            pos++
            if (peek() == '+' || peek() == '-') pos++
            if (peek()?.isDigit() != true) fail("Malformed number — expected a digit in the exponent")
            while (peek()?.isDigit() == true) pos++
        }
        return JsonValue.JsonNumber(text.substring(start, pos))
    }

    private fun readString(): String {
        val quote = text[pos]
        pos++
        val sb = StringBuilder()
        while (true) {
            if (pos >= text.length) fail("Unterminated string — expected a closing $quote")
            when (val c = text[pos]) {
                quote -> {
                    pos++
                    return sb.toString()
                }
                '\\' -> {
                    pos++
                    if (pos >= text.length) fail("Unterminated escape sequence")
                    when (val esc = text[pos]) {
                        '"' -> sb.append('"')
                        '\'' -> sb.append('\'')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'b' -> sb.append('\b')
                        'f' -> sb.append(FORM_FEED)
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            if (pos + 4 >= text.length) fail("Truncated \\u escape")
                            val hex = text.substring(pos + 1, pos + 5)
                            val code = hex.toIntOrNull(16) ?: fail("Invalid \\u escape '\\u$hex'")
                            sb.append(code.toChar())
                            pos += 4
                        }
                        else -> fail("Invalid escape '\\${printable(esc)}'")
                    }
                    pos++
                }
                '\n' -> fail("Unescaped line break inside a string")
                else -> {
                    sb.append(c)
                    pos++
                }
            }
        }
    }

    // ------------------------------------------------------------------ lexing

    private fun peek(): Char? = if (pos < text.length) text[pos] else null

    private fun expect(c: Char) {
        skipIgnorable()
        if (peek() != c) fail("Expected '$c'")
        pos++
    }

    /** Whitespace, plus comments when lenient. */
    private fun skipIgnorable() {
        while (pos < text.length) {
            val c = text[pos]
            when {
                c == ' ' || c == '\t' || c == '\n' || c == '\r' -> pos++
                lenient && c == '/' && pos + 1 < text.length && text[pos + 1] == '/' -> {
                    while (pos < text.length && text[pos] != '\n') pos++
                }
                lenient && c == '/' && pos + 1 < text.length && text[pos + 1] == '*' -> {
                    val end = text.indexOf("*/", pos + 2)
                    pos = if (end < 0) text.length else end + 2
                }
                else -> return
            }
        }
    }

    private fun printable(c: Char): String =
        if (c.isISOControl()) "\\u%04x".format(c.code) else c.toString()

    private fun fail(reason: String): Nothing {
        var line = 1
        var lastBreak = -1
        for (i in 0 until minOf(pos, text.length)) {
            if (text[i] == '\n') {
                line++
                lastBreak = i
            }
        }
        throw JsonSyntaxException(reason, line, pos - lastBreak, pos)
    }

    companion object {
        private const val MAX_DEPTH = 512

        /** U+000C FORM FEED, written numerically so no literal control byte sits in this file. */
        private val FORM_FEED = Char(12)

        private val LITERALS = listOf(
            "true" to JsonValue.JsonBool(true),
            "false" to JsonValue.JsonBool(false),
            "null" to JsonValue.JsonNull,
        )

        fun parse(text: String, lenient: Boolean = false): JsonValue = JsonParser(text, lenient).parse()
    }
}
