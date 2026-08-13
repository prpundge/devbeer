package com.devutility.ai.context

/**
 * Decides what may never leave the machine.
 *
 * Split from the sanitizer on purpose: this answers "may this *file* be read at all", the
 * sanitizer answers "is this *text* safe to send". A file can pass here and still have its
 * contents redacted (§9, §24).
 *
 * Pure string logic — no VFS types — so every rule is unit-testable.
 */
object ContextExclusionService {

    /** Generated, vendored or VCS directories. Nothing here is source the user wrote. */
    val EXCLUDED_DIRECTORIES: Set<String> = setOf(
        ".git", ".hg", ".svn", ".idea", ".gradle", ".mvn",
        "build", "out", "target", "bin",
        "node_modules", "dist", "coverage", "__pycache__",
        ".venv", "venv", ".next", ".nuxt", "vendor", ".terraform",
    )

    /** Files whose *entire contents* are presumed sensitive. */
    private val SECRET_FILE_PATTERNS: List<Regex> = listOf(
        Regex("""^\.env$""", RegexOption.IGNORE_CASE),
        Regex("""^\.env\..*$""", RegexOption.IGNORE_CASE),
        Regex(""".*\.pem$""", RegexOption.IGNORE_CASE),
        Regex(""".*\.key$""", RegexOption.IGNORE_CASE),
        Regex(""".*\.p12$""", RegexOption.IGNORE_CASE),
        Regex(""".*\.jks$""", RegexOption.IGNORE_CASE),
        Regex(""".*\.keystore$""", RegexOption.IGNORE_CASE),
        Regex(""".*\.pfx$""", RegexOption.IGNORE_CASE),
        Regex("""^credentials.*$""", RegexOption.IGNORE_CASE),
        Regex("""^secrets?.*$""", RegexOption.IGNORE_CASE),
        Regex("""^id_rsa.*$""", RegexOption.IGNORE_CASE),
        Regex("""^id_ed25519.*$""", RegexOption.IGNORE_CASE),
        Regex("""^.*\.token$""", RegexOption.IGNORE_CASE),
        Regex("""^serviceaccount.*\.json$""", RegexOption.IGNORE_CASE),
    )

    /** Binaries and archives: no useful context, large payloads. */
    private val BINARY_EXTENSIONS: Set<String> = setOf(
        "class", "jar", "war", "ear", "zip", "tar", "gz", "7z", "rar",
        "png", "jpg", "jpeg", "gif", "bmp", "ico", "svg", "webp",
        "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
        "so", "dll", "dylib", "exe", "bin", "o", "a",
        "mp3", "mp4", "avi", "mov", "woff", "woff2", "ttf", "eot",
        "lock", "iml",
    )

    /** Above this, a file is data rather than code and blows the budget on its own. */
    const val MAX_FILE_BYTES: Long = 512 * 1024

    fun isExcludedDirectory(name: String): Boolean = name in EXCLUDED_DIRECTORIES

    /**
     * True when any path segment is a generated directory. Takes a path in either separator
     * style, since callers pass VFS paths on Windows.
     */
    fun isInExcludedDirectory(path: String): Boolean =
        path.replace('\\', '/').split('/').any { it.isNotEmpty() && isExcludedDirectory(it) }

    fun isSecretFile(fileName: String): Boolean =
        SECRET_FILE_PATTERNS.any { it.matches(fileName) }

    fun isBinary(fileName: String): Boolean =
        fileName.substringAfterLast('.', "").lowercase() in BINARY_EXTENSIONS

    /**
     * The single question callers should ask before reading a file for context.
     *
     * Deliberately conservative: "is this file named config, so it is probably fine" is
     * exactly the reasoning that leaks an `application-prod.properties` full of passwords.
     * Naming decides admission; the sanitizer still redacts the contents afterwards.
     */
    fun isEligible(path: String, fileName: String, sizeBytes: Long = 0): Boolean = when {
        isInExcludedDirectory(path) -> false
        isSecretFile(fileName) -> false
        isBinary(fileName) -> false
        sizeBytes > MAX_FILE_BYTES -> false
        else -> true
    }

    /** Why a file was rejected, for the debug log. Never includes contents. */
    fun reasonForExclusion(path: String, fileName: String, sizeBytes: Long = 0): String? = when {
        isInExcludedDirectory(path) -> "generated or VCS directory"
        isSecretFile(fileName) -> "matches a secret-file pattern"
        isBinary(fileName) -> "binary or archive"
        sizeBytes > MAX_FILE_BYTES -> "larger than ${MAX_FILE_BYTES / 1024} KB"
        else -> null
    }
}
