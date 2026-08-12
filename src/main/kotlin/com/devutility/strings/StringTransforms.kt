package com.devutility.strings

import com.devutility.json.JsonFormatOptions
import com.devutility.json.JsonFormatter
import com.devutility.props.PropertiesParser
import com.devutility.props.YamlParser
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.Locale
import java.util.UUID
import java.util.zip.CRC32

/**
 * One named text transformation.
 *
 * [editorSafe] is false for transforms whose output is a *report about* the text rather than
 * a replacement for it — those are hidden from the "replace the selection" action, where
 * swapping code for a statistics dump would never be what you wanted.
 */
class StringTransform(
    val id: String,
    val category: String,
    val title: String,
    val description: String,
    val editorSafe: Boolean = true,
    private val fn: (String) -> String,
) {
    fun apply(input: String): String = fn(input)

    override fun toString(): String = title
}

object TransformCategory {
    const val CASE = "Case"
    const val ENCODE = "Encode / Decode"
    const val HASH = "Hash"
    const val DATA = "JSON / XML"
    const val LINES = "Lines"
    const val TEXT = "Text"
    const val CONFIG = "Config files"
    const val TIME = "Time & IDs"
}

object StringTransforms {

    // Declared before ALL: the sort at the end of the builder dereferences it eagerly.
    private val CATEGORY_ORDER = listOf(
        TransformCategory.CASE,
        TransformCategory.ENCODE,
        TransformCategory.HASH,
        TransformCategory.DATA,
        TransformCategory.LINES,
        TransformCategory.TEXT,
        TransformCategory.CONFIG,
        TransformCategory.TIME,
    )

    val ALL: List<StringTransform> = buildList {
        // ---------------------------------------------------------------- case
        case("camel-case", "camelCase") { w -> w.mapIndexed { i, s -> if (i == 0) s.lowercase() else s.cap() }.joinToString("") }
        case("pascal-case", "PascalCase") { w -> w.joinToString("") { it.cap() } }
        case("snake-case", "snake_case") { w -> w.joinToString("_") { it.lowercase() } }
        case("constant-case", "CONSTANT_CASE") { w -> w.joinToString("_") { it.uppercase() } }
        case("kebab-case", "kebab-case") { w -> w.joinToString("-") { it.lowercase() } }
        case("dot-case", "dot.case") { w -> w.joinToString(".") { it.lowercase() } }
        case("title-case", "Title Case") { w -> w.joinToString(" ") { it.cap() } }
        case("sentence-case", "Sentence case") { w ->
            w.mapIndexed { i, s -> if (i == 0) s.cap() else s.lowercase() }.joinToString(" ")
        }
        add(TransformCategory.CASE, "upper-case", "UPPERCASE", "Uppercase every character") { it.uppercase() }
        add(TransformCategory.CASE, "lower-case", "lowercase", "Lowercase every character") { it.lowercase() }
        add(TransformCategory.CASE, "swap-case", "sWAP cASE", "Invert the case of every letter") { s ->
            s.map { if (it.isUpperCase()) it.lowercaseChar() else it.uppercaseChar() }.joinToString("")
        }

        // ---------------------------------------------------------------- encode / decode
        add(TransformCategory.ENCODE, "base64-encode", "Base64 encode", "Standard Base64, with padding") {
            Base64.getEncoder().encodeToString(it.toByteArray(StandardCharsets.UTF_8))
        }
        add(TransformCategory.ENCODE, "base64-decode", "Base64 decode", "Tolerates line breaks and whitespace") {
            String(Base64.getMimeDecoder().decode(it.trim()), StandardCharsets.UTF_8)
        }
        add(TransformCategory.ENCODE, "base64url-encode", "Base64 encode (URL-safe)", "URL-safe alphabet, unpadded") {
            Base64.getUrlEncoder().withoutPadding().encodeToString(it.toByteArray(StandardCharsets.UTF_8))
        }
        add(TransformCategory.ENCODE, "base64url-decode", "Base64 decode (URL-safe)", "URL-safe alphabet") {
            String(Base64.getUrlDecoder().decode(it.trim().padBase64()), StandardCharsets.UTF_8)
        }
        add(TransformCategory.ENCODE, "url-encode", "URL encode", "application/x-www-form-urlencoded") {
            URLEncoder.encode(it, StandardCharsets.UTF_8)
        }
        add(TransformCategory.ENCODE, "url-decode", "URL decode", "application/x-www-form-urlencoded") {
            URLDecoder.decode(it, StandardCharsets.UTF_8)
        }
        add(TransformCategory.ENCODE, "html-escape", "HTML escape", "Escape &, <, >, \" and '") { s ->
            s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;")
        }
        add(TransformCategory.ENCODE, "html-unescape", "HTML unescape", "Named and numeric entities") { unescapeHtml(it) }
        add(TransformCategory.ENCODE, "hex-encode", "Hex encode", "UTF-8 bytes as lowercase hex") { s ->
            s.toByteArray(StandardCharsets.UTF_8).joinToString("") { "%02x".format(it) }
        }
        add(TransformCategory.ENCODE, "hex-decode", "Hex decode", "Hex string back to UTF-8 text") { s ->
            val clean = s.filter { !it.isWhitespace() }
            require(clean.length % 2 == 0) { "Hex input must have an even number of digits" }
            String(ByteArray(clean.length / 2) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }, StandardCharsets.UTF_8)
        }
        add(TransformCategory.ENCODE, "unicode-escape", "Unicode escape", "Non-ASCII characters as \\uXXXX") { s ->
            s.map { if (it.code in 32..126 || it == '\n' || it == '\t') it.toString() else "\\u%04x".format(it.code) }
                .joinToString("")
        }
        add(TransformCategory.ENCODE, "unicode-unescape", "Unicode unescape", "\\uXXXX back to characters") { s ->
            Regex("\\\\u([0-9a-fA-F]{4})").replace(s) { m -> m.groupValues[1].toInt(16).toChar().toString() }
        }

