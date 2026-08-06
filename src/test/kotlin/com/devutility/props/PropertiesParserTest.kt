package com.devutility.props

import org.junit.Assert.assertEquals
import org.junit.Test

class PropertiesParserTest {

    private fun values(text: String): Map<String, String> =
        PropertiesParser.parse(text.trimIndent()).mapValues { it.value.value }

    @Test
    fun `reads the three separator styles`() {
        val result = values(
            """
            a.equals=1
            a.colon:2
            a.space 3
            """
        )

        assertEquals("1", result["a.equals"])
        assertEquals("2", result["a.colon"])
        assertEquals("3", result["a.space"])
    }

    @Test
    fun `skips comments and blank lines`() {
        val result = values(
            """
            # a comment
            ! another comment

            server.port=8080
            """
        )

        assertEquals(1, result.size)
        assertEquals("8080", result["server.port"])
    }

    @Test
    fun `records one-based line numbers`() {
        val parsed = PropertiesParser.parse("# comment\nserver.port=8080\n")
        assertEquals(2, parsed["server.port"]?.line)
    }

    @Test
    fun `joins continued lines and reports the first line number`() {
        val parsed = PropertiesParser.parse("long=first \\\n  second\nnext=x\n")
        assertEquals("first second", parsed["long"]?.value)
        assertEquals(1, parsed["long"]?.line)
        assertEquals("x", parsed["next"]?.value)
    }

    @Test
    fun `unescapes escape sequences`() {
        val result = values(
            """
            multi=line\nbreak
            tabbed=a\tb
            unicode=café
            """
        )

        assertEquals("line\nbreak", result["multi"])
        assertEquals("a\tb", result["tabbed"])
        assertEquals("café", result["unicode"])
    }

    @Test
    fun `keeps a colon inside a value`() {
        assertEquals("jdbc:h2:mem:test", values("url=jdbc:h2:mem:test")["url"])
    }

    @Test
    fun `handles a key with no value`() {
        assertEquals("", values("empty=")["empty"])
    }

    @Test
    fun `render round trips through parse`() {
        val original = mapOf("a.b" to "1", "c" to "two words", "d" to "line\nbreak")
        assertEquals(original, values(PropertiesParser.render(original)))
    }
}
