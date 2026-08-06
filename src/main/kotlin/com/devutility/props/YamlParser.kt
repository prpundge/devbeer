package com.devutility.props

/**
 * Flattens YAML into the same dotted key space `.properties` files live in, so the two
 * formats can be compared against each other.
 *
 * This is deliberately a hand-written indentation parser rather than a full YAML engine:
 * it has no third-party dependency, it keeps line numbers, and it covers what actually
 * shows up in Spring Boot config — nested maps, sequences, multi-document files,
 * block scalars, flow collections, anchor-free plain scalars.
 *
 * Known limits: anchors/aliases (`&a` / `*a`) and explicit tags are kept verbatim as text,
 * and merge keys (`<<`) are not expanded.
 */
object YamlParser {

    private sealed class Frame {
        abstract val indent: Int
        abstract val path: String
    }

    private class MapFrame(override val indent: Int, override val path: String) : Frame()

    private class ListFrame(override val indent: Int, override val path: String) : Frame() {
        var count = 0
    }

    fun parse(text: String): LinkedHashMap<String, PropertyEntry> {
        val out = LinkedHashMap<String, PropertyEntry>()
        val lines = text.lines()
        val stack = ArrayDeque<Frame>()

        var i = 0
        while (i < lines.size) {
            val raw = lines[i]
            val lineNo = i + 1
            i++

            if (raw.isBlank()) continue
            val indent = raw.indexOfFirst { it != ' ' && it != '\t' }.let { if (it < 0) raw.length else it }
            val content = raw.substring(indent).trimEnd()
            if (content.startsWith("#")) continue

            // Document separators reset the scope. Spring uses them for per-profile blocks
            // inside a single file, and later documents win, which a plain merge gives us.
            if (content == "---" || content.startsWith("--- ") || content == "...") {
                stack.clear()
                continue
            }

            if (content == "-" || content.startsWith("- ")) {
                i = handleListItem(lines, i, indent, content, lineNo, out, stack)
            } else {
                val kv = splitKey(content) ?: continue
                popTo(stack, indent)
                val parent = stack.lastOrNull()?.path.orEmpty()
                i = emit(lines, i, indent, parent, kv.first, kv.second, lineNo, out, stack)
            }
        }
        return out
    }

    private fun handleListItem(
        lines: List<String>,
        nextIndex: Int,
        indent: Int,
        content: String,
        lineNo: Int,
        out: LinkedHashMap<String, PropertyEntry>,
        stack: ArrayDeque<Frame>,
    ): Int {
        // Drop anything nested deeper than this marker, plus any map scope that started at
        // the same column — but keep a sibling ListFrame so its counter keeps incrementing.
        while (stack.isNotEmpty() && stack.last().indent > indent) stack.removeLast()
        while (stack.isNotEmpty() && stack.last() is MapFrame && stack.last().indent >= indent) stack.removeLast()

        var list = stack.lastOrNull() as? ListFrame
        if (list == null || list.indent != indent) {
            list = ListFrame(indent, stack.lastOrNull()?.path.orEmpty())
            stack.addLast(list)
        }

        val elementPath = "${list.path}[${list.count}]"
        list.count++

        val rest = if (content == "-") "" else content.substring(2).trim()
        if (rest.isEmpty()) {
            // Keys of this element start at indent + 2; +1 keeps the frame from being popped by them.
            stack.addLast(MapFrame(indent + 1, elementPath))
            return nextIndex
        }

        val kv = splitKey(rest)
        if (kv == null) {
            out[elementPath] = PropertyEntry(elementPath, cleanScalar(rest), lineNo)
            return nextIndex
        }

        stack.addLast(MapFrame(indent + 1, elementPath))
        return emit(lines, nextIndex, indent + 2, elementPath, kv.first, kv.second, lineNo, out, stack)
    }

