package com.devutility.ai.error

import com.devutility.ai.model.ErrorKind
import com.devutility.ai.model.ParsedError
import com.devutility.ai.model.StackFrame

/**
 * Turns whatever the user pasted or right-clicked into structured form.
 *
 * Runs entirely offline. An error that cannot be classified still yields a [ParsedError] with
 * `UNKNOWN` and the raw text, because a degraded analysis beats refusing to start one.
 */
object ErrorParser {

    // at com.example.PaymentService.create(PaymentService.java:81)
    // Modern JDKs prefix frames with the module: "at java.base/java.util.Objects.requireNonNull(...)",
    // and the module may carry a version: "at app@1.0/com.example.Foo.bar(...)". Missing that
    // prefix silently drops every JDK frame from the trace.
    private val FRAME = Regex(
        """^\s*at\s+(?:[\w.$]+(?:@[\w.+\-]+)?/)?([\w.$<>]+)\.([\w$<>]+)\s*\(([^)]*)\)\s*$""",
    )

    // com.example.FooException: message
    private val EXCEPTION_HEAD = Regex(
        """^\s*(?:Caused by:\s*|Suppressed:\s*)?([\w.$]*(?:Exception|Error|Throwable))(?::\s*(.*))?$""",
    )

    // /path/Foo.java:42: error: cannot find symbol      (javac)
    private val JAVAC = Regex("""^(.+?):(\d+):\s*(?:error|warning):\s*(.*)$""")

    // Foo.java:[42,17] cannot find symbol               (Gradle/Maven)
    private val BRACKET = Regex("""^(.+?):\[(\d+)(?:,(\d+))?]\s*(.*)$""")

    // Foo.kt:42:17: error: ...                          (kotlinc)
    private val KOTLINC = Regex("""^(.+?):(\d+):(\d+):\s*(?:error|warning):\s*(.*)$""")

    private val SPRING_MARKERS = listOf(
        "APPLICATION FAILED TO START",
        "UnsatisfiedDependencyException",
        "NoSuchBeanDefinitionException",
        "BeanCreationException",
        "BeanDefinitionStoreException",
        "ApplicationContextException",
        "ConfigDataResourceNotFoundException",
        "Parameter 0 of constructor",
        "Consider defining a bean",
        "required a bean of type",
    )

    private val TEST_MARKERS = listOf(
        "org.junit.ComparisonFailure",
        "org.opentest4j.AssertionFailedError",
        "java.lang.AssertionError",
        "expected:",
        "Tests run:",
        "FAILED",
    )

    private val BUILD_MARKERS = listOf(
        "FAILURE: Build failed",
        "Execution failed for task",
        "BUILD FAILURE",
        "Could not resolve",
    )

    /**
     * @param fallbackKind used when the text is neither a stack trace nor a compiler
     *   diagnostic. The editor action passes [ErrorKind.INSPECTION] because text selected in a
     *   source file and sent here is almost always an inspection message — which has no
     *   machine-readable shape of its own to detect (§15).
     */
    fun parse(
        raw: String,
        maxStackTraceLines: Int = 100,
        fallbackKind: ErrorKind = ErrorKind.UNKNOWN,
    ): ParsedError {
        if (raw.isBlank()) {
            return ParsedError(ErrorKind.UNKNOWN, null, null, raw = raw)
        }

        val lines = raw.lines()
        val frames = parseFrames(lines, maxStackTraceLines)
        val head = findExceptionHead(lines)
        val compile = parseCompilerDiagnostic(lines)

        val kind = classify(raw, head?.first, frames, compile != null)
            .let { if (it == ErrorKind.UNKNOWN) fallbackKind else it }

        // A compiler diagnostic carries its own file/line; otherwise fall back to the first
        // frame that belongs to the application rather than a library.
        val appFrame = frames.firstOrNull { !it.isLibraryFrame } ?: frames.firstOrNull()

        return ParsedError(
            kind = kind,
            exceptionType = head?.first,
            message = compile?.message ?: head?.second ?: firstMeaningfulLine(lines),
            frames = frames,
            fileName = compile?.file ?: appFrame?.fileName,
            line = compile?.line ?: appFrame?.line ?: 0,
            column = compile?.column ?: 0,
            raw = raw,
        )
    }

