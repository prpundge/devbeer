package com.devutility.folders

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [FolderDiffEngine.compare] needs the IntelliJ VFS, so it is exercised in the sandbox rather
 * than here. The pure parts — the delta summary and the report layout — are covered directly.
 */
class KeyDeltaTest {

    @Test
    fun `describes an unchanged pair`() {
        assertEquals("same keys", KeyDelta(0, 0, 0).describe())
    }

    @Test
    fun `describes each kind of difference`() {
        assertEquals("3 changed", KeyDelta(3, 0, 0).describe())
        assertEquals("2 only left", KeyDelta(0, 2, 0).describe())
        assertEquals("1 only right", KeyDelta(0, 0, 1).describe())
    }

    @Test
    fun `combines several kinds in a stable order`() {
        assertEquals("1 changed, 2 only left, 3 only right", KeyDelta(1, 2, 3).describe())
    }

    @Test
    fun `total counts every difference`() {
        assertEquals(6, KeyDelta(1, 2, 3).total)
        assertEquals(0, KeyDelta(0, 0, 0).total)
    }

    @Test
    fun `report groups rows by status and includes the key delta`() {
        val rows = listOf(
            row("same.txt", FileStatus.SAME),
            row("app.properties", FileStatus.DIFFERENT, KeyDelta(2, 1, 0)),
            row("only-left.txt", FileStatus.ONLY_LEFT),
            row("only-right.txt", FileStatus.ONLY_RIGHT),
        )
        val result = FolderDiffResult(rows, "/left", "/right")
        val report = FolderDiffEngine.report(result, rows)

        assertEquals(1, result.same)
        assertEquals(1, result.different)
        assertEquals(1, result.onlyLeft)
        assertEquals(1, result.onlyRight)
        assertEquals(4, result.total)

        // Each status gets its own section, and config rows carry their key summary.
        for (marker in listOf("Identical (1)", "Different (1)", "Only left (1)", "Only right (1)")) {
            assert(report.contains(marker)) { "missing '$marker' in:\n$report" }
        }
        assert(report.contains("[2 changed, 1 only left]")) { "missing key delta in:\n$report" }
        assert(report.contains("/left")) { "missing left root in:\n$report" }
    }

    private fun row(path: String, status: FileStatus, delta: KeyDelta? = null) = FolderRow(
        relativePath = path,
        left = null,
        right = null,
        leftSize = 0,
        rightSize = 0,
        status = status,
        keyDelta = delta,
    )
}
