package com.devutility.ai.parse

import com.devutility.ai.llm.HttpJson.asArray
import com.devutility.ai.llm.HttpJson.asDouble
import com.devutility.ai.llm.HttpJson.asInt
import com.devutility.ai.llm.HttpJson.asString
import com.devutility.ai.llm.HttpJson.field
import com.devutility.ai.model.CodeExplanation
import com.devutility.ai.model.CodeFlow
import com.devutility.ai.model.CodeLocation
import com.devutility.ai.model.CodeSuggestion
import com.devutility.ai.model.ErrorAnalysis
import com.devutility.ai.model.FixSuggestion
import com.devutility.ai.model.FlowNode
import com.devutility.ai.model.RepositoryAnswer
import com.devutility.json.JsonParser
import com.devutility.json.JsonSyntaxException
import com.devutility.json.JsonValue

/**
 * Reads the structured part of a model reply.
 *
 * Models wrap JSON in prose or fences no matter what the prompt says, so the object is
 * extracted before parsing. Every field is optional: a reply missing half its keys still
 * yields a usable result rather than an exception, and a reply that is not JSON at all falls
 * back to the raw text (§18, §33).
 */
object AiResponseParser {

    data class Outcome<T>(val value: T?, val rawText: String, val structured: Boolean)

    /**
     * Pulls the outermost balanced JSON object out of [content].
     *
     * Brace counting is string-aware, so a `{` inside a string literal — common in
     * `suggestedCode` — does not throw the balance off.
     */
    fun extractJsonObject(content: String): String? {
        val start = content.indexOf('{')
        if (start < 0) return null

        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until content.length) {
            val c = content[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return content.substring(start, i + 1)
                }
            }
        }
        return null
    }

    private fun root(content: String): JsonValue? {
        val candidate = extractJsonObject(content) ?: return null
        return try {
            JsonParser.parse(candidate, lenient = true)
        } catch (e: JsonSyntaxException) {
            null
        }
    }

    // ------------------------------------------------------------------ results

    fun repositoryAnswer(content: String): Outcome<RepositoryAnswer> {
        val json = root(content) ?: return Outcome(null, content, false)
        return Outcome(
            RepositoryAnswer(
                summary = json.field("summary").asString() ?: "",
                detail = json.field("detail").asString() ?: "",
                locations = locations(json.field("locations")),
            ),
            content,
            true,
        )
    }

    fun explanation(content: String): Outcome<CodeExplanation> {
        val json = root(content) ?: return Outcome(null, content, false)
        return Outcome(
            CodeExplanation(
                summary = json.field("summary").asString() ?: "",
                howItWorks = json.field("howItWorks").asString() ?: "",
                dependencies = strings(json.field("dependencies")),
                risks = strings(json.field("risks")),
            ),
            content,
            true,
        )
    }

    fun suggestion(content: String): Outcome<CodeSuggestion> {
        val json = root(content) ?: return Outcome(null, content, false)
        return Outcome(
            CodeSuggestion(
                problem = json.field("problem").asString() ?: "",
                recommendation = json.field("recommendation").asString() ?: "",
                suggestedCode = json.field("suggestedCode").asString()?.takeIf { it.isNotBlank() },
                reason = json.field("reason").asString() ?: "",
            ),
            content,
            true,
        )
    }

    fun errorAnalysis(content: String): Outcome<ErrorAnalysis> {
        val json = root(content) ?: return Outcome(null, content, false)

        // Accept 0..1 and 0..100 — models produce both no matter how the prompt is phrased.
        val rawConfidence = json.field("confidence").asDouble() ?: 0.0
        val confidence = if (rawConfidence > 1.0) rawConfidence / 100.0 else rawConfidence

        return Outcome(
            ErrorAnalysis(
                summary = json.field("summary").asString() ?: "",
                rootCause = json.field("rootCause").asString() ?: "",
                why = json.field("why").asString() ?: "",
                confidence = confidence.coerceIn(0.0, 1.0),
                likelyLocation = location(json.field("likelyLocation")),
                relatedLocations = locations(json.field("relatedLocations")),
                fixes = fixes(json.field("fixes")),
                insufficientContext = json.field("insufficientContext").asString()?.toBoolean() ?: false,
            ),
            content,
            true,
        )
    }

    fun codeFlow(content: String): Outcome<CodeFlow> {
        val json = root(content) ?: return Outcome(null, content, false)
        val nodes = json.field("nodes").asArray().mapNotNull { node ->
            val symbol = node.field("symbol").asString() ?: return@mapNotNull null
            FlowNode(
                symbol = symbol,
                location = location(node),
                description = node.field("description").asString(),
            )
        }
        return Outcome(
            CodeFlow(summary = json.field("summary").asString() ?: "", nodes = nodes),
            content,
            true,
        )
    }

    // ------------------------------------------------------------------ pieces

    private fun strings(value: JsonValue?): List<String> =
        value.asArray().mapNotNull { it.asString() }.filter { it.isNotBlank() }

    private fun locations(value: JsonValue?): List<CodeLocation> =
        value.asArray().mapNotNull { location(it) }

    /**
     * Locations always come back with `verified = false`. Only the resolver, after confirming
     * the file and line exist in the project, may mark one verified (§13, §16).
     */
    private fun location(value: JsonValue?): CodeLocation? {
        if (value == null) return null
        val path = value.field("filePath").asString()
            ?: value.field("file").asString()
            ?: value.field("path").asString()
            ?: return null
        if (path.isBlank()) return null
        return CodeLocation(
            filePath = path.trim(),
            line = value.field("line").asInt() ?: 0,
            column = value.field("column").asInt() ?: 0,
            symbol = value.field("symbol").asString(),
            description = value.field("description").asString(),
            verified = false,
        )
    }

    private fun fixes(value: JsonValue?): List<FixSuggestion> = value.asArray().mapNotNull { fix ->
        val path = fix.field("filePath").asString() ?: fix.field("file").asString() ?: return@mapNotNull null
        val replacement = fix.field("replacement").asString() ?: return@mapNotNull null
        val startLine = fix.field("startLine").asInt() ?: return@mapNotNull null
        FixSuggestion(
            filePath = path.trim(),
            startLine = startLine,
            startColumn = fix.field("startColumn").asInt() ?: 0,
            endLine = fix.field("endLine").asInt() ?: startLine,
            endColumn = fix.field("endColumn").asInt() ?: 0,
            replacement = replacement,
            explanation = fix.field("explanation").asString() ?: "",
            originalText = fix.field("originalText").asString(),
        )
    }
}
