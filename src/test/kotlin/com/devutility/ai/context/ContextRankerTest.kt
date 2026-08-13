package com.devutility.ai.context

import com.devutility.ai.settings.ContextBudget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextRankerTest {

    private fun candidate(
        path: String,
        kind: MatchKind = MatchKind.TEXT,
        signals: Set<Signal> = emptySet(),
        text: String = "",
        isMethod: Boolean = false,
        symbol: String? = null,
    ) = ContextCandidate(
        path = path,
        fileName = path.substringAfterLast('/'),
        symbol = symbol,
        kind = kind,
        signals = signals,
        text = text,
        isMethod = isMethod,
    )

    // ------------------------------------------------------------------ scoring

    @Test
    fun `scores follow the specified weights`() {
        assertEquals(100, ContextRanker.score(candidate("A", MatchKind.EXACT_SYMBOL)))
        assertEquals(80, ContextRanker.score(candidate("A", MatchKind.EXACT_METHOD)))
        assertEquals(70, ContextRanker.score(candidate("A", MatchKind.CLASS_NAME)))
        assertEquals(60, ContextRanker.score(candidate("A", MatchKind.REFERENCE)))
        assertEquals(40, ContextRanker.score(candidate("A", MatchKind.TEXT)))
    }

    @Test
    fun `signals stack on top of the base score`() {
        val score = ContextRanker.score(
            candidate("A", MatchKind.CLASS_NAME, setOf(Signal.SAME_PACKAGE, Signal.RELATED_TEST)),
        )
        assertEquals(70 + 20 + 20, score)
    }

    @Test
    fun `ranking orders by descending score`() {
        val ranked = ContextRanker.rank(
            listOf(
                candidate("c.java", MatchKind.TEXT),
                candidate("a.java", MatchKind.EXACT_SYMBOL),
                candidate("b.java", MatchKind.CLASS_NAME),
            ),
        )
        assertEquals(listOf("a.java", "b.java", "c.java"), ranked.map { it.candidate.path })
    }

    @Test
    fun `ranking is stable for equal scores`() {
        // Determinism matters: the same question must produce the same context every run.
        val input = listOf(
            candidate("z.java", MatchKind.TEXT),
            candidate("a.java", MatchKind.TEXT),
            candidate("m.java", MatchKind.TEXT),
        )
        val first = ContextRanker.rank(input).map { it.candidate.path }
        val second = ContextRanker.rank(input.reversed()).map { it.candidate.path }
        assertEquals(first, second)
        assertEquals(listOf("a.java", "m.java", "z.java"), first)
    }

    // ------------------------------------------------------------------ budget

    @Test
    fun `stops at the file ceiling`() {
        val candidates = (1..20).map { candidate("File$it.java", MatchKind.CLASS_NAME, text = "x") }
        val result = ContextRanker.select(candidates, ContextBudget(maxFiles = 3))
        assertEquals(3, result.fileCount)
        assertTrue(result.truncated)
        assertEquals(17, result.droppedForBudget)
    }

    @Test
    fun `stops at the character ceiling`() {
        val candidates = (1..10).map { candidate("File$it.java", text = "y".repeat(1000)) }
        val result = ContextRanker.select(candidates, ContextBudget(maxFiles = 100, maxCharacters = 3500))
        assertTrue(result.totalCharacters <= 3500)
        assertEquals(3, result.selected.size)
        assertTrue(result.truncated)
    }

    @Test
    fun `stops at the method ceiling`() {
        val candidates = (1..10).map {
            candidate("File.java", MatchKind.EXACT_METHOD, text = "m", isMethod = true, symbol = "m$it")
        }
        val result = ContextRanker.select(candidates, ContextBudget(maxFiles = 100, maxMethods = 4))
        assertEquals(4, result.selected.size)
    }

    @Test
    fun `repository size does not determine payload size`() {
        // The core promise of the budget: 10 files and 100,000 files cost the same.
        val budget = ContextBudget(maxFiles = 10, maxCharacters = 50_000)
        val small = ContextRanker.select((1..10).map { candidate("F$it.java", text = "z".repeat(500)) }, budget)
        val huge = ContextRanker.select((1..100_000).map { candidate("F$it.java", text = "z".repeat(500)) }, budget)

        assertEquals(small.fileCount, huge.fileCount)
        assertEquals(small.totalCharacters, huge.totalCharacters)
        assertTrue(huge.totalCharacters <= 50_000)
    }

    @Test
    fun `an empty project yields an empty selection rather than failing`() {
        val result = ContextRanker.select(emptyList(), ContextBudget())
        assertTrue(result.selected.isEmpty())
        assertFalse(result.truncated)
        assertEquals(0, result.totalCharacters)
    }

    @Test
    fun `higher ranked candidates survive truncation`() {
        val candidates = listOf(
            candidate("low.java", MatchKind.TEXT, text = "a".repeat(900)),
            candidate("high.java", MatchKind.EXACT_SYMBOL, text = "b".repeat(900)),
        )
        val result = ContextRanker.select(candidates, ContextBudget(maxCharacters = 1000))
        assertEquals(listOf("high.java"), result.selected.map { it.candidate.path })
    }

    // ------------------------------------------------------------------ query terms

    @Test
    fun `search terms drop stop words`() {
        val terms = ContextRanker.searchTerms("Where is customer validation implemented?")
        assertTrue(terms.toString(), terms.contains("customer"))
        assertTrue(terms.toString(), terms.contains("validation"))
        assertFalse(terms.toString(), terms.contains("Where"))
        assertFalse(terms.toString(), terms.contains("is"))
    }

    @Test
    fun `search terms split camel case identifiers`() {
        val terms = ContextRanker.searchTerms("How does createPayment work?")
        assertTrue(terms.toString(), terms.contains("createPayment"))
        assertTrue(terms.toString(), terms.contains("create"))
        assertTrue(terms.toString(), terms.contains("Payment"))
    }

    @Test
    fun `search terms are deduplicated and ordered`() {
        val terms = ContextRanker.searchTerms("payment payment PaymentService")
        assertEquals(terms.distinct(), terms)
    }

    @Test
    fun `a query of only stop words yields no terms`() {
        assertTrue(ContextRanker.searchTerms("how does the code work in my project").isEmpty())
    }
}
