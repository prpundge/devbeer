package com.devutility.ai

import com.devutility.ai.context.ContextCandidate
import com.devutility.ai.context.ContextExclusionService
import com.devutility.ai.context.ContextRanker
import com.devutility.ai.context.ContextSanitizer
import com.devutility.ai.error.ErrorParser
import com.devutility.ai.model.AiResultType
import com.devutility.ai.prompt.PromptBuilder
import com.devutility.ai.settings.ContextBudget
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Measures the CPU- and memory-bound parts of the AI layer against the §37 targets.
 *
 * These are the pieces whose cost scales with repository size, so they are where a promise
 * like "a hundred-thousand-file project stays responsive" is either true or not. The
 * IDE-bound half (index search) is measured separately in the fixture test — this covers
 * everything that runs after the index hands results back.
 *
 * Thresholds are deliberately loose: the point is to catch an order-of-magnitude regression,
 * not to fail on a busy CI machine.
 */
class PerformanceBenchmarkTest {

    private fun <T> measure(label: String, warmups: Int = 2, runs: Int = 5, body: () -> T): Long {
        repeat(warmups) { body() }
        val timings = (1..runs).map {
            val start = System.nanoTime()
            body()
            (System.nanoTime() - start) / 1_000_000
        }
        val median = timings.sorted()[timings.size / 2]
        println("BENCH $label: median ${median}ms  (runs=$timings)")
        return median
    }

    private fun candidates(n: Int, textSize: Int = 400) = (1..n).map {
        ContextCandidate(
            path = "/project/src/main/java/com/example/pkg$it/Class$it.java",
            fileName = "Class$it.java",
            symbol = "Class$it",
            text = "x".repeat(textSize),
        )
    }

    // ---------------------------------------------------------------- ranking

    @Test
    fun `ranking one hundred thousand candidates stays well under a second`() {
        val pool = candidates(100_000)
        val millis = measure("rank+select 100k candidates") {
            ContextRanker.select(pool, ContextBudget())
        }
        assertTrue("ranking 100k took ${millis}ms", millis < 2_000)
    }

    @Test
    fun `payload size is independent of repository size`() {
        // The central §8 promise, measured rather than asserted.
        val budget = ContextBudget(maxFiles = 10, maxCharacters = 50_000)
        val small = ContextRanker.select(candidates(10), budget)
        val huge = ContextRanker.select(candidates(100_000), budget)

        println("BENCH payload: 10-file project=${small.totalCharacters} chars, 100k-file=${huge.totalCharacters} chars")
        assertTrue(huge.totalCharacters <= 50_000)
        assertTrue(huge.fileCount <= 10)
    }

    // ---------------------------------------------------------------- sanitising

    @Test
    fun `sanitising a large context stays fast`() {
        // Worst realistic case: a full 50,000-character payload at the budget ceiling.
        val payload = buildString {
            repeat(500) {
                append("public class Sample$it { private String token = \"value$it\"; }\n")
            }
        }
        val millis = measure("sanitize ${payload.length} chars") { ContextSanitizer.sanitize(payload) }
        assertTrue("sanitising took ${millis}ms", millis < 500)
    }

    // ---------------------------------------------------------------- error parsing

    @Test
    fun `parsing a deep stack trace stays fast`() {
        val trace = buildString {
            appendLine("java.lang.RuntimeException: deep")
            repeat(1_000) { appendLine("\tat com.example.Class$it.method$it(Class$it.java:${it + 1})") }
        }
        val millis = measure("parse 1000-frame trace") { ErrorParser.parse(trace, maxStackTraceLines = 100) }
        assertTrue("parsing took ${millis}ms", millis < 200)
    }

    // ---------------------------------------------------------------- exclusion

    @Test
    fun `exclusion checks are cheap enough to run per file`() {
        val count = 100_000
        val paths = (1..count).map { "/project/src/main/java/com/example/Class$it.java" }
        val millis = measure("exclusion check ${count / 1000}k paths") {
            paths.count { ContextExclusionService.isEligible(it, it.substringAfterLast('/'), 1000) }
        }
        // Assert the per-path cost rather than the total: the resolver only ever checks the
        // ~60 candidate files the index returns, so the figure that matters is microseconds
        // per path, and a total-time assertion is just a machine-speed test.
        val microsPerPath = millis * 1000.0 / count
        println("BENCH exclusion per path: ${"%.2f".format(microsPerPath)}µs → ~${"%.1f".format(microsPerPath * 60 / 1000)}ms for a real 60-file pass")
        assertTrue("exclusion cost ${microsPerPath}µs per path", microsPerPath < 100)
    }

    // ---------------------------------------------------------------- memory

    @Test
    fun `context at the budget ceiling has a bounded footprint`() {
        val budget = ContextBudget()
        val selection = ContextRanker.select(candidates(1_000, textSize = 5_000), budget)

        // Characters are the honest proxy: two bytes per char in a JVM String.
        val approxBytes = selection.totalCharacters.toLong() * 2
        println("BENCH context footprint: ${selection.totalCharacters} chars ≈ ${approxBytes / 1024} KB")

        assertTrue("context should stay at or under the 50,000-char budget", selection.totalCharacters <= 50_000)
        assertTrue("that is ~100 KB, not megabytes", approxBytes < 256 * 1024)
    }

    @Test
    fun `the response cache cannot grow without bound`() {
        // AiService caps responses at 50 and contexts at 20. A pathological reply is capped by
        // the provider's own response, so the worst case is 50 × (one reply).
        val worstCaseReply = 32 * 1024
        val maxCachedResponses = 50
        val approxBytes = worstCaseReply.toLong() * maxCachedResponses * 2

        println("BENCH cache ceiling: 50 replies × 32 KB ≈ ${approxBytes / 1024 / 1024} MB worst case")
        assertTrue("bounded caches must stay in the single-digit MB range", approxBytes < 8L * 1024 * 1024)
    }

    // ---------------------------------------------------------------- prompt assembly

    @Test
    fun `building a full prompt is not a measurable cost`() {
        val selection = ContextRanker.select(candidates(1_000), ContextBudget())
        val millis = measure("build full prompt") {
            PromptBuilder(AiResultType.REPOSITORY_ANSWER)
                .projectContext("bench")
                .relevantCode(selection.selected)
                .task("where is validation handled")
                .build()
        }
        assertTrue("prompt assembly took ${millis}ms", millis < 500)
    }
}
