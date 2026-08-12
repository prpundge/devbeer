package com.devutility.json

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class JsonFormatterTest {

    // ------------------------------------------------------------------ formatting

    @Test
    fun `pretty prints nested structures`() {
        val out = JsonFormatter.format("""{"a":1,"b":{"c":[1,2]}}""")
        assertEquals(
            """
            {
              "a": 1,
              "b": {
                "c": [
                  1,
                  2
                ]
              }
            }
            """.trimIndent(),
            out,
        )
    }

    @Test
    fun `keeps empty containers on one line`() {
        val out = JsonFormatter.format("""{"a":{},"b":[]}""")
        assertTrue(out, out.contains("\"a\": {}"))
        assertTrue(out, out.contains("\"b\": []"))
    }

    @Test
    fun `minify is the inverse of pretty`() {
        val original = """{"a":1,"b":{},"c":[],"d":[1,2],"e":"x"}"""
        assertEquals(original, JsonFormatter.minify(JsonFormatter.format(original)))
    }

    @Test
    fun `whitespace inside strings is preserved`() {
        assertEquals("""{"a":"two  words"}""", JsonFormatter.minify("""{ "a" : "two  words" }"""))
    }

    @Test
    fun `number formatting is lossless`() {
        // A double round trip would turn 1.0 into 1 and 1e3 into 1000.0; keeping the raw text does not.
        val original = """[1.0,1e3,0.1000,-0,2.5E-8]"""
        assertEquals(original, JsonFormatter.minify(original))
    }

    @Test
    fun `duplicate keys are preserved rather than collapsed`() {
        assertEquals("""{"a":1,"a":2}""", JsonFormatter.minify("""{"a":1,"a":2}"""))
    }

    @Test
    fun `sort keys orders every level`() {
        val out = JsonFormatter.minify(
            """{"b":1,"a":{"d":1,"c":2}}""",
            JsonFormatOptions(sortKeys = true),
        )
        assertEquals("""{"a":{"c":2,"d":1},"b":1}""", out)
    }

    @Test
    fun `indent is configurable`() {
        val out = JsonFormatter.format("""{"a":1}""", JsonFormatOptions(indent = "    "))
        assertTrue(out, out.contains("\n    \"a\": 1"))
    }

    // ------------------------------------------------------------------ escapes

    @Test
    fun `escapes round trip`() {
        val original = """{"s":"quote \" backslash \\ newline \n tab \t slash /"}"""
        val parsed = JsonParser.parse(original)
        val value = ((parsed as JsonValue.JsonObject).entries[0].value as JsonValue.JsonString).value
        assertEquals("quote \" backslash \\ newline \n tab \t slash /", value)
        // Re-emitting must produce something that parses back to the same string.
        val reparsed = JsonParser.parse(JsonFormatter.minify(parsed))
        assertEquals(parsed, reparsed)
    }

    @Test
    fun `control characters are escaped on output`() {
        val out = JsonFormatter.minify(JsonValue.JsonString("bellend"))
        assertEquals("\"bell\\u0007end\"", out)
    }

    @Test
    fun `unicode escapes are decoded`() {
        val parsed = JsonParser.parse(""""éA"""") as JsonValue.JsonString
        assertEquals("éA", parsed.value)
    }

    // ------------------------------------------------------------------ diagnostics

    @Test
    fun `validate accepts good json and rejects bad`() {
        assertNull(JsonFormatter.validate("""{"a":1}"""))
        assertNotNull(JsonFormatter.validate("""{"a":}"""))
    }

    @Test
    fun `syntax error reports the line and column`() {
        try {
            JsonFormatter.format("{\n  \"a\": 1,\n  \"b\": ,\n}")
            fail("expected a JsonSyntaxException")
        } catch (e: JsonSyntaxException) {
            assertEquals("error was: ${e.message}", 3, e.line)
            assertTrue(e.message!!, e.column > 0)
        }
    }

    @Test
    fun `unterminated string is reported`() {
        val error = JsonFormatter.validate("""{"a":"oops}""")
        assertNotNull(error)
        assertTrue(error!!.reason, error.reason.contains("Unterminated"))
    }

    @Test
    fun `trailing content is rejected`() {
        val error = JsonFormatter.validate("""{"a":1} {"b":2}""")
        assertNotNull(error)
        assertTrue(error!!.reason, error.reason.contains("trailing"))
    }

    // ------------------------------------------------------------------ lenient mode

    @Test
    fun `lenient mode accepts comments and trailing commas`() {
        val messy = """
            {
              // a line comment
              "a": 1,
              /* a block comment */
              "b": [1, 2,],
            }
        """.trimIndent()

        assertNotNull("strict mode should reject this", JsonFormatter.validate(messy))

        val out = JsonFormatter.minify(messy, JsonFormatOptions(lenient = true))
        assertEquals("""{"a":1,"b":[1,2]}""", out)
    }

    @Test
    fun `lenient mode accepts single quotes`() {
        val out = JsonFormatter.minify("""{'a':'x'}""", JsonFormatOptions(lenient = true))
        assertEquals("""{"a":"x"}""", out)
    }

    @Test
    fun `strict mode rejects single quotes`() {
        assertNotNull(JsonFormatter.validate("""{'a':1}"""))
    }

    // ------------------------------------------------------------------ edge cases

    @Test
    fun `top level scalars are valid json`() {
        assertEquals("42", JsonFormatter.minify("42"))
        assertEquals("\"x\"", JsonFormatter.minify("\"x\""))
        assertEquals("true", JsonFormatter.minify("true"))
        assertEquals("null", JsonFormatter.minify("null"))
    }

    @Test
    fun `empty input is an error rather than an exception without a position`() {
        val error = JsonFormatter.validate("   ")
        assertNotNull(error)
        assertEquals(1, error!!.line)
    }

    @Test
    fun `deeply nested input does not blow the stack`() {
        val depth = 400
        val deep = "[".repeat(depth) + "]".repeat(depth)
        assertEquals(depth, JsonFormatter.minify(deep).count { it == '[' })
    }

    @Test
    fun `nesting beyond the limit is reported rather than crashing`() {
        val deep = "[".repeat(2000) + "]".repeat(2000)
        val error = JsonFormatter.validate(deep)
        assertNotNull(error)
        assertTrue(error!!.reason, error.reason.contains("Nesting"))
    }
}
