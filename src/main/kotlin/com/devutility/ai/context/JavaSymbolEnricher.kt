package com.devutility.ai.context

import com.devutility.ai.model.CodeLocation
import com.devutility.ai.model.FlowNode
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiModifierListOwner
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiShortNamesCache
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil

/**
 * Java and Spring aware symbol resolution.
 *
 * **Loaded reflectively by [SymbolEnricher] and only when the Java module is present.** Never
 * reference this class directly from code that must run without Java support — the reference
 * alone would fail to link.
 */
@Suppress("unused") // instantiated by name from SymbolEnricher
class JavaSymbolEnricher : SymbolEnricher {

    override val available: Boolean = true

    private val springAnnotations = setOf(
        "Service", "Component", "Repository", "Controller", "RestController",
        "Configuration", "Bean", "ConfigurationProperties", "Value", "Autowired",
    )

    override fun enrich(project: Project, terms: List<String>, limit: Int): List<ContextCandidate> =
        ReadAction.compute<List<ContextCandidate>, RuntimeException> {
            val cache = PsiShortNamesCache.getInstance(project)
            val scope = GlobalSearchScope.projectScope(project)
            val out = mutableListOf<ContextCandidate>()

            for (term in terms) {
                if (out.size >= limit) break
                ProgressManager.checkCanceled()

                // Exact class name: the strongest signal available.
                for (psiClass in cache.getClassesByName(term, scope)) {
                    if (out.size >= limit) break
                    candidateFor(psiClass, MatchKind.EXACT_SYMBOL)?.let { out += it }
                }

                // Exact method name.
                for (method in cache.getMethodsByName(term, scope)) {
                    if (out.size >= limit) break
                    candidateFor(method, MatchKind.EXACT_METHOD, isMethod = true)?.let { out += it }
                }

                // Capitalised term as a class name: "validation" -> "Validation", "Validator".
                for (variant in classNameVariants(term)) {
                    if (out.size >= limit) break
                    for (psiClass in cache.getClassesByName(variant, scope)) {
                        if (out.size >= limit) break
                        candidateFor(psiClass, MatchKind.CLASS_NAME)?.let { out += it }
                    }
                }
            }

            // Where the matched symbols are *used*. "Where is payment validation handled?"
            // is usually answered by the call sites, not only by the declaration, which is why
            // §7 scores a reference above a plain text hit.
            out += references(project, scope, out.toList(), limit - out.size)

            out.distinctBy { it.path to it.symbol }
        }

    /** Call sites of already-matched declarations, bounded so a popular symbol cannot flood. */
    private fun references(
        project: Project,
        scope: GlobalSearchScope,
        declarations: List<ContextCandidate>,
        remaining: Int,
    ): List<ContextCandidate> {
        if (remaining <= 0) return emptyList()
        val cache = PsiShortNamesCache.getInstance(project)
        val out = mutableListOf<ContextCandidate>()

        for (declaration in declarations.take(MAX_SYMBOLS_TO_TRACE)) {
            if (out.size >= remaining) break
            val simpleName = declaration.symbol?.substringAfterLast('#') ?: continue

            val targets: List<PsiElement> =
                cache.getClassesByName(simpleName, scope).toList() +
                    cache.getMethodsByName(simpleName, scope).toList()

            for (target in targets) {
                if (out.size >= remaining) break
                ProgressManager.checkCanceled()
                var found = 0
                ReferencesSearch.search(target, scope, true).forEach { reference ->
                    val element = reference.element
                    candidateFor(element, MatchKind.REFERENCE)?.let { out += it }
                    found++
                    // Keep going only while there is budget and the symbol is not ubiquitous.
                    found < MAX_REFERENCES_PER_SYMBOL && out.size < remaining
                }
            }
        }
        return out
    }

