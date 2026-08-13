package com.devutility.ai.error

import com.devutility.ai.context.ContextExclusionService
import com.devutility.ai.context.SourceSnippets
import com.devutility.ai.model.CodeLocation
import com.devutility.ai.model.ParsedError
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope

/**
 * Maps a claimed location onto a real file in this project.
 *
 * The rule that makes AI navigation trustworthy: **a location is only marked
 * [CodeLocation.verified] once the file has been found in the project model and the line is
 * within it.** Models invent plausible paths; an unverified location may be displayed as text
 * but must never become a clickable link (§13, §16).
 */
object SourceLocationResolver {

    /** Result of trying to place a location in the project. */
    data class Resolved(
        val location: CodeLocation,
        val file: VirtualFile?,
    ) {
        val isNavigable: Boolean get() = file != null && location.verified
    }

    /**
     * Verifies [location]. Returns it with `verified = true` and the real path when the file
     * exists and the line is in range; otherwise returns it unchanged and unverified.
     *
     * Must be called off the EDT.
     */
    fun verify(project: Project, location: CodeLocation): Resolved =
        ReadAction.compute<Resolved, RuntimeException> {
            val file = findFile(project, location.filePath)
                ?: return@compute Resolved(location.copy(verified = false), null)

            val lineCount = SourceSnippets.documentOf(file)?.lineCount ?: 0
            // Line 0 means "file only", which is still a useful, verifiable destination.
            val lineOk = location.line == 0 || (location.line in 1..lineCount)

            if (!lineOk) {
                return@compute Resolved(location.copy(verified = false), null)
            }

            Resolved(
                location.copy(filePath = file.path, verified = true),
                file,
            )
        }

    fun verifyAll(project: Project, locations: List<CodeLocation>): List<Resolved> =
        locations.map { verify(project, it) }

    /**
     * Finds a file from whatever the model or a stack trace produced: an absolute path, a
     * project-relative path, or just a file name.
     */
    fun findFile(project: Project, path: String): VirtualFile? {
        if (path.isBlank()) return null
        val normalised = path.trim().replace('\\', '/')

        // 1. An absolute path that exists and belongs to this project.
        LocalFileSystem.getInstance().findFileByPath(normalised)?.let { file ->
            if (file.isValid && !file.isDirectory && inProject(project, file)) return file
        }

        // 2. Relative to the project root.
        project.basePath?.let { base ->
            LocalFileSystem.getInstance().findFileByPath("$base/${normalised.trimStart('/')}")?.let { file ->
                if (file.isValid && !file.isDirectory) return file
            }
        }

        // 3. By file name through the index, preferring a match whose parent path also lines up.
        val fileName = normalised.substringAfterLast('/')
        if (fileName.isBlank()) return null

        val matches = try {
            FilenameIndex.getVirtualFilesByName(fileName, GlobalSearchScope.projectScope(project))
                .filter { it.isValid && !it.isDirectory }
                .filter { ContextExclusionService.isEligible(it.path, it.name, it.length) }
        } catch (e: Exception) {
            emptyList()
        }
        if (matches.isEmpty()) return null
        if (matches.size == 1) return matches.first()

        // Several files share the name — pick the one whose path best matches what was asked for.
        val wanted = normalised.substringBeforeLast('/', "")
        return matches.maxByOrNull { candidate ->
            val candidatePath = candidate.path.replace('\\', '/')
            commonSuffixLength(candidatePath, "$wanted/$fileName")
        }
    }

    /**
     * Best source location for a parsed error, taken from the project model rather than from
     * a model's guess. Prefers the first application stack frame over library frames.
     */
    fun locateError(project: Project, error: ParsedError): Resolved? {
        // A compiler diagnostic already names its file and line.
        error.fileName?.let { name ->
            val resolved = verify(project, CodeLocation(name, error.line, error.column))
            if (resolved.isNavigable) return resolved
        }

        // Otherwise walk the trace, application frames first.
        val ordered = error.frames.sortedBy { it.isLibraryFrame }
        for (frame in ordered) {
            val name = frame.fileName ?: continue
            val resolved = verify(
                project,
                CodeLocation(name, frame.line, symbol = "${frame.declaringClass}.${frame.methodName}"),
            )
            if (resolved.isNavigable) return resolved
        }
        return null
    }

    private fun inProject(project: Project, file: VirtualFile): Boolean =
        GlobalSearchScope.projectScope(project).contains(file)

    private fun commonSuffixLength(a: String, b: String): Int {
        var i = 0
        while (i < a.length && i < b.length && a[a.length - 1 - i] == b[b.length - 1 - i]) i++
        return i
    }
}
