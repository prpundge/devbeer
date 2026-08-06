package com.devutility.props

/**
 * One resolved configuration key. [line] is 1-based so it can be handed straight to
 * navigation; 0 means "unknown".
 */
data class PropertyEntry(
    val key: String,
    val value: String,
    val line: Int,
)

enum class PropertyFormat(val label: String) {
    PROPERTIES("properties"),
    YAML("yaml"),
    UNKNOWN("text");

    companion object {
        fun forExtension(extension: String?): PropertyFormat = when (extension?.lowercase()) {
            "properties", "env", "conf" -> PROPERTIES
            "yml", "yaml" -> YAML
            else -> UNKNOWN
        }
    }
}

/**
 * A configuration file flattened into a dotted key space. Both `.properties` and YAML
 * end up in the same shape, which is what makes cross-format comparison possible.
 */
class PropertySnapshot(
    val displayName: String,
    val path: String,
    val format: PropertyFormat,
    val entries: Map<String, PropertyEntry>,
) {
    val keys: Set<String> get() = entries.keys

    fun value(key: String): String? = entries[key]?.value

    fun line(key: String): Int = entries[key]?.line ?: 0

    /** Keys that start with `prefix.`, useful for `@ConfigurationProperties`. */
    fun withPrefix(prefix: String): Map<String, PropertyEntry> {
        val p = "$prefix."
        return entries.filterKeys { it.startsWith(p) }
    }

    companion object {
        val EMPTY = PropertySnapshot("", "", PropertyFormat.UNKNOWN, emptyMap())
    }
}
