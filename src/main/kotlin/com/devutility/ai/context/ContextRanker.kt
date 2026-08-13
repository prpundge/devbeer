package com.devutility.ai.context

import com.devutility.ai.settings.ContextBudget

/** What kind of thing a candidate is. Drives the base score. */
enum class MatchKind(val score: Int) {
    EXACT_SYMBOL(100),
    EXACT_METHOD(80),
    CLASS_NAME(70),
    REFERENCE(60),
    TEXT(40),
}

/** Extra signals that stack on top of the base score. */
enum class Signal(val score: Int) {
    SAME_PACKAGE(20),
    RELATED_TEST(20),
    CONFIGURATION_RELATIONSHIP(20),
    SPRING_COMPONENT(15),
    STACK_FRAME(50),
}

/**
 * One thing that might be worth sending.
 *
 * [text] is the source that would be included; [characters] is what it costs against the
 * budget.
 */
data class ContextCandidate(
    val path: String,
    val fileName: String,
    val symbol: String? = null,
    val kind: MatchKind = MatchKind.TEXT,
    val signals: Set<Signal> = emptySet(),
    val line: Int = 0,
    val text: String = "",
    val isMethod: Boolean = false,
) {
    val characters: Int get() = text.length
}

data class RankedContext(
    val candidate: ContextCandidate,
    val score: Int,
)

data class SelectionResult(
    val selected: List<RankedContext>,
    val droppedForBudget: Int,
    val totalCharacters: Int,
) {
    val fileCount: Int get() = selected.map { it.candidate.path }.distinct().size
    val truncated: Boolean get() = droppedForBudget > 0
}

/**
 * Deterministic relevance scoring.
 *
 * Deliberately arithmetic rather than learned: the same query must produce the same context
 * every time, and a developer must be able to read the score and understand why a file was
 * chosen. Embeddings are explicitly out of scope for v1 (§7).
 */
object ContextRanker {

    fun score(candidate: ContextCandidate): Int =
        candidate.kind.score + candidate.signals.sumOf { it.score }

    fun rank(candidates: List<ContextCandidate>): List<RankedContext> =
        candidates
            .map { RankedContext(it, score(it)) }
            // Ties broken by path then symbol so ordering is stable across runs.
            .sortedWith(
                compareByDescending<RankedContext> { it.score }
                    .thenBy { it.candidate.path }
                    .thenBy { it.candidate.symbol ?: "" },
            )

    /**
     * Ranks, then takes as much as the budget allows.
     *
     * Stops at the first ceiling reached, and reports what was left out — silently truncating
     * would make a partial answer look complete (§8).
     */
    fun select(candidates: List<ContextCandidate>, budget: ContextBudget): SelectionResult {
        val ranked = rank(candidates)
        val selected = mutableListOf<RankedContext>()
        val files = LinkedHashSet<String>()
        var characters = 0
        var methods = 0
        var dropped = 0

        for (entry in ranked) {
            val c = entry.candidate
            val newFile = c.path !in files

            val wouldExceed = when {
                newFile && files.size >= budget.maxFiles -> true
                characters + c.characters > budget.maxCharacters -> true
                c.isMethod && methods >= budget.maxMethods -> true
                else -> false
            }

            if (wouldExceed) {
                dropped++
                continue
            }

            selected += entry
            if (newFile) files += c.path
            characters += c.characters
            if (c.isMethod) methods++
        }

        return SelectionResult(selected, dropped, characters)
    }

    /**
     * Splits a natural-language query into search terms, including the camel-case parts of
     * any identifier so "customerValidation" also searches "customer" and "validation" (§7).
     */
    fun searchTerms(query: String): List<String> {
        val raw = Regex("[A-Za-z_][A-Za-z0-9_]*").findAll(query).map { it.value }.toList()
        val expanded = LinkedHashSet<String>()
        for (word in raw) {
            if (word.lowercase() in STOP_WORDS) continue
            expanded += word
            splitCamel(word).forEach { part ->
                if (part.length >= 3 && part.lowercase() !in STOP_WORDS) expanded += part
            }
        }
        return expanded.toList()
    }

    private fun splitCamel(s: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        for (c in s) {
            if (c == '_' || c == '-') {
                if (sb.isNotEmpty()) { out += sb.toString(); sb.clear() }
                continue
            }
            if (sb.isNotEmpty() && c.isUpperCase() && sb.last().isLowerCase()) {
                out += sb.toString()
                sb.clear()
            }
            sb.append(c)
        }
        if (sb.isNotEmpty()) out += sb.toString()
        return out
    }

    private val STOP_WORDS = setOf(
        "the", "a", "an", "is", "are", "was", "were", "be", "been", "being",
        "how", "what", "where", "when", "why", "who", "which",
        "does", "do", "did", "can", "could", "should", "would", "will",
        "in", "on", "at", "to", "for", "of", "with", "from", "by", "and", "or", "not",
        "it", "its", "this", "that", "these", "those", "i", "we", "you",
        "code", "implemented", "implementation", "handled", "work", "works", "used", "using",
        "my", "our", "project", "repository", "repo", "please", "show", "me", "find",
    )
}