    /**
     * Records one `key: value` pair, pushing a scope instead when the value is a nested
     * block. Returns the index of the next unconsumed line (block scalars eat their body).
     */
    private fun emit(
        lines: List<String>,
        nextIndex: Int,
        ownerIndent: Int,
        parentPath: String,
        key: String,
        rawValue: String,
        lineNo: Int,
        out: LinkedHashMap<String, PropertyEntry>,
        stack: ArrayDeque<Frame>,
    ): Int {
        val path = if (parentPath.isEmpty()) key else "$parentPath.$key"
        val value = rawValue.trim()

        return when {
            value.isEmpty() -> {
                stack.addLast(MapFrame(ownerIndent, path))
                nextIndex
            }

            value.startsWith("|") || value.startsWith(">") -> {
                readBlockScalar(lines, nextIndex, ownerIndent, path, value.startsWith(">"), lineNo, out)
            }

            value.startsWith("[") -> {
                splitFlow(value.removePrefix("[").removeSuffix("]")).forEachIndexed { n, item ->
                    val p = "$path[$n]"
                    out[p] = PropertyEntry(p, cleanScalar(item), lineNo)
                }
                nextIndex
            }

            value.startsWith("{") -> {
                for (item in splitFlow(value.removePrefix("{").removeSuffix("}"))) {
                    val kv = splitKey(item) ?: continue
                    val p = "$path.${kv.first}"
                    out[p] = PropertyEntry(p, cleanScalar(kv.second), lineNo)
                }
                nextIndex
            }

            else -> {
                out[path] = PropertyEntry(path, cleanScalar(value), lineNo)
                nextIndex
            }
        }
    }

    private fun readBlockScalar(
        lines: List<String>,
        startIndex: Int,
        ownerIndent: Int,
        path: String,
        folded: Boolean,
        lineNo: Int,
        out: LinkedHashMap<String, PropertyEntry>,
    ): Int {
        val sb = StringBuilder()
        var base = -1
        var j = startIndex
        while (j < lines.size) {
            val line = lines[j]
            if (line.isBlank()) {
                sb.append('\n')
                j++
                continue
            }
            val ind = line.indexOfFirst { it != ' ' }
            if (ind <= ownerIndent) break
            if (base < 0) base = ind
            sb.append(line.substring(minOf(base, ind)))
            sb.append(if (folded) ' ' else '\n')
            j++
        }
        out[path] = PropertyEntry(path, sb.toString().trim(), lineNo)
        return j
    }

    private fun popTo(stack: ArrayDeque<Frame>, indent: Int) {
        while (stack.isNotEmpty() && stack.last().indent >= indent) stack.removeLast()
    }

