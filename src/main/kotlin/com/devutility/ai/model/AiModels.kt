package com.devutility.ai.model

/**
 * Data model for the AI layer.
 *
 * Everything here is plain Kotlin with no IntelliJ or network types, so the engine, the
 * prompt builder, the response parser and the fix validator can all be unit-tested without a
 * platform fixture or a live model.
 */

// ---------------------------------------------------------------- provider transport

enum class ProviderKind(val displayName: String) {
    /** Any local HTTP server speaking the Ollama API — Ollama, and anything mimicking it. */
    LOCAL("Local LLM"),

    /** Any endpoint speaking the OpenAI chat-completions API. */
    OPENAI_COMPATIBLE("OpenAI Compatible"),
}

enum class Role { SYSTEM, USER }

data class LlmMessage(val role: Role, val content: String)

data class LlmRequest(
    val messages: List<LlmMessage>,
    val model: String,
    /** Low by default: these are analysis tasks, not creative ones. */
    val temperature: Double = 0.1,
    val timeoutSeconds: Int = 60,
    /** Ask the provider for a JSON object when it supports the hint. */
    val expectJson: Boolean = true,
) {
    /** Rough character count of everything that would be transmitted. Used for logging only. */
    val approximateSize: Int get() = messages.sumOf { it.content.length }
}

data class LlmResponse(
    val content: String,
    val model: String,
    val providerName: String,
    val durationMillis: Long,
)

/**
 * Why a request could not be completed. Carried as a value rather than thrown so the UI can
 * render a specific, actionable message instead of a stack trace.
 */
data class LlmFailure(
    val kind: Kind,
    val providerName: String,
    val detail: String,
) {
    enum class Kind {
        NOT_CONFIGURED,
        UNREACHABLE,
        TIMEOUT,
        AUTH,
        RATE_LIMITED,
        BAD_RESPONSE,
        CANCELLED,
        OTHER,
    }

    /** A sentence for the user. Never contains credentials or source text. */
    fun userMessage(): String = when (kind) {
        Kind.NOT_CONFIGURED -> "DevBeer AI is not configured yet."
        Kind.UNREACHABLE -> "Could not reach $providerName. $detail"
        Kind.TIMEOUT -> "$providerName did not respond in time."
        Kind.AUTH -> "$providerName rejected the credentials."
        Kind.RATE_LIMITED -> "$providerName is rate limiting requests. Try again shortly."
        Kind.BAD_RESPONSE -> "The AI response could not be interpreted."
        Kind.CANCELLED -> "Cancelled."
        Kind.OTHER -> detail
    }
}

/** Success or failure without exceptions crossing layers. */
sealed class LlmResult {
    data class Success(val response: LlmResponse) : LlmResult()
    data class Failure(val failure: LlmFailure) : LlmResult()
}

// ---------------------------------------------------------------- results

enum class AiResultType {
    REPOSITORY_ANSWER,
    CODE_EXPLANATION,
    CODE_SUGGESTION,
    ERROR_ANALYSIS,
    CODE_FLOW,
}

/**
 * A place in the project.
 *
 * [verified] is false until the plugin has confirmed the file and line actually exist. The UI
 * must never offer navigation for an unverified location — models invent file paths, and a
 * link that opens nothing is worse than no link (§13, §16).
 */
data class CodeLocation(
    val filePath: String,
    val line: Int,
    val column: Int = 0,
    val symbol: String? = null,
    val description: String? = null,
    val verified: Boolean = false,
) {
    fun display(): String {
        val name = filePath.substringAfterLast('/').substringAfterLast('\\')
        return if (line > 0) "$name:$line" else name
    }
}

data class RepositoryAnswer(
    val summary: String,
    val detail: String,
    val locations: List<CodeLocation> = emptyList(),
)

data class CodeExplanation(
    val summary: String,
    val howItWorks: String,
    val dependencies: List<String> = emptyList(),
    val risks: List<String> = emptyList(),
)

data class CodeSuggestion(
    val problem: String,
    val recommendation: String,
    val suggestedCode: String?,
    val reason: String,
    val fix: FixSuggestion? = null,
)

/**
 * A concrete, reviewable edit.
 *
 * Ranges are 1-based lines and 0-based columns. [originalText] is what the model believed it
 * was replacing; the validator refuses to apply the fix unless the document still matches it,
 * which is what stops a stale or hallucinated patch from corrupting a file (§17, §31).
 */
data class FixSuggestion(
    val filePath: String,
    val startLine: Int,
    val startColumn: Int,
    val endLine: Int,
    val endColumn: Int,
    val replacement: String,
    val explanation: String,
    val originalText: String? = null,
)

data class ErrorAnalysis(
    val summary: String,
    val rootCause: String,
    val why: String,
    val confidence: Double,
    val likelyLocation: CodeLocation? = null,
    val relatedLocations: List<CodeLocation> = emptyList(),
    val fixes: List<FixSuggestion> = emptyList(),
    /** Set when the model reported that the evidence was not sufficient (§33). */
    val insufficientContext: Boolean = false,
) {
    val confidencePercent: Int get() = (confidence.coerceIn(0.0, 1.0) * 100).toInt()
}

data class FlowNode(
    val symbol: String,
    val location: CodeLocation?,
    val description: String? = null,
)

data class CodeFlow(
    val summary: String,
    val nodes: List<FlowNode>,
)

// ---------------------------------------------------------------- parsed errors

enum class ErrorKind {
    JAVA_EXCEPTION,
    SPRING_STARTUP,
    COMPILATION,
    INSPECTION,
    TEST_FAILURE,
    BUILD_FAILURE,
    UNKNOWN,
}

/** One frame of a stack trace, as parsed from text. */
data class StackFrame(
    val declaringClass: String,
    val methodName: String,
    val fileName: String?,
    val line: Int,
) {
    /** Frames in libraries are noise when looking for the developer's own bug. */
    val isLibraryFrame: Boolean
        get() = LIBRARY_PREFIXES.any { declaringClass.startsWith(it) }

    private companion object {
        val LIBRARY_PREFIXES = listOf(
            "java.", "javax.", "jdk.", "sun.", "com.sun.",
            "kotlin.", "kotlinx.",
            "org.junit.", "junit.", "org.gradle.", "worker.org.gradle.",
            "org.springframework.", "org.apache.catalina.", "org.apache.tomcat.",
            "org.mockito.", "net.bytebuddy.",
        )
    }
}

/**
 * The structured form of whatever the user pasted or right-clicked. Produced without any
 * network call, so a malformed error still yields something the resolver can work with.
 */
data class ParsedError(
    val kind: ErrorKind,
    val exceptionType: String?,
    val message: String?,
    val frames: List<StackFrame> = emptyList(),
    val fileName: String? = null,
    val line: Int = 0,
    val column: Int = 0,
    val raw: String = "",
) {
    /** The first frame that looks like project code — the usual place a fix belongs. */
    val firstApplicationFrame: StackFrame? get() = frames.firstOrNull { !it.isLibraryFrame }

    val hasLocation: Boolean get() = fileName != null || frames.isNotEmpty()
}
