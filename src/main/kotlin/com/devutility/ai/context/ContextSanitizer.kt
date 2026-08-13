package com.devutility.ai.context

/**
 * Redacts credential-shaped text before anything is transmitted.
 *
 * This is the last line of defence, not the first: [ContextExclusionService] has already kept
 * whole secret files out. This catches the hardcoded key in an otherwise ordinary source
 * file, which is the case that actually leaks (§9, §24).
 *
 * Everything replaced becomes [REDACTED] so the model still sees the shape of the code and
 * can reason about it, without receiving the value.
 */
object ContextSanitizer {

    const val REDACTED = "«redacted»"

    private data class Rule(val name: String, val regex: Regex, val replacement: (MatchResult) -> String)

    /**
     * Key-ish assignment: `apiKey = "..."`, `password: '...'`, `secret=...`.
     *
     * The negative lookahead matters: for `Authorization: Bearer <token>` the value group would
     * otherwise stop at the space and capture only "Bearer", redacting the label and leaving the
     * actual token in the text. The authorization rule handles that shape instead.
     */
    private val ASSIGNMENT = Regex(
        """(?i)\b([\w.\-]*(?:api[_\-]?key|secret|password|passwd|pwd|token|credential|private[_\-]?key|access[_\-]?key|auth)[\w.\-]*)\s*[:=]\s*(?!bearer\b|basic\b)(["']?)([^\s"',;)}]{4,})\2""",
    )

    /**
     * Order is load-bearing. Specific shapes run before the generic assignment rule, so a
     * narrow rule claims its match before a broad one can redact the label and orphan the value.
     */
    private val RULES: List<Rule> = listOf(
        // Bearer / Basic authorization values.
        Rule("authorization", Regex("""(?i)\b(bearer|basic)\s+[A-Za-z0-9+/=._\-]{8,}""")) { m ->
            "${m.groupValues[1]} $REDACTED"
        },

        // Well-known provider key shapes, matched literally so they are caught even in prose.
        Rule("openai", Regex("""\bsk-[A-Za-z0-9_\-]{16,}""")) { REDACTED },
        Rule("github", Regex("""\bgh[pousr]_[A-Za-z0-9]{20,}""")) { REDACTED },
        Rule("jetbrains", Regex("""\bperm-[A-Za-z0-9+/=]{4,}\.[A-Za-z0-9+/=]{4,}\.[A-Za-z0-9]{8,}""")) { REDACTED },
        Rule("aws", Regex("""\b(?:AKIA|ASIA)[0-9A-Z]{16}\b""")) { REDACTED },
        Rule("slack", Regex("""\bxox[baprs]-[A-Za-z0-9\-]{10,}""")) { REDACTED },
        Rule("google", Regex("""\bAIza[0-9A-Za-z_\-]{35}\b""")) { REDACTED },

        // Anything that looks like a private key block, including its body.
        Rule(
            "pem",
            Regex("""-----BEGIN [A-Z ]*PRIVATE KEY-----[\s\S]*?-----END [A-Z ]*PRIVATE KEY-----"""),
        ) { REDACTED },

        // Credentials embedded in a connection URL: scheme://user:pass@host
        Rule("url-credentials", Regex("""(?i)\b([a-z][a-z0-9+.\-]*://)([^\s:/@]+):([^\s@]+)@""")) { m ->
            "${m.groupValues[1]}${m.groupValues[2]}:$REDACTED@"
        },

        // JWTs.
        Rule("jwt", Regex("""\beyJ[A-Za-z0-9_\-]{8,}\.[A-Za-z0-9_\-]{8,}\.[A-Za-z0-9_\-]{8,}""")) { REDACTED },

        // Generic key=value last, so the rules above get first claim on their shapes.
        Rule("assignment", ASSIGNMENT) { m -> "${m.groupValues[1]}=$REDACTED" },
    )

    data class Result(val text: String, val redactions: Int, val rulesTriggered: List<String>)

    /** Redacts [text], reporting how much was removed so the UI can say so. */
    fun sanitize(text: String): Result {
        if (text.isEmpty()) return Result(text, 0, emptyList())

        var current = text
        var count = 0
        val triggered = mutableListOf<String>()

        for (rule in RULES) {
            var ruleHits = 0
            current = rule.regex.replace(current) { match ->
                ruleHits++
                rule.replacement(match)
            }
            if (ruleHits > 0) {
                count += ruleHits
                triggered += rule.name
            }
        }
        return Result(current, count, triggered)
    }

    /** Convenience for callers that only need the cleaned text. */
    fun clean(text: String): String = sanitize(text).text

    /** True when [text] contains something that would be redacted. */
    fun containsSecret(text: String): Boolean = sanitize(text).redactions > 0
}