        // ---------------------------------------------------------------- hash
        digest("md5", "MD5", "MD5")
        digest("sha1", "SHA-1", "SHA-1")
        digest("sha256", "SHA-256", "SHA-256")
        digest("sha512", "SHA-512", "SHA-512")
        add(TransformCategory.HASH, "crc32", "CRC32", "CRC32 checksum as hex") { s ->
            val crc = CRC32()
            crc.update(s.toByteArray(StandardCharsets.UTF_8))
            "%08x".format(crc.value)
        }

        // ---------------------------------------------------------------- json / xml
        add(TransformCategory.DATA, "json-pretty", "JSON pretty print", "Reindent with two spaces; reports the line and column of any syntax error") {
            JsonFormatter.format(it)
        }
        add(TransformCategory.DATA, "json-minify", "JSON minify", "Strip all insignificant whitespace") {
            JsonFormatter.minify(it)
        }
        add(TransformCategory.DATA, "json-pretty-lenient", "JSON pretty print (lenient)", "Also accepts comments, trailing commas and single quotes") {
            JsonFormatter.format(it, JsonFormatOptions(lenient = true))
        }
        add(TransformCategory.DATA, "json-sort-keys", "JSON sort keys", "Pretty print with every object's keys in alphabetical order") {
            JsonFormatter.format(it, JsonFormatOptions(sortKeys = true, lenient = true))
        }
        add(TransformCategory.DATA, "json-validate", "JSON validate", "Report whether the text is valid JSON, and where it breaks", editorSafe = false) { text ->
            val strict = JsonFormatter.validate(text, lenient = false)
            when {
                strict == null -> "Valid JSON."
                JsonFormatter.validate(text, lenient = true) == null ->
                    "Not strict JSON, but readable in lenient mode.\n" +
                        "  ${strict.reason} at line ${strict.line}, column ${strict.column}\n" +
                        "  Comments, trailing commas and single quotes are accepted by the lenient transforms."
                else -> "Invalid JSON.\n  ${strict.reason}\n  at line ${strict.line}, column ${strict.column}"
            }
        }
        add(TransformCategory.DATA, "java-escape", "Escape as Java string", "Quotes, backslashes and newlines escaped") { s ->
            s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
                .replace("\r", "\\r").replace("\t", "\\t")
        }
        add(TransformCategory.DATA, "java-unescape", "Unescape Java string", "The inverse of the above") { s ->
            s.replace("\\n", "\n").replace("\\r", "\r").replace("\\t", "\t")
                .replace("\\\"", "\"").replace("\\\\", "\\")
        }
        add(TransformCategory.DATA, "xml-pretty", "XML pretty print", "Reindent an XML document") { prettyXml(it) }
        add(TransformCategory.DATA, "jwt-decode", "JWT decode", "Header and payload of a JWT, pretty printed", editorSafe = false) {
            decodeJwt(it)
        }

