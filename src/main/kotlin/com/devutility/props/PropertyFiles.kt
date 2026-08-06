package com.devutility.props

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope

/** Reads configuration files off disk (or out of the editor) and flattens them. */
object PropertyFileReader {

    fun read(project: Project?, file: VirtualFile): PropertySnapshot {
        val format = PropertyFormat.forExtension(file.extension)
        val text = loadText(project, file) ?: return PropertySnapshot(file.name, file.path, format, emptyMap())
        return PropertySnapshot(file.name, file.path, format, parse(text, format))
    }

    fun parse(text: String, format: PropertyFormat): LinkedHashMap<String, PropertyEntry> = when (format) {
        PropertyFormat.YAML -> YamlParser.parse(text)
        else -> PropertiesParser.parse(text)
    }

    /**
     * Prefers the in-memory document so unsaved edits are compared too — that is normally
     * what you want when you are mid-way through editing one of the two files.
     */
    private fun loadText(project: Project?, file: VirtualFile): String? = try {
        ReadAction.compute<String?, RuntimeException> {
            if (!file.isValid || file.isDirectory) return@compute null
            FileDocumentManager.getInstance().getDocument(file)?.text
                ?: String(file.contentsToByteArray(), file.charset)
        }
    } catch (e: Exception) {
        null
    }
}

/** Finds the configuration files in a project that are worth offering in the compare picker. */
object PropertyFileScanner {

    private val EXTENSIONS = listOf("properties", "yml", "yaml", "env", "conf")

    private val IGNORED_PATH_SEGMENTS = listOf(
        "/build/", "/out/", "/target/", "/.gradle/", "/.idea/", "/node_modules/", "/.git/", "/venv/",
    )

    /** Must be called off the EDT — it hits the filename index. */
    fun scan(project: Project): List<VirtualFile> = ReadAction.compute<List<VirtualFile>, RuntimeException> {
        val scope = GlobalSearchScope.projectScope(project)
        EXTENSIONS
            .flatMap { ext -> FilenameIndex.getAllFilesByExt(project, ext, scope) }
            .filter { it.isValid && !it.isDirectory && !isIgnored(it) }
            .distinctBy { it.path }
            .sortedWith(compareBy({ !isSpringConfig(it.name) }, { it.name }, { it.path }))
    }

    fun isConfigFile(file: VirtualFile): Boolean =
        !file.isDirectory && file.extension?.lowercase() in EXTENSIONS

    private fun isIgnored(file: VirtualFile): Boolean {
        val path = file.path.replace('\\', '/')
        return IGNORED_PATH_SEGMENTS.any { it in path }
    }

    /** Spring's own config files float to the top of the picker — they are the common case. */
    private fun isSpringConfig(name: String): Boolean =
        name.startsWith("application") || name.startsWith("bootstrap")

    /**
     * `application-prod.yml` -> `prod`, `application.yml` -> `default`.
     * Used to label resolved values in the Spring inlay hints.
     */
    fun profileOf(fileName: String): String {
        val base = fileName.substringBeforeLast('.')
        val dash = base.indexOf('-')
        return if (dash >= 0 && dash < base.length - 1) base.substring(dash + 1) else "default"
    }
}