    /**
     * Splits `key: value` on the first colon that is outside quotes and followed by a space
     * or end of line — so `url: http://host` keeps its scheme, and `a:b` is not a key.
     */
    private fun splitKey(s: String): Pair<String, String>? {
        var inSingle = false
        var inDouble = false
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '\'' && !inDouble -> inSingle = !inSingle
                c == '"' && !inSingle -> inDouble = !inDouble
                c == ':' && !inSingle && !inDouble && (i == s.length - 1 || s[i + 1] == ' ') -> {
                    val key = s.substring(0, i).trim().removeSurrounding("\"").removeSurrounding("'")
                    if (key.isEmpty()) return null
                    return key to s.substring(i + 1)
                }
            }
            i++
        }
        return null
    }

    /** Splits a flow collection body on top-level commas. */
    private fun splitFlow(body: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var depth = 0
        var inSingle = false
        var inDouble = false
        for (c in body) {
            when {
                c == '\'' && !inDouble -> { inSingle = !inSingle; sb.append(c) }
                c == '"' && !inSingle -> { inDouble = !inDouble; sb.append(c) }
                (c == '[' || c == '{') && !inSingle && !inDouble -> { depth++; sb.append(c) }
                (c == ']' || c == '}') && !inSingle && !inDouble -> { depth--; sb.append(c) }
                c == ',' && depth == 0 && !inSingle && !inDouble -> { out += sb.toString(); sb.clear() }
                else -> sb.append(c)
            }
        }
        if (sb.isNotBlank()) out += sb.toString()
        return out.map { it.trim() }.filter { it.isNotEmpty() }
    }

    /** Strips a trailing comment and surrounding quotes from a plain scalar. */
    private fun cleanScalar(raw: String): String {
        var s = raw.trim()
        if (s.isEmpty()) return s

        if (!s.startsWith("\"") && !s.startsWith("'")) {
            // A comment only starts at " #", so `http://host#frag` survives intact.
            val hash = s.indexOf(" #")
            if (hash >= 0) s = s.substring(0, hash).trim()
        }

        if (s.length >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            return unescapeDoubleQuoted(s.substring(1, s.length - 1))
        }
        if (s.length >= 2 && s.startsWith("'") && s.endsWith("'")) {
            return s.substring(1, s.length - 1).replace("''", "'")
        }
        return s
    }

    private fun unescapeDoubleQuoted(s: String): String {
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
                '0' -> sb.append(' ')
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

    // ---------------------------------------------------------------- rendering

    private sealed class Node
    private class MapNode : Node() {
        val children = LinkedHashMap<String, Node>()
    }
    private class ListNode : Node() {
        val items = ArrayList<Node?>()
    }
    private class LeafNode(val value: String) : Node()

    /** Rebuilds nested YAML from a flat dotted key space. The inverse of [parse]. */
    fun render(entries: Map<String, String>): String {
        val root = MapNode()
        for ((key, value) in entries) insert(root, parseSegments(key), value)
        val sb = StringBuilder()
        writeMap(root, 0, sb)
        return sb.toString()
    }

    private data class Segment(val name: String?, val index: Int?)

    private fun parseSegments(key: String): List<Segment> {
        val out = mutableListOf<Segment>()
        for (part in key.split('.')) {
            if (part.isEmpty()) continue
            val bracket = part.indexOf('[')
            if (bracket < 0) {
                out += Segment(part, null)
                continue
            }
            val name = part.substring(0, bracket)
            if (name.isNotEmpty()) out += Segment(name, null)
            Regex("\\[(\\d+)]").findAll(part.substring(bracket)).forEach {
                out += Segment(null, it.groupValues[1].toInt())
            }
        }
        return out
    }

    private fun insert(root: MapNode, segments: List<Segment>, value: String) {
        if (segments.isEmpty()) return
        var current: Node = root
        for ((idx, seg) in segments.withIndex()) {
            val last = idx == segments.size - 1
            if (seg.name != null) {
                val map = current as? MapNode ?: return
                if (last) {
                    map.children[seg.name] = LeafNode(value)
                } else {
                    val nextIsIndex = segments[idx + 1].name == null
                    val existing = map.children[seg.name]
                    val next = when {
                        nextIsIndex -> existing as? ListNode ?: ListNode().also { map.children[seg.name] = it }
                        else -> existing as? MapNode ?: MapNode().also { map.children[seg.name] = it }
                    }
                    current = next
                }
            } else {
                val list = current as? ListNode ?: return
                val i = seg.index ?: return
                while (list.items.size <= i) list.items.add(null)
                if (last) {
                    list.items[i] = LeafNode(value)
                } else {
                    val nextIsIndex = segments[idx + 1].name == null
                    val existing = list.items[i]
                    val next = when {
                        nextIsIndex -> existing as? ListNode ?: ListNode().also { list.items[i] = it }
                        else -> existing as? MapNode ?: MapNode().also { list.items[i] = it }
                    }
                    current = next
                }
            }
        }
    }

    private fun writeMap(node: MapNode, depth: Int, sb: StringBuilder) {
        val pad = "  ".repeat(depth)
        for ((key, child) in node.children) {
            when (child) {
                is LeafNode -> sb.append(pad).append(key).append(": ").append(quote(child.value)).append('\n')
                is MapNode -> {
                    sb.append(pad).append(key).append(":\n")
                    writeMap(child, depth + 1, sb)
                }
                is ListNode -> {
                    sb.append(pad).append(key).append(":\n")
                    writeList(child, depth + 1, sb)
                }
            }
        }
    }

    private fun writeList(node: ListNode, depth: Int, sb: StringBuilder) {
        val pad = "  ".repeat(depth)
        for (item in node.items) {
            when (item) {
                null -> sb.append(pad).append("- ~\n")
                is LeafNode -> sb.append(pad).append("- ").append(quote(item.value)).append('\n')
                is MapNode -> {
                    val nested = StringBuilder()
                    writeMap(item, depth + 1, nested)
                    val body = nested.toString().lines().filter { it.isNotBlank() }
                    if (body.isEmpty()) {
                        sb.append(pad).append("- {}\n")
                    } else {
                        sb.append(pad).append("- ").append(body.first().trimStart()).append('\n')
                        body.drop(1).forEach { sb.append(it).append('\n') }
                    }
                }
                is ListNode -> {
                    sb.append(pad).append("-\n")
                    writeList(item, depth + 1, sb)
                }
            }
        }
    }

    private val NEEDS_QUOTES = Regex("^\\s|\\s$|^[\\[{&*!|>%@`#-]|:\\s|\\s#|[\\n\"']")

    private fun quote(value: String): String {
        if (value.isEmpty()) return "\"\""
        if (NEEDS_QUOTES.containsMatchIn(value)) {
            val escaped = value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
            return "\"$escaped\""
        }
        return value
    }
}
