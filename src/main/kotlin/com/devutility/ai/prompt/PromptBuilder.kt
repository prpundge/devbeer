package com.devutility.ai.prompt

import com.devutility.ai.context.ContextSanitizer
import com.devutility.ai.context.RankedContext
import com.devutility.ai.model.AiResultType
import com.devutility.ai.model.LlmMessage
import com.devutility.ai.model.ParsedError
import com.devutility.ai.model.Role

/**
 * System prompts, one per task.
 *
 * Kept short and constant so they compress well and behave predictably. The anti-fabrication
 * clause is shared because every task needs it — a model that invents a file path produces a
 * navigation link that opens nothing (§19).
 */
object PromptTemplates {

    /** Appended to every system prompt. */
    const val GROUND_RULES = """
Rules:
- Do not invent files, classes, methods or line numbers. Use only what appears in the context.
- Do not assume APIs that are not shown.
- If the context is insufficient to answer confidently, say so and set insufficientContext to true.
- Prefer naming a specific file and line from the context over a general explanation.
Reply with a single JSON object and nothing else."""

    val REPOSITORY_QUESTION = """
You are a code assistant answering questions about one repository.
Answer only from the supplied context.

JSON shape:
{"summary": string, "detail": string,
 "locations": [{"filePath": string, "line": number, "symbol": string, "description": string}],
 "insufficientContext": boolean}
$GROUND_RULES""".trim()

    val CODE_EXPLANATION = """
You are a code assistant explaining a fragment of code to an experienced developer.
Be concrete and brief. Do not restate the code line by line.

JSON shape:
{"summary": string, "howItWorks": string,
 "dependencies": [string], "risks": [string], "insufficientContext": boolean}
$GROUND_RULES""".trim()

    val CODE_IMPROVEMENT = """
You are a code reviewer suggesting one focused improvement.
Only suggest a change you can justify from the code shown. If the code is fine, say so.

JSON shape:
{"problem": string, "recommendation": string, "suggestedCode": string, "reason": string,
 "insufficientContext": boolean}
suggestedCode must be a drop-in replacement for the selected code, nothing more.
$GROUND_RULES""".trim()

    val ERROR_ANALYSIS = """
You are diagnosing a failure in one repository.
Identify the root cause, not a restatement of the message.

JSON shape:
{"summary": string, "rootCause": string, "why": string, "confidence": number between 0 and 1,
 "likelyLocation": {"filePath": string, "line": number},
 "relatedLocations": [{"filePath": string, "line": number, "description": string}],
 "fixes": [{"filePath": string, "startLine": number, "startColumn": number,
            "endLine": number, "endColumn": number,
            "originalText": string, "replacement": string, "explanation": string}],
 "insufficientContext": boolean}
Set confidence honestly. A low confidence with a correct caveat is more useful than a
confident guess. originalText must be copied exactly from the supplied source.
$GROUND_RULES""".trim()

    val CODE_FLOW = """
You are describing the call flow of one method in this repository.
Order the nodes from entry point to terminal call.

JSON shape:
{"summary": string,
 "nodes": [{"symbol": string, "filePath": string, "line": number, "description": string}],
 "insufficientContext": boolean}
$GROUND_RULES""".trim()

    fun systemPrompt(type: AiResultType): String = when (type) {
        AiResultType.REPOSITORY_ANSWER -> REPOSITORY_QUESTION
        AiResultType.CODE_EXPLANATION -> CODE_EXPLANATION
        AiResultType.CODE_SUGGESTION -> CODE_IMPROVEMENT
        AiResultType.ERROR_ANALYSIS -> ERROR_ANALYSIS
        AiResultType.CODE_FLOW -> CODE_FLOW
    }
}

/**
 * Assembles the user message from explicit, labelled sections (§19).
 *
 * Every piece of source passes through [ContextSanitizer] on the way in — this is the single
 * choke point where project text becomes an outbound payload, so the redaction happens here
 * rather than being left to each caller to remember.
 */
class PromptBuilder(private val type: AiResultType) {

    private val sections = LinkedHashMap<String, String>()
    private var redactions = 0

    fun projectContext(name: String, languageHint: String? = null) = apply {
        val text = buildString {
            append("Project: ").append(name)
            languageHint?.let { append('\n').append("Primary language: ").append(it) }
        }
        sections["PROJECT CONTEXT"] = text
    }

    fun error(parsed: ParsedError, maxStackTraceLines: Int) = apply {
        val text = buildString {
            append("Kind: ").append(parsed.kind.name).append('\n')
            parsed.exceptionType?.let { append("Type: ").append(it).append('\n') }
            parsed.message?.let { append("Message: ").append(it).append('\n') }
            if (parsed.frames.isNotEmpty()) {
                append("Stack (application frames first):\n")
                val ordered = parsed.frames.sortedBy { it.isLibraryFrame }
                ordered.take(maxStackTraceLines).forEach { f ->
                    append("  ").append(f.declaringClass).append('.').append(f.methodName)
                    f.fileName?.let { append(" (").append(it).append(':').append(f.line).append(')') }
                    append('\n')
                }
            }
        }
        addSanitized("ERROR", text)
    }

    fun sourceLocation(path: String, line: Int) = apply {
        sections["SOURCE LOCATION"] = if (line > 0) "$path:$line" else path
    }

    fun selectedCode(code: String, language: String? = null) = apply {
        addSanitized("SELECTED CODE", fence(code, language))
    }

    fun relevantCode(entries: List<RankedContext>) = apply {
        if (entries.isEmpty()) return@apply
        val text = buildString {
            for (entry in entries) {
                val c = entry.candidate
                append("--- ").append(c.path)
                if (c.line > 0) append(':').append(c.line)
                c.symbol?.let { append("  [").append(it).append(']') }
                append("  (relevance ").append(entry.score).append(")\n")
                if (c.text.isNotBlank()) append(c.text.trimEnd()).append("\n\n")
            }
        }
        addSanitized("RELEVANT CODE", text)
    }

    fun relatedFiles(paths: List<String>) = apply {
        if (paths.isEmpty()) return@apply
        sections["RELATED FILES"] = paths.joinToString("\n") { "- $it" }
    }

    fun task(text: String) = apply {
        sections["TASK"] = text
    }

    fun note(text: String) = apply {
        sections["NOTES"] = text
    }

    /** Number of secret-shaped values redacted while building. Surfaced in the UI. */
    fun redactionCount(): Int = redactions

    fun build(): List<LlmMessage> = listOf(
        LlmMessage(Role.SYSTEM, PromptTemplates.systemPrompt(type)),
        LlmMessage(Role.USER, userMessage()),
    )

    fun userMessage(): String = sections.entries.joinToString("\n\n") { (heading, body) ->
        "$heading\n${"-".repeat(heading.length)}\n${body.trimEnd()}"
    }

    private fun addSanitized(heading: String, text: String) {
        val result = ContextSanitizer.sanitize(text)
        redactions += result.redactions
        sections[heading] = result.text
    }

    private fun fence(code: String, language: String?): String =
        "```${language.orEmpty()}\n${code.trimEnd()}\n```"
}
