package com.devutility.props

/**
 * A `.properties` reader that keeps line numbers.
 *
 * [java.util.Properties] would be less code but throws the line numbers away, and we need
 * them to jump from a diff row back to the source file.
 */
object PropertiesParser {

    fun parse(text: String): LinkedHashMap<String, PropertyEntry> {
        val out = LinkedHashMap<String, PropertyEntry>()
        val lines = text.lines()
        var i = 0
        while (i < lines.size) {
            val startLine = i + 1
            val first = lines[i]
            i++

            val lead = first.trimStart()
            if (lead.isEmpty() || lead.startsWith("#") || lead.startsWith("!")) continue

            // A trailing odd number of backslashes continues onto the next line.
            var logical = first
            while (endsWithOddBackslash(logical) && i < lines.size) {
                logical = logical.dropLast(1) + lines[i].trimStart()
                i++
            }

            val (key, value) = splitKeyValue(logical) ?: continue
            if (key.isNotEmpty()) out[key] = PropertyEntry(key, value, startLine)
        }
        return out
    }

    /** Renders a flat key/value map back out as `.properties` text. */
    fun render(entries: Map<String, String>): String = buildString {
        for ((k, v) in entries) {
            append(escapeKey(k)).append('=').append(escapeValue(v)).append('\n')
        }
    }

    private fun endsWithOddBackslash(s: String): Boolean {
        var count = 0
        var i = s.length - 1
        while (i >= 0 && s[i] == '\\') {
            count++
            i--
        }
        return count % 2 == 1
    }

    private fun splitKeyValue(line: String): Pair<String, String>? {
        val s = line.trimStart()
        if (s.isEmpty()) return null

        var i = 0
        var escaped = false
        var sepIndex = -1
        var sepIsWhitespace = false
        while (i < s.length) {
            val c = s[i]
            if (escaped) {
                escaped = false
                i++
                continue
            }
            when (c) {
                '\\' -> escaped = true
                '=', ':' -> sepIndex = i
                ' ', '\t' -> {
                    sepIndex = i
                    sepIsWhitespace = true
                }
            }
            if (sepIndex >= 0) break
            i++
        }

        if (sepIndex < 0) return unescape(s.trimEnd()) to ""

        val key = unescape(s.substring(0, sepIndex)).trim()
        var rest = s.substring(sepIndex + 1)
        if (sepIsWhitespace) {
            rest = rest.trimStart()
            if (rest.startsWith("=") || rest.startsWith(":")) rest = rest.substring(1)
        }
        return key to unescape(rest.trimStart())
    }

    private fun unescape(s: String): String {
        if ('\\' !in s) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c != '\\' || i == s.length - 1) {
                sb.append(c)
                i++
                continue
            }
            i++
            when (val n = s[i]) {
                'n' -> sb.append('\n')
                't' -> sb.append('\t')
                'r' -> sb.append('\r')
                'f' -> sb.append('\u000C')
                'u' -> {
                    val hex = s.substring(i + 1, minOf(i + 5, s.length))
                    val code = hex.toIntOrNull(16)
                    if (code != null && hex.length == 4) {
                        sb.append(code.toChar())
                        i += 4
                    } else {
                        sb.append('u')
                    }
                }
                else -> sb.append(n)
            }
            i++
        }
        return sb.toString()
    }

    private fun escapeKey(k: String): String =
        k.replace("\\", "\\\\").replace(":", "\\:").replace("=", "\\=").replace(" ", "\\ ")

    private fun escapeValue(v: String): String =
        v.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")
}
