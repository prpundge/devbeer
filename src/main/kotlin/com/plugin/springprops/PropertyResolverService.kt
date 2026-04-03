package com.plugin.springprops

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import java.util.Properties

object PropertyResolverService {

    fun resolveProperty(project: Project, key: String): Map<String, String> {
        val results = mutableMapOf<String, String>()
        val allNames = FilenameIndex.getAllFilenames(project)
        val appNames = allNames.filter { 
            it.startsWith("application") && (it.endsWith(".yml") || it.endsWith(".yaml") || it.endsWith(".properties"))
        }

        for (name in appNames) {
            var profile = "default"
            if (name.contains("-")) {
                profile = name.substringAfter("-").substringBeforeLast(".")
            }

            val files = FilenameIndex.getVirtualFilesByName(name, GlobalSearchScope.projectScope(project))
            for (file in files) {
                val value = if (name.endsWith(".properties")) {
                    parseProperties(file, key)
                } else {
                    parseYaml(file, key)
                }
                
                if (value != null) {
                    results[profile] = value
                    break // Stop if found in this specific file
                }
            }
        }
        return results
    }

    private fun parseProperties(file: VirtualFile, key: String): String? {
        try {
            file.inputStream.use { stream ->
                val props = Properties()
                props.load(stream)
                return props.getProperty(key)
            }
        } catch (e: Exception) {
            // Ignore
        }
        return null
    }

    private fun parseYaml(file: VirtualFile, key: String): String? {
        try {
            val content = String(file.contentsToByteArray(), Charsets.UTF_8)
            val parts = key.split(".")
            
            // Simple poor-man's YAML parser for basic key-value extraction
            val lines = content.lines()
            var currentLevel = 0
            var partIndex = 0
            var expectedIndent = 0

            for (line in lines) {
                if (line.trim().isEmpty() || line.trim().startsWith("#")) continue
                
                val indent = line.takeWhile { it == ' ' }.length
                val trimmed = line.trim()
                
                if (indent < expectedIndent) {
                    // we popped up a level
                    if (indent == 0) {
                        partIndex = 0
                        expectedIndent = 0
                    } else {
                        // Very naive approach - we just reset if indent is smaller
                        partIndex = 0
                        expectedIndent = 0
                    }
                }

                if (partIndex < parts.size) {
                    val target = parts[partIndex]
                    if (trimmed.startsWith("$target:")) {
                        partIndex++
                        if (partIndex == parts.size) {
                           // we found the leaf
                           val valuePart = trimmed.substringAfter("$target:").trim()
                           if (valuePart.isNotEmpty()) {
                               return valuePart.removeSurrounding("\"").removeSurrounding("'")
                           }
                        } else {
                           expectedIndent = indent + 2 // Expect next level to have more indent
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // Ignore
        }
        return null
    }
}
