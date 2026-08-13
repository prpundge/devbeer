package com.devutility.ai.context

import com.devutility.ai.settings.ContextBudget
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiSearchHelper
import com.intellij.psi.search.UsageSearchContext
import com.intellij.util.Processor

/** Reads short windows of source without loading whole files into the payload. */
object SourceSnippets {

    private const val LINES_BEFORE = 3
    private const val LINES_AFTER = 12

    fun documentOf(file: VirtualFile): Document? =
        FileDocumentManager.getInstance().getDocument(file)

    /** [line] is 1-based. Returns a numbered window so the model can cite real line numbers. */
    fun aroundLine(file: VirtualFile, line: Int): String {
        val document = documentOf(file) ?: return ""
        val total = document.lineCount
        if (total == 0) return ""

        val zeroBased = (line - 1).coerceIn(0, total - 1)
        val from = (zeroBased - LINES_BEFORE).coerceAtLeast(0)
        val to = (zeroBased + LINES_AFTER).coerceAtMost(total - 1)

        return buildString {
            for (i in from..to) {
                val start = document.getLineStartOffset(i)
                val end = document.getLineEndOffset(i)
                append(i + 1).append(": ").append(document.getText(com.intellij.openapi.util.TextRange(start, end)))
                append('\n')
            }
        }
    }

    fun aroundElement(element: PsiElement): String {
        val file = element.containingFile?.virtualFile ?: return ""
        val document = element.containingFile.viewProvider.document ?: return ""
        val line = document.getLineNumber(element.textOffset) + 1
        return aroundLine(file, line)
    }
}

/**
 * Turns a question into a small, ranked set of source fragments.
 *
 * Uses IntelliJ's word index through [PsiSearchHelper] rather than walking the tree, so a
 * hundred-thousand-file project costs about what a small one does — there is no full
 * repository scan on the normal path (§6, §22, §37).
 *
 * Must be called off the EDT.
 */
object ContextResolver {

    /** Candidate files examined before ranking. Bounds the read cost, not just the payload. */
    private const val MAX_CANDIDATE_FILES = 60

    data class Resolution(
        val selected: List<RankedContext>,
        val truncated: Boolean,
        val filesConsidered: Int,
        val javaSupport: Boolean,
    )

    /**
     * @param stackFrameFiles file names taken from a stack trace. Files that appear in the
     *   trace are the ones a developer actually needs when diagnosing a failure, so they carry
     *   the heaviest single signal in the table ([Signal.STACK_FRAME]).
     */
    fun resolve(
        project: Project,
        query: String,
        budget: ContextBudget,
        stackFrameFiles: Set<String> = emptySet(),
    ): Resolution {
        val terms = ContextRanker.searchTerms(query)
        if (terms.isEmpty() && stackFrameFiles.isEmpty()) {
            return Resolution(emptyList(), false, 0, SymbolEnricher.get().available)
        }

        val enricher = SymbolEnricher.get()

        // Precise Java symbols first — they outrank anything text search can produce.
        val symbolCandidates = try {
            enricher.enrich(project, terms, budget.maxFiles * 2)
        } catch (e: Exception) {
            emptyList()
        }

        val textCandidates = textSearch(project, terms, budget)
        val all = (symbolCandidates + textCandidates)
            .distinctBy { it.path to it.line }
            .map { candidate ->
                if (candidate.fileName in stackFrameFiles) {
                    candidate.copy(signals = candidate.signals + Signal.STACK_FRAME)
                } else {
                    candidate
                }
            }

        val selection = ContextRanker.select(all, budget)
        return Resolution(
            selected = selection.selected,
            truncated = selection.truncated,
            filesConsidered = all.map { it.path }.distinct().size,
            javaSupport = enricher.available,
        )
    }

    /**
     * Index-backed word search, language agnostic, so this path works in every IDE.
     */
    private fun textSearch(project: Project, terms: List<String>, budget: ContextBudget): List<ContextCandidate> =
        ReadAction.compute<List<ContextCandidate>, RuntimeException> {
            val helper = PsiSearchHelper.getInstance(project)
            val scope = GlobalSearchScope.projectScope(project)

            // How many query terms each file matched — the cheap relevance signal.
            val hits = LinkedHashMap<VirtualFile, MutableSet<String>>()

            for (term in terms) {
                if (term.length < 3) continue
                ProgressManager.checkCanceled()
                helper.processCandidateFilesForText(
                    scope,
                    UsageSearchContext.ANY,
                    true,
                    term,
                    Processor { file ->
                        if (ContextExclusionService.isEligible(file.path, file.name, file.length)) {
                            hits.getOrPut(file) { mutableSetOf() } += term
                        }
                        hits.size < MAX_CANDIDATE_FILES
                    },
                )
                if (hits.size >= MAX_CANDIDATE_FILES) break
            }

            hits.entries
                .sortedByDescending { it.value.size }
                .take(budget.maxFiles * 2)
                .mapNotNull { (file, matched) -> candidateFor(file, matched, terms) }
        }

    private fun candidateFor(
        file: VirtualFile,
        matchedTerms: Set<String>,
        allTerms: List<String>,
    ): ContextCandidate? {
        val document = SourceSnippets.documentOf(file) ?: return null
        val text = document.text

        // Locate the first line mentioning any matched term, so the snippet is centred on
        // something relevant rather than on the top of the file.
        var bestLine = 1
        outer@ for (term in matchedTerms) {
            val index = text.indexOf(term)
            if (index >= 0) {
                bestLine = document.getLineNumber(index) + 1
                break@outer
            }
        }

        val nameMatches = allTerms.any { file.nameWithoutExtension.equals(it, ignoreCase = true) }
        val signals = mutableSetOf<Signal>()
        if (file.name.contains("Test", ignoreCase = true)) signals += Signal.RELATED_TEST
        if (file.extension in CONFIG_EXTENSIONS) signals += Signal.CONFIGURATION_RELATIONSHIP

        return ContextCandidate(
            path = file.path,
            fileName = file.name,
            symbol = if (nameMatches) file.nameWithoutExtension else null,
            kind = if (nameMatches) MatchKind.CLASS_NAME else MatchKind.TEXT,
            signals = signals,
            line = bestLine,
            text = SourceSnippets.aroundLine(file, bestLine),
        )
    }

    private val CONFIG_EXTENSIONS = setOf("properties", "yml", "yaml", "xml")
}
