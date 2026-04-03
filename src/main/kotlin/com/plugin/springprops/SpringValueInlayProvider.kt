package com.plugin.springprops

import com.intellij.codeInsight.hints.declarative.*
import com.intellij.openapi.editor.Editor
import com.intellij.psi.*

class SpringValueInlayProvider : InlayHintsProvider {

    override fun createCollector(file: PsiFile, editor: Editor): InlayHintsCollector? {
        if (file !is PsiJavaFile) return null
        return SpringValueHintsCollector()
    }

    private class SpringValueHintsCollector : SharedBypassCollector {
        override fun collectFromElement(element: PsiElement, sink: InlayTreeSink) {
            if (element is PsiAnnotation) {
                val fqn = element.qualifiedName
                if (fqn == "org.springframework.beans.factory.annotation.Value") {
                    val valueAttribute = element.findAttributeValue("value")
                    val text = valueAttribute?.text?.trim('"', '\'') ?: return
                    
                    // extract property key from ${my.prop:default}
                    var key = text
                    if (key.startsWith("\${") && key.endsWith("}")) {
                        key = key.substring(2, key.length - 1)
                        if (key.contains(":")) {
                            key = key.substringBefore(":")
                        }
                    }

                    val resolvedMap = PropertyResolverService.resolveProperty(element.project, key)

                    if (resolvedMap.isNotEmpty()) {
                        val offset = element.textRange.endOffset
                        sink.addPresentation(InlineInlayPosition(offset, true), hasBackground = true) {
                            val display = resolvedMap.entries.joinToString(" ") { "[${it.key}: ${it.value}]" }
                            text(" \u2192 $display")
                        }
                    }
                }
            }
        }
    }
}
