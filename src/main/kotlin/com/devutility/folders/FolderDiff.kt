package com.devutility.folders

import com.devutility.props.DiffStatus
import com.devutility.props.PropertyDiffEngine
import com.devutility.props.PropertyFileReader
import com.devutility.props.PropertyFileScanner
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import java.security.MessageDigest

enum class FileStatus(val label: String) {
    SAME("Identical"),
    DIFFERENT("Different"),
    ONLY_LEFT("Only left"),
    ONLY_RIGHT("Only right"),
}

/**
 * How two configuration files differ at the key level. Null for anything that is not a
 * `.properties` or YAML pair — this is what makes the folder view more useful than a plain
 * byte comparison for config trees.
 */
data class KeyDelta(val different: Int, val onlyLeft: Int, val onlyRight: Int) {
    val total: Int get() = different + onlyLeft + onlyRight

    fun describe(): String = when {
        total == 0 -> "same keys"
        else -> buildList {
            if (different > 0) add("$different changed")
            if (onlyLeft > 0) add("$onlyLeft only left")
            if (onlyRight > 0) add("$onlyRight only right")
        }.joinToString(", ")
    }
}

data class FolderRow(
    val relativePath: String,
    val left: VirtualFile?,
    val right: VirtualFile?,
    val leftSize: Long,
    val rightSize: Long,
    val status: FileStatus,
    val keyDelta: KeyDelta?,
) {
    val isConfigPair: Boolean
        get() = left != null && right != null &&
            PropertyFileScanner.isConfigFile(left) && PropertyFileScanner.isConfigFile(right)
}

data class FolderDiffOptions(
    /** Compare bytes. When false, files of equal size are assumed identical — much faster. */
    val compareContents: Boolean = true,
    /** Skip build output, VCS metadata and dependency directories. */
    val skipGeneratedDirs: Boolean = true,
    /** Above this size, fall back to a size comparison rather than reading the whole file. */
    val maxComparedSize: Long = 20L * 1024 * 1024,
)

data class FolderDiffResult(
    val rows: List<FolderRow>,
    val leftRoot: String,
    val rightRoot: String,
    val truncated: Boolean = false,
) {
    val same: Int = rows.count { it.status == FileStatus.SAME }
    val different: Int = rows.count { it.status == FileStatus.DIFFERENT }
    val onlyLeft: Int = rows.count { it.status == FileStatus.ONLY_LEFT }
    val onlyRight: Int = rows.count { it.status == FileStatus.ONLY_RIGHT }
    val total: Int = rows.size
}

object FolderDiffEngine {

    private val SKIPPED_DIRS = setOf(
        ".git", ".hg", ".svn", ".idea", ".gradle", "build", "out", "target",
        "node_modules", "__pycache__", ".venv", "venv", "dist", ".next",
    )

    /** Hard ceiling so a mistargeted comparison cannot hang the IDE indefinitely. */
    private const val MAX_ROWS = 50_000

    /**
     * Compares two directory trees. Must run off the EDT — it reads file contents.
     *
     * [indicator] is polled for cancellation and updated with progress; pass null outside the IDE.
     */
    fun compare(
        project: Project?,
        leftRoot: VirtualFile,
        rightRoot: VirtualFile,
        options: FolderDiffOptions = FolderDiffOptions(),
        indicator: ProgressIndicator? = null,
    ): FolderDiffResult {
        val left = collect(leftRoot, options)
        val right = collect(rightRoot, options)

        val paths = LinkedHashSet<String>(left.size + right.size)
        paths += left.keys
        paths += right.keys

        val rows = ArrayList<FolderRow>(paths.size)
        var truncated = false
        var processed = 0

        for (path in paths.sorted()) {
            indicator?.checkCanceled()
            if (rows.size >= MAX_ROWS) {
                truncated = true
                break
            }

            val l = left[path]
            val r = right[path]
            indicator?.text2 = path
            indicator?.fraction = processed.toDouble() / paths.size.coerceAtLeast(1)
            processed++

            val status = when {
                l == null -> FileStatus.ONLY_RIGHT
                r == null -> FileStatus.ONLY_LEFT
                sameContent(l, r, options) -> FileStatus.SAME
                else -> FileStatus.DIFFERENT
            }

            val delta = if (status == FileStatus.DIFFERENT && l != null && r != null &&
                PropertyFileScanner.isConfigFile(l) && PropertyFileScanner.isConfigFile(r)
            ) {
                keyDelta(project, l, r)
            } else {
                null
            }

            rows += FolderRow(
                relativePath = path,
                left = l,
                right = r,
                leftSize = l?.length ?: -1,
                rightSize = r?.length ?: -1,
                status = status,
                keyDelta = delta,
            )
        }

        return FolderDiffResult(rows, leftRoot.path, rightRoot.path, truncated)
    }