    // ------------------------------------------------------------------ pieces

    private fun parseFrames(lines: List<String>, limit: Int): List<StackFrame> {
        val out = mutableListOf<StackFrame>()
        for (line in lines) {
            if (out.size >= limit) break
            val m = FRAME.find(line) ?: continue
            val declaringClass = m.groupValues[1]
            val method = m.groupValues[2]
            val location = m.groupValues[3]

            // "Foo.java:81", "Native Method", "Unknown Source"
            val file: String?
            val number: Int
            val colon = location.lastIndexOf(':')
            if (colon > 0 && location.substring(colon + 1).all { it.isDigit() }) {
                file = location.substring(0, colon).trim().ifEmpty { null }
                number = location.substring(colon + 1).toIntOrNull() ?: 0
            } else {
                file = null
                number = 0
            }
            out += StackFrame(declaringClass, method, file, number)
        }
        return out
    }

    private fun findExceptionHead(lines: List<String>): Pair<String, String?>? {
        // The last "Caused by:" is the proximate cause and is what the developer needs.
        val causes = lines.mapNotNull { line ->
            EXCEPTION_HEAD.find(line.trim())?.let { it.groupValues[1] to it.groupValues[2].ifBlank { null } }
        }
        if (causes.isEmpty()) return null
        val causedBy = lines.withIndex()
            .filter { it.value.trimStart().startsWith("Caused by:") }
            .mapNotNull { (_, line) ->
                EXCEPTION_HEAD.find(line.trim())?.let { it.groupValues[1] to it.groupValues[2].ifBlank { null } }
            }
        return causedBy.lastOrNull() ?: causes.first()
    }

    private data class Diagnostic(val file: String, val line: Int, val column: Int, val message: String)

    private fun parseCompilerDiagnostic(lines: List<String>): Diagnostic? {
        for (line in lines) {
            KOTLINC.find(line)?.let {
                return Diagnostic(
                    it.groupValues[1].trim(),
                    it.groupValues[2].toIntOrNull() ?: 0,
                    it.groupValues[3].toIntOrNull() ?: 0,
                    it.groupValues[4].trim(),
                )
            }
            BRACKET.find(line)?.let {
                return Diagnostic(
                    it.groupValues[1].trim(),
                    it.groupValues[2].toIntOrNull() ?: 0,
                    it.groupValues[3].toIntOrNull() ?: 0,
                    it.groupValues[4].trim(),
                )
            }
            JAVAC.find(line)?.let {
                // Guard against matching a stack frame or a Windows drive letter path.
                if (line.trimStart().startsWith("at ")) return@let
                return Diagnostic(
                    it.groupValues[1].trim(),
                    it.groupValues[2].toIntOrNull() ?: 0,
                    0,
                    it.groupValues[3].trim(),
                )
            }
        }
        return null
    }

    private fun classify(
        raw: String,
        exceptionType: String?,
        frames: List<StackFrame>,
        hasCompilerDiagnostic: Boolean,
    ): ErrorKind = when {
        SPRING_MARKERS.any { raw.contains(it, ignoreCase = false) } -> ErrorKind.SPRING_STARTUP
        hasCompilerDiagnostic && raw.contains("error:", ignoreCase = true) -> ErrorKind.COMPILATION
        hasCompilerDiagnostic -> ErrorKind.COMPILATION
        TEST_MARKERS.any { raw.contains(it) } && frames.isNotEmpty() -> ErrorKind.TEST_FAILURE
        BUILD_MARKERS.any { raw.contains(it) } -> ErrorKind.BUILD_FAILURE
        exceptionType != null || frames.isNotEmpty() -> ErrorKind.JAVA_EXCEPTION
        else -> ErrorKind.UNKNOWN
    }

    private fun firstMeaningfulLine(lines: List<String>): String? =
        lines.map { it.trim() }
            .firstOrNull { it.isNotEmpty() && !it.startsWith("at ") }
            ?.take(500)
}
