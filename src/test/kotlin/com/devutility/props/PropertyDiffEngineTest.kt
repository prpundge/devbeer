package com.devutility.props

import org.junit.Assert.assertEquals
import org.junit.Test

class PropertyDiffEngineTest {

    private fun snapshot(name: String, format: PropertyFormat, text: String) = PropertySnapshot(
        displayName = name,
        path = "/$name",
        format = format,
        entries = PropertyFileReaderStub.parse(text.trimIndent(), format),
    )

    /** Keeps the test off the IntelliJ VFS while using the same parse dispatch. */
    private object PropertyFileReaderStub {
        fun parse(text: String, format: PropertyFormat) = when (format) {
            PropertyFormat.YAML -> YamlParser.parse(text)
            else -> PropertiesParser.parse(text)
        }
    }

    @Test
    fun `classifies every key`() {
        val left = snapshot("left.properties", PropertyFormat.PROPERTIES, "same=1\ndiffers=a\nonly.left=x")
        val right = snapshot("right.properties", PropertyFormat.PROPERTIES, "same=1\ndiffers=b\nonly.right=y")

        val result = PropertyDiffEngine.diff(left, right)
        val byKey = result.rows.associateBy { it.key }

        assertEquals(DiffStatus.SAME, byKey["same"]?.status)
        assertEquals(DiffStatus.DIFFERENT, byKey["differs"]?.status)
        assertEquals(DiffStatus.ONLY_LEFT, byKey["only.left"]?.status)
        assertEquals(DiffStatus.ONLY_RIGHT, byKey["only.right"]?.status)

        assertEquals(4, result.total)
        assertEquals(1, result.same)
        assertEquals(1, result.different)
        assertEquals(1, result.onlyLeft)
        assertEquals(1, result.onlyRight)
    }

    @Test
    fun `compares a properties file against a yaml file`() {
        val left = snapshot(
            "application.properties", PropertyFormat.PROPERTIES,
            "server.port=8080\nspring.datasource.username=admin",
        )
        val right = snapshot(
            "application.yml", PropertyFormat.YAML,
            """
            server:
              port: 9090
            spring:
              datasource:
                username: admin
            """,
        )

        val byKey = PropertyDiffEngine.diff(left, right).rows.associateBy { it.key }

        assertEquals(DiffStatus.DIFFERENT, byKey["server.port"]?.status)
        assertEquals(DiffStatus.SAME, byKey["spring.datasource.username"]?.status)
    }

    @Test
    fun `honours the ignore case and trim options`() {
        val left = snapshot("a.properties", PropertyFormat.PROPERTIES, "k=Value  ")
        val right = snapshot("b.properties", PropertyFormat.PROPERTIES, "k=value")

        val strict = PropertyDiffEngine.diff(left, right, DiffOptions(ignoreCase = false, trimValues = false))
        assertEquals(DiffStatus.DIFFERENT, strict.rows.single().status)

        val lenient = PropertyDiffEngine.diff(left, right, DiffOptions(ignoreCase = true, trimValues = true))
        assertEquals(DiffStatus.SAME, lenient.rows.single().status)
    }

    @Test
    fun `normalize emits both sides over the same key list`() {
        val left = snapshot("a.properties", PropertyFormat.PROPERTIES, "b=2\na=1")
        val right = snapshot("b.yml", PropertyFormat.YAML, "a: 1")

        val keys = (left.keys + right.keys).sorted()
        val leftText = PropertyDiffEngine.normalize(left, keys)
        val rightText = PropertyDiffEngine.normalize(right, keys)

        // Same number of body lines on both sides is what makes the diff viewer line up.
        fun body(s: String) = s.lines().filter { it.isNotBlank() }.drop(2)
        assertEquals(body(leftText).size, body(rightText).size)
        assertEquals(listOf("a = 1", "b = 2"), body(leftText))
        assertEquals(listOf("a = 1", "# (absent) b"), body(rightText))
    }
}