    /** Relative path -> file, for every regular file under [root]. */
    private fun collect(root: VirtualFile, options: FolderDiffOptions): Map<String, VirtualFile> =
        ReadAction.compute<Map<String, VirtualFile>, RuntimeException> {
            val out = LinkedHashMap<String, VirtualFile>()
            if (!root.isValid || !root.isDirectory) return@compute out

            val stack = ArrayDeque<Pair<VirtualFile, String>>()
            stack.addLast(root to "")
            while (stack.isNotEmpty()) {
                val (dir, prefix) = stack.removeLast()
                for (child in dir.children ?: emptyArray()) {
                    if (!child.isValid) continue
                    val name = child.name
                    val relative = if (prefix.isEmpty()) name else "$prefix/$name"
                    if (child.isDirectory) {
                        if (options.skipGeneratedDirs && name in SKIPPED_DIRS) continue
                        stack.addLast(child to relative)
                    } else {
                        if (out.size >= MAX_ROWS) return@compute out
                        out[relative] = child
                    }
                }
            }
            out
        }

    private fun sameContent(a: VirtualFile, b: VirtualFile, options: FolderDiffOptions): Boolean {
        // Different lengths can never be equal content, and this is free.
        if (a.length != b.length) return false
        if (!options.compareContents) return true
        if (a.length > options.maxComparedSize) return true
        return digest(a).contentEquals(digest(b))
    }

    private fun digest(file: VirtualFile): ByteArray = try {
        ReadAction.compute<ByteArray, RuntimeException> {
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream.use { stream ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = stream.read(buffer)
                    if (read <= 0) break
                    md.update(buffer, 0, read)
                }
            }
            md.digest()
        }
    } catch (e: Exception) {
        // Unreadable files compare as different rather than silently identical.
        ByteArray(1) { (System.identityHashCode(file) and 0xFF).toByte() }
    }

    private fun keyDelta(project: Project?, left: VirtualFile, right: VirtualFile): KeyDelta? = try {
        val result = PropertyDiffEngine.diff(
            PropertyFileReader.read(project, left),
            PropertyFileReader.read(project, right),
        )
        KeyDelta(
            different = result.rows.count { it.status == DiffStatus.DIFFERENT },
            onlyLeft = result.rows.count { it.status == DiffStatus.ONLY_LEFT },
            onlyRight = result.rows.count { it.status == DiffStatus.ONLY_RIGHT },
        )
    } catch (e: Exception) {
        null
    }

    /** Plain-text report of the comparison, for copying or saving. */
    fun report(result: FolderDiffResult, rows: List<FolderRow>): String = buildString {
        append("Folder comparison\n")
        append("  Left : ").append(result.leftRoot).append('\n')
        append("  Right: ").append(result.rightRoot).append("\n\n")
        append("  ").append(result.total).append(" files — ")
            .append(result.same).append(" identical, ")
            .append(result.different).append(" different, ")
            .append(result.onlyLeft).append(" only left, ")
            .append(result.onlyRight).append(" only right\n")
        if (result.truncated) append("  (truncated at ").append(MAX_ROWS).append(" files)\n")
        append('\n')

        for (status in FileStatus.values()) {
            val bucket = rows.filter { it.status == status }
            if (bucket.isEmpty()) continue
            append(status.label).append(" (").append(bucket.size).append(")\n")
            for (row in bucket) {
                append("  ").append(row.relativePath)
                row.keyDelta?.let { append("   [").append(it.describe()).append(']') }
                append('\n')
            }
            append('\n')
        }
    }
}