    override fun resolveFlow(project: Project, methodName: String, limit: Int): List<FlowNode> =
        ReadAction.compute<List<FlowNode>, RuntimeException> {
            val cache = PsiShortNamesCache.getInstance(project)
            val scope = GlobalSearchScope.projectScope(project)
            val fileIndex = com.intellij.openapi.roots.ProjectRootManager.getInstance(project).fileIndex
            val roots = cache.getMethodsByName(methodName, scope)
            if (roots.isEmpty()) return@compute emptyList()

            val nodes = mutableListOf<FlowNode>()
            val visited = mutableSetOf<String>()

            // Breadth-first over outgoing calls. Deliberately shallow: this is a readable
            // trace for a developer, not a call graph (§14).
            var frontier = listOf(roots.first())
            var depth = 0
            while (frontier.isNotEmpty() && nodes.size < limit && depth < MAX_FLOW_DEPTH) {
                val next = mutableListOf<PsiMethod>()
                for (method in frontier) {
                    ProgressManager.checkCanceled()
                    val signature = signatureOf(method)
                    if (!visited.add(signature)) continue

                    nodes += FlowNode(
                        symbol = signature,
                        location = locationOf(method),
                        description = method.containingClass?.qualifiedName,
                    )
                    if (nodes.size >= limit) break

                    PsiTreeUtil.findChildrenOfType(method.body, PsiMethodCallExpression::class.java)
                        .mapNotNull { it.resolveMethod() }
                        // Only follow calls into project source; library calls are noise.
                        // isInContent is the direct question ("is this project code") rather
                        // than a search scope, which answers it indirectly.
                        .filter { called ->
                            val vf = called.containingFile?.virtualFile
                            vf != null && fileIndex.isInContent(vf)
                        }
                        .forEach { next += it }
                }
                frontier = next
                depth++
            }
            nodes
        }

    // ------------------------------------------------------------------ helpers

    private fun candidateFor(
        element: PsiElement,
        kind: MatchKind,
        isMethod: Boolean = false,
    ): ContextCandidate? {
        val file = element.containingFile?.virtualFile ?: return null
        if (!ContextExclusionService.isEligible(file.path, file.name, file.length)) return null

        val document = element.containingFile.viewProvider.document
        val line = document?.getLineNumber(element.textOffset)?.plus(1) ?: 0

        val signals = mutableSetOf<Signal>()
        if (hasSpringAnnotation(element)) signals += Signal.SPRING_COMPONENT
        if (file.name.endsWith("Test.java") || file.name.endsWith("Tests.java")) signals += Signal.RELATED_TEST

        return ContextCandidate(
            path = file.path,
            fileName = file.name,
            symbol = symbolName(element),
            kind = kind,
            signals = signals,
            line = line,
            text = SourceSnippets.aroundElement(element),
            isMethod = isMethod,
        )
    }

    private fun hasSpringAnnotation(element: PsiElement): Boolean {
        val owner = element as? PsiModifierListOwner
            ?: PsiTreeUtil.getParentOfType(element, PsiModifierListOwner::class.java)
            ?: return false
        return owner.annotations.any { annotation ->
            annotation.qualifiedName?.substringAfterLast('.') in springAnnotations
        }
    }

    private fun symbolName(element: PsiElement): String? = when (element) {
        is PsiClass -> element.name
        is PsiMethod -> "${element.containingClass?.name ?: ""}#${element.name}"
        else -> null
    }

    private fun signatureOf(method: PsiMethod): String =
        "${method.containingClass?.name ?: "?"}#${method.name}()"

    private fun locationOf(method: PsiMethod): CodeLocation? {
        val file = method.containingFile?.virtualFile ?: return null
        val document = method.containingFile.viewProvider.document
        val line = document?.getLineNumber(method.textOffset)?.plus(1) ?: 0
        // Verified: this came from the project model, not from a model's imagination.
        return CodeLocation(file.path, line, symbol = signatureOf(method), verified = true)
    }

    /** "validation" also searches "Validation" and "Validator" — how Java projects are named. */
    private fun classNameVariants(term: String): List<String> {
        if (term.isEmpty() || term[0].isUpperCase()) return emptyList()
        val capitalised = term.replaceFirstChar { it.uppercase() }
        return listOf(capitalised, "${capitalised}Service", "${capitalised}Validator", "${capitalised}Controller")
    }

    private companion object {
        const val MAX_FLOW_DEPTH = 6

        /** Only the strongest declarations are traced; reference search is the expensive part. */
        const val MAX_SYMBOLS_TO_TRACE = 3

        /** A ubiquitous symbol must not flood the context with call sites. */
        const val MAX_REFERENCES_PER_SYMBOL = 5
    }
}
