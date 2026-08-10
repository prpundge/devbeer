package com.devutility.props

enum class DiffStatus(val label: String) {
    SAME("Identical"),
    DIFFERENT("Different"),
    ONLY_LEFT("Only left"),
    ONLY_RIGHT("Only right"),
}

data class DiffRow(
    val key: String,
    val left: String?,
    val right: String?,
    val leftLine: Int,
    val rightLine: Int,
    val status: DiffStatus,
)

data class DiffOptions(
    val ignoreCase: Boolean = false,
    val trimValues: Boolean = true,
)

data class DiffResult(
    val rows: List<DiffRow>,
    val leftName: String,
    val rightName: String,
) {
    val same: Int = rows.count { it.status == DiffStatus.SAME }
    val different: Int = rows.count { it.status == DiffStatus.DIFFERENT }
    val onlyLeft: Int = rows.count { it.status == DiffStatus.ONLY_LEFT }
    val onlyRight: Int = rows.count { it.status == DiffStatus.ONLY_RIGHT }
    val total: Int = rows.size
}

object PropertyDiffEngine {

    fun diff(left: PropertySnapshot, right: PropertySnapshot, options: DiffOptions = DiffOptions()): DiffResult {
        // Left order first so the table reads like the left-hand file, then whatever
        // only the right side has, appended in its own order.
        val keys = LinkedHashSet<String>(left.keys.size + right.keys.size)
        keys += left.keys
        keys += right.keys

        val rows = keys.map { key ->
            val l = left.value(key)
            val r = right.value(key)
            val status = when {
                l == null -> DiffStatus.ONLY_RIGHT
                r == null -> DiffStatus.ONLY_LEFT
                equal(l, r, options) -> DiffStatus.SAME
                else -> DiffStatus.DIFFERENT
            }
            DiffRow(key, l, r, left.line(key), right.line(key), status)
        }

        return DiffResult(rows, left.displayName, right.displayName)
    }

    private fun equal(a: String, b: String, options: DiffOptions): Boolean {
        val x = if (options.trimValues) a.trim() else a
        val y = if (options.trimValues) b.trim() else b
        return x.equals(y, ignoreCase = options.ignoreCase)
    }

    /**
     * Both sides rendered into the same normalized, key-sorted shape. Feeding these to the
     * IDE diff viewer makes a YAML file and a `.properties` file line up row for row.
     */
    fun normalize(snapshot: PropertySnapshot, keys: List<String>): String = buildString {
        append("# ").append(snapshot.displayName).append("  (normalized by DevBeer)\n")
        append("# ").append(snapshot.entries.size).append(" keys, source format: ")
            .append(snapshot.format.label).append("\n\n")
        for (key in keys) {
            val value = snapshot.value(key)
            if (value == null) {
                append("# (absent) ").append(key).append('\n')
            } else {
                append(key).append(" = ").append(value.replace("\n", "\\n")).append('\n')
            }
        }
    }

    fun report(result: DiffResult, rows: List<DiffRow>): String = buildString {
        append("Property comparison\n")
        append("  Left : ").append(result.leftName).append('\n')
        append("  Right: ").append(result.rightName).append("\n\n")
        append("  ").append(result.total).append(" keys total — ")
            .append(result.same).append(" identical, ")
            .append(result.different).append(" different, ")
            .append(result.onlyLeft).append(" only left, ")
            .append(result.onlyRight).append(" only right\n\n")

        for (status in DiffStatus.values()) {
            val bucket = rows.filter { it.status == status }
            if (bucket.isEmpty()) continue
            append(status.label).append(" (").append(bucket.size).append(")\n")
            for (row in bucket) {
                when (status) {
                    DiffStatus.SAME -> append("  ").append(row.key).append(" = ").append(row.left).append('\n')
                    DiffStatus.DIFFERENT -> {
                        append("  ").append(row.key).append('\n')
                        append("      left : ").append(row.left).append('\n')
                        append("      right: ").append(row.right).append('\n')
                    }
                    DiffStatus.ONLY_LEFT -> append("  ").append(row.key).append(" = ").append(row.left).append('\n')
                    DiffStatus.ONLY_RIGHT -> append("  ").append(row.key).append(" = ").append(row.right).append('\n')
                }
            }
            append('\n')
        }
    }
}
