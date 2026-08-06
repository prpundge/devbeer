package com.devutility.springprops

import com.intellij.codeInsight.hints.declarative.InlayHintsCollector
import com.intellij.codeInsight.hints.declarative.InlayHintsProvider
import com.intellij.codeInsight.hints.declarative.InlayTreeSink
import com.intellij.codeInsight.hints.declarative.InlineInlayPosition
import com.intellij.codeInsight.hints.declarative.SharedBypassCollector
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiField
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiModifier

private const val VALUE_ANNOTATION = "org.springframework.beans.factory.annotation.Value"
private const val CONFIG_PROPERTIES_ANNOTATION = "org.springframework.boot.context.properties.ConfigurationProperties"

/** `${some.key:fallback}` — captures the key and the optional inline default. */
private val PLACEHOLDER = Regex("""\$\{([^{}:]+)(?::([^{}]*))?}""")

private const val MAX_VALUE_LENGTH = 60

class SpringValueInlayProvider : InlayHintsProvider {

    override fun createCollector(file: com.intellij.psi.PsiFile, editor: Editor): InlayHintsCollector? {
        if (file !is PsiJavaFile) return null
        return SpringHintsCollector()
    }

    private class SpringHintsCollector : SharedBypassCollector {

        override fun collectFromElement(element: PsiElement, sink: InlayTreeSink) {
            when (element) {
                is PsiAnnotation -> when (element.qualifiedName) {
                    VALUE_ANNOTATION -> collectValueAnnotation(element, sink)
                    CONFIG_PROPERTIES_ANNOTATION -> collectConfigPropertiesAnnotation(element, sink)
                }
                is PsiField -> collectConfigPropertiesField(element, sink)
            }
        }

        private fun collectValueAnnotation(annotation: PsiAnnotation, sink: InlayTreeSink) {
            val raw = annotation.findAttributeValue("value")?.text?.trim('"') ?: return
            val index = SpringPropertyIndex.getInstance(annotation.project)

            val parts = mutableListOf<String>()
            val tooltip = StringBuilder()

            for (match in PLACEHOLDER.findAll(raw)) {
                val key = match.groupValues[1].trim()
                val inlineDefault = match.groups[2]?.value

                val resolved = index.resolve(key)
                when {
                    resolved.isNotEmpty() -> {
                        parts += resolved.joinToString(" ") { "${it.profile}=${abbreviate(it.value)}" }
                        resolved.forEach { tooltip.append(key).append(" [").append(it.profile).append("] = ")
                            .append(it.value).append("  (").append(it.fileName).append(")\n") }
                    }
                    inlineDefault != null -> {
                        parts += "default=${abbreviate(inlineDefault)}"
                        tooltip.append(key).append(" — not configured, using the inline default \"")
                            .append(inlineDefault).append("\"\n")
                    }
                    else -> {
                        parts += "unresolved"
                        tooltip.append(key).append(" — not found in any application config file\n")
                    }
                }
            }

            if (parts.isEmpty()) return
            addHint(sink, annotation.textRange.endOffset, parts.joinToString(", "), tooltip.toString().trim())
        }

        private fun collectConfigPropertiesAnnotation(annotation: PsiAnnotation, sink: InlayTreeSink) {
            val prefix = configPrefix(annotation) ?: return
            val counts = SpringPropertyIndex.getInstance(annotation.project).countUnderPrefix(prefix)
            if (counts.isEmpty()) {
                addHint(sink, annotation.textRange.endOffset, "no keys bound", "Nothing is configured under \"$prefix.\"")
                return
            }
            val text = counts.entries.joinToString(" ") { "${it.key}=${it.value} keys" }
            addHint(sink, annotation.textRange.endOffset, text, "Keys configured under \"$prefix.\" per profile")
        }

        private fun collectConfigPropertiesField(field: PsiField, sink: InlayTreeSink) {
            if (field.hasModifierProperty(PsiModifier.STATIC)) return
            val owner = field.containingClass ?: return
            val prefix = configPrefixOf(owner) ?: return
            val name = field.name

            val resolved = SpringPropertyIndex.getInstance(field.project).resolveRelaxed(prefix, name)
            if (resolved.isEmpty()) return

            val text = resolved.joinToString(" ") { "${it.profile}=${abbreviate(it.value)}" }
            val tooltip = resolved.joinToString("\n") {
                "$prefix.$name [${it.profile}] = ${it.value}  (${it.fileName})"
            }
            addHint(sink, field.nameIdentifier.textRange.endOffset, text, tooltip)
        }

        private fun addHint(sink: InlayTreeSink, offset: Int, text: String, tooltip: String) {
            // The Plugin Verifier flags this overload as deprecated on 2024.2+, which is
            // expected and deliberate: its replacement takes a HintFormat, and that class does
            // not exist in 2024.1. Switching would compile but fail with NoSuchMethodError on
            // the oldest IDE we support. Revisit when pluginSinceBuild moves past 241.
            sink.addPresentation(
                InlineInlayPosition(offset, true),
                null,
                tooltip.ifBlank { null },
                true,
            ) {
                text(" → $text")
            }
        }

        private fun configPrefixOf(owner: PsiClass): String? =
            owner.annotations.firstOrNull { it.qualifiedName == CONFIG_PROPERTIES_ANNOTATION }
                ?.let { configPrefix(it) }

        private fun configPrefix(annotation: PsiAnnotation): String? {
            val attribute = annotation.findAttributeValue("prefix")
                ?: annotation.findAttributeValue("value")
                ?: return null
            return attribute.text?.trim('"')?.trim()?.takeIf { it.isNotEmpty() }
        }

        private fun abbreviate(value: String): String {
            val flat = value.replace('\n', ' ').trim()
            return if (flat.length <= MAX_VALUE_LENGTH) flat else flat.take(MAX_VALUE_LENGTH - 1) + "…"
        }
    }
}