        // ---------------------------------------------------------------- lines
        lines("lines-sort", "Sort lines A→Z") { it.sorted() }
        lines("lines-sort-desc", "Sort lines Z→A") { it.sortedDescending() }
        lines("lines-sort-ci", "Sort lines (ignore case)") { it.sortedBy { l -> l.lowercase() } }
        lines("lines-sort-natural", "Sort lines naturally") { it.sortedWith(NATURAL) }
        lines("lines-reverse", "Reverse line order") { it.reversed() }
        lines("lines-shuffle", "Shuffle lines") { it.shuffled() }
        lines("lines-dedupe", "Remove duplicate lines") { it.distinct() }
        lines("lines-remove-blank", "Remove blank lines") { l -> l.filter { it.isNotBlank() } }
        lines("lines-trim", "Trim each line") { l -> l.map { it.trim() } }
        lines("lines-number", "Number lines") { l ->
            val width = l.size.toString().length
            l.mapIndexed { i, s -> "${(i + 1).toString().padStart(width)}. $s" }
        }
        add(TransformCategory.LINES, "lines-join-comma", "Join lines with \", \"", "One-line comma separated list") { s ->
            s.lines().filter { it.isNotBlank() }.joinToString(", ") { it.trim() }
        }
        add(TransformCategory.LINES, "lines-join-space", "Join lines with a space", "Collapse to a single line") { s ->
            s.lines().filter { it.isNotBlank() }.joinToString(" ") { it.trim() }
        }
        add(TransformCategory.LINES, "lines-split-comma", "Split on commas into lines", "The inverse of joining") { s ->
            s.split(",").map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
        }
        add(TransformCategory.LINES, "lines-sql-in", "Wrap as a SQL IN list", "'a', 'b', 'c' — ready to paste into a query") { s ->
            s.lines().filter { it.isNotBlank() }.joinToString(", ") { "'${it.trim().replace("'", "''")}'" }
        }
        add(TransformCategory.LINES, "lines-quote", "Quote each line", "Surround every line with double quotes") { s ->
            s.lines().joinToString("\n") { "\"${it.replace("\"", "\\\"")}\"" }
        }
        add(TransformCategory.LINES, "lines-count", "Count lines", "Replace the text with its line count", editorSafe = false) { s ->
            s.lines().size.toString()
        }

        // ---------------------------------------------------------------- text
        add(TransformCategory.TEXT, "text-reverse", "Reverse characters", "Reverse the whole string") { it.reversed() }
        add(TransformCategory.TEXT, "text-collapse-ws", "Collapse whitespace", "Runs of whitespace become one space") { s ->
            s.replace(Regex("[ \\t]+"), " ").trim()
        }
        add(TransformCategory.TEXT, "text-strip-ws", "Remove all whitespace", "Including line breaks") { s ->
            s.filter { !it.isWhitespace() }
        }
        add(TransformCategory.TEXT, "text-rot13", "ROT13", "Classic letter rotation") { s ->
            s.map { c ->
                when {
                    c in 'a'..'z' -> 'a' + (c - 'a' + 13) % 26
                    c in 'A'..'Z' -> 'A' + (c - 'A' + 13) % 26
                    else -> c
                }
            }.joinToString("")
        }
        add(TransformCategory.TEXT, "text-extract-urls", "Extract URLs", "One URL per line", editorSafe = false) { s ->
            Regex("https?://[^\\s\"'<>()\\[\\]]+").findAll(s).map { it.value }.distinct().joinToString("\n")
        }
        add(TransformCategory.TEXT, "text-extract-emails", "Extract email addresses", "One address per line", editorSafe = false) { s ->
            Regex("[\\w.+-]+@[\\w-]+\\.[\\w.-]+").findAll(s).map { it.value }.distinct().joinToString("\n")
        }
        add(TransformCategory.TEXT, "text-extract-numbers", "Extract numbers", "One number per line", editorSafe = false) { s ->
            Regex("-?\\d+(?:\\.\\d+)?").findAll(s).map { it.value }.joinToString("\n")
        }
        add(TransformCategory.TEXT, "text-word-frequency", "Word frequency", "Words by descending count", editorSafe = false) { s ->
            Regex("[\\p{L}\\p{N}_'-]+").findAll(s.lowercase())
                .map { it.value }
                .groupingBy { it }
                .eachCount()
                .entries
                .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                .joinToString("\n") { "${it.value}\t${it.key}" }
        }
        add(TransformCategory.TEXT, "text-stats", "Text statistics", "Characters, words, lines and bytes", editorSafe = false) { s ->
            statistics(s)
        }

        // ---------------------------------------------------------------- config files
        add(TransformCategory.CONFIG, "props-to-yaml", ".properties → YAML", "Expand dotted keys into a YAML tree") { s ->
            YamlParser.render(PropertiesParser.parse(s).mapValues { it.value.value })
        }
        add(TransformCategory.CONFIG, "yaml-to-props", "YAML → .properties", "Flatten a YAML tree into dotted keys") { s ->
            PropertiesParser.render(YamlParser.parse(s).mapValues { it.value.value })
        }
        add(TransformCategory.CONFIG, "props-sort", "Sort .properties by key", "Alphabetical, comments dropped") { s ->
            PropertiesParser.render(PropertiesParser.parse(s).mapValues { it.value.value }.toSortedMap())
        }
        add(TransformCategory.CONFIG, "yaml-sort", "Sort YAML by key", "Flatten, sort, rebuild the tree") { s ->
            YamlParser.render(YamlParser.parse(s).mapValues { it.value.value }.toSortedMap())
        }

        // ---------------------------------------------------------------- time & ids
        add(TransformCategory.TIME, "epoch-to-iso", "Epoch → ISO-8601", "Seconds or milliseconds, auto-detected") { s ->
            s.lines().filter { it.isNotBlank() }.joinToString("\n") { line ->
                val n = line.trim().toLongOrNull() ?: return@joinToString line
                val instant = if (kotlin.math.abs(n) > 100_000_000_000L) Instant.ofEpochMilli(n) else Instant.ofEpochSecond(n)
                ZonedDateTime.ofInstant(instant, ZoneId.systemDefault()).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
            }
        }
        add(TransformCategory.TIME, "iso-to-epoch", "ISO-8601 → epoch millis", "One timestamp per line") { s ->
            s.lines().filter { it.isNotBlank() }.joinToString("\n") { line ->
                runCatching { ZonedDateTime.parse(line.trim()).toInstant().toEpochMilli().toString() }
                    .getOrElse { line }
            }
        }
        add(TransformCategory.TIME, "now", "Insert current timestamp", "Local ISO-8601 plus epoch millis") {
            val now = ZonedDateTime.now()
            "${now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)}\n${now.toInstant().toEpochMilli()}"
        }
        add(TransformCategory.TIME, "uuid", "Generate UUID", "One random UUID, or one per input line") { s ->
            val count = s.lines().count { it.isNotBlank() }.coerceAtLeast(1)
            (1..count).joinToString("\n") { UUID.randomUUID().toString() }
        }
        add(TransformCategory.TIME, "uuid-compact", "Generate UUID (no dashes)", "Random UUID without separators") { s ->
            val count = s.lines().count { it.isNotBlank() }.coerceAtLeast(1)
            (1..count).joinToString("\n") { UUID.randomUUID().toString().replace("-", "") }
        }
    }.sortedWith(compareBy({ CATEGORY_ORDER.indexOf(it.category) }, { it.title }))

    val BY_CATEGORY: Map<String, List<StringTransform>> = ALL.groupBy { it.category }

    fun byId(id: String): StringTransform? = ALL.firstOrNull { it.id == id }

    // ---------------------------------------------------------------- builders

    private fun MutableList<StringTransform>.add(
        category: String,
        id: String,
        title: String,
        description: String,
        editorSafe: Boolean = true,
        fn: (String) -> String,
    ) {
        add(StringTransform(id, category, title, description, editorSafe, fn))
    }

    /** Case conversions apply per line, so a pasted list of names converts in one go. */
    private fun MutableList<StringTransform>.case(id: String, title: String, join: (List<String>) -> String) {
        add(TransformCategory.CASE, id, title, "Convert each line to $title") { input ->
            input.lines().joinToString("\n") { line ->
                if (line.isBlank()) line else join(splitWords(line))
            }
        }
    }

    private fun MutableList<StringTransform>.lines(id: String, title: String, op: (List<String>) -> List<String>) {
        add(TransformCategory.LINES, id, title, title) { op(it.lines()).joinToString("\n") }
    }

    private fun MutableList<StringTransform>.digest(id: String, title: String, algorithm: String) {
        add(TransformCategory.HASH, id, title, "$algorithm digest of the UTF-8 bytes, as hex") { s ->
            MessageDigest.getInstance(algorithm)
                .digest(s.toByteArray(StandardCharsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun String.cap(): String =
        lowercase().replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }

    /**
     * Splits an identifier or phrase into words on separators *and* camel humps, so
     * `HTTPServerName` becomes `HTTP`, `Server`, `Name`.
     */
    private fun splitWords(input: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        fun flush() {
            if (sb.isNotEmpty()) {
                out += sb.toString()
                sb.clear()
            }
        }
        for (c in input) {
            if (!c.isLetterOrDigit()) {
                flush()
                continue
            }
            if (sb.isNotEmpty()) {
                val prev = sb.last()
                if (prev.isLowerCase() && c.isUpperCase()) {
                    flush()
                } else if (prev.isUpperCase() && c.isLowerCase() && sb.length > 1) {
                    // ...ABCd -> keep "AB", start a new word with "Cd"
                    val carry = sb.last()
                    sb.deleteCharAt(sb.length - 1)
                    flush()
                    sb.append(carry)
                }
            }
            sb.append(c)
        }
        flush()
        return out
    }

    private val CHUNKS = Regex("\\d+|\\D+")

    /** Sorts `item2` before `item10`, which plain lexicographic ordering gets wrong. */
    private val NATURAL = Comparator<String> { a, b ->
        val left = CHUNKS.findAll(a).map { it.value }.toList()
        val right = CHUNKS.findAll(b).map { it.value }.toList()
        for (i in 0 until minOf(left.size, right.size)) {
            val l = left[i]
            val r = right[i]
            val cmp = if (l.first().isDigit() && r.first().isDigit()) {
                (l.toBigIntegerOrNull() ?: java.math.BigInteger.ZERO)
                    .compareTo(r.toBigIntegerOrNull() ?: java.math.BigInteger.ZERO)
            } else {
                l.compareTo(r)
            }
            if (cmp != 0) return@Comparator cmp
        }
        left.size - right.size
    }

    private fun String.padBase64(): String = when (length % 4) {
        2 -> "$this=="
        3 -> "$this="
        else -> this
    }

    private fun statistics(s: String): String {
        val lines = s.lines()
        val words = Regex("\\S+").findAll(s).count()
        val bytes = s.toByteArray(StandardCharsets.UTF_8).size
        return buildString {
            append("Characters       : ").append(s.length).append('\n')
            append("Characters (no ws): ").append(s.count { !it.isWhitespace() }).append('\n')
            append("Words            : ").append(words).append('\n')
            append("Lines            : ").append(lines.size).append('\n')
            append("Non-blank lines  : ").append(lines.count { it.isNotBlank() }).append('\n')
            append("Bytes (UTF-8)    : ").append(bytes)
        }
    }

    private fun unescapeHtml(s: String): String {
        val named = mapOf(
            "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'",
            "nbsp" to "\u00A0", "copy" to "©", "reg" to "®", "hellip" to "…", "mdash" to "—", "ndash" to "–",
        )
        return Regex("&(#x?[0-9a-fA-F]+|\\w+);").replace(s) { m ->
            val body = m.groupValues[1]
            when {
                body.startsWith("#x") || body.startsWith("#X") ->
                    body.drop(2).toIntOrNull(16)?.toChar()?.toString() ?: m.value
                body.startsWith("#") ->
                    body.drop(1).toIntOrNull()?.toChar()?.toString() ?: m.value
                else -> named[body.lowercase()] ?: m.value
            }
        }
    }

    private fun decodeJwt(token: String): String {
        val parts = token.trim().split(".")
        require(parts.size >= 2) { "A JWT needs at least a header and a payload separated by '.'" }
        val decoder = Base64.getUrlDecoder()
        fun segment(i: Int) = String(decoder.decode(parts[i].padBase64()), StandardCharsets.UTF_8)
        return buildString {
            append("// header\n").append(JsonFormatter.format(segment(0))).append("\n\n")
            append("// payload\n").append(JsonFormatter.format(segment(1)))
            if (parts.size > 2) append("\n\n// signature (not verified)\n").append(parts[2])
        }
    }

    // ---------------------------------------------------------------- xml

    private fun prettyXml(input: String): String {
        val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance()
        // Hardening: this parses whatever the user pasted, so no DTDs and no external entities.
        runCatching { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { factory.setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        factory.isXIncludeAware = false
        factory.isExpandEntityReferences = false

        val document = factory.newDocumentBuilder()
            .parse(org.xml.sax.InputSource(java.io.StringReader(input.trim())))

        // Drop whitespace-only text nodes, otherwise the transformer has nothing to reindent.
        val blanks = javax.xml.xpath.XPathFactory.newInstance().newXPath()
            .evaluate("//text()[normalize-space()='']", document, javax.xml.xpath.XPathConstants.NODESET)
                as org.w3c.dom.NodeList
        for (i in 0 until blanks.length) {
            val node = blanks.item(i)
            node.parentNode?.removeChild(node)
        }

        val transformerFactory = javax.xml.transform.TransformerFactory.newInstance()
        runCatching { transformerFactory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_DTD, "") }
        runCatching { transformerFactory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "") }

        val transformer = transformerFactory.newTransformer()
        transformer.setOutputProperty(javax.xml.transform.OutputKeys.INDENT, "yes")
        runCatching { transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2") }

        val writer = java.io.StringWriter()
        transformer.transform(
            javax.xml.transform.dom.DOMSource(document),
            javax.xml.transform.stream.StreamResult(writer),
        )
        return writer.toString().trim()
    }
}
