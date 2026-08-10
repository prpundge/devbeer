package com.devutility.strings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StringTransformsTest {

    private fun run(id: String, input: String): String {
        val transform = StringTransforms.byId(id)
        assertNotNull("no transform with id '$id'", transform)
        return transform!!.apply(input)
    }

    @Test
    fun `every transform has a unique id`() {
        val ids = StringTransforms.ALL.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `case conversions split on separators and camel humps`() {
        assertEquals("maxPoolSize", run("camel-case", "max_pool_size"))
        assertEquals("MaxPoolSize", run("pascal-case", "max pool size"))
        assertEquals("max_pool_size", run("snake-case", "maxPoolSize"))
        assertEquals("MAX_POOL_SIZE", run("constant-case", "maxPoolSize"))
        assertEquals("max-pool-size", run("kebab-case", "MaxPoolSize"))
        assertEquals("max.pool.size", run("dot-case", "max-pool-size"))
    }

    @Test
    fun `case conversion keeps acronyms together`() {
        assertEquals("http-server-name", run("kebab-case", "HTTPServerName"))
    }

    @Test
    fun `case conversion applies per line`() {
        assertEquals("first_name\nlast_name", run("snake-case", "firstName\nlastName"))
    }

    @Test
    fun `base64 round trips`() {
        val encoded = run("base64-encode", "héllo wörld")
        assertEquals("héllo wörld", run("base64-decode", encoded))
    }

    @Test
    fun `url safe base64 round trips`() {
        val encoded = run("base64url-encode", "a+b/c?d=e")
        assertEquals("a+b/c?d=e", run("base64url-decode", encoded))
    }

    @Test
    fun `hex round trips`() {
        assertEquals("DevBeer", run("hex-decode", run("hex-encode", "DevBeer")))
    }

    @Test
    fun `html escaping round trips`() {
        val escaped = run("html-escape", "<a href=\"x\">Tom & Jerry</a>")
        assertEquals("<a href=\"x\">Tom & Jerry</a>", run("html-unescape", escaped))
    }

    @Test
    fun `sha256 matches the known digest of an empty string`() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", run("sha256", ""))
    }

    @Test
    fun `json pretty print keeps empty containers on one line`() {
        val pretty = run("json-pretty", """{"a":1,"b":{},"c":[],"d":[1,2]}""")
        assertTrue(pretty, pretty.contains("\"b\": {}"))
        assertTrue(pretty, pretty.contains("\"c\": []"))
        assertEquals("""{"a":1,"b":{},"c":[],"d":[1,2]}""", run("json-minify", pretty))
    }

    @Test
    fun `json minify leaves whitespace inside strings alone`() {
        assertEquals("""{"a":"two  words"}""", run("json-minify", """{ "a" : "two  words" }"""))
    }

    @Test
    fun `natural sort orders numbers by value`() {
        assertEquals("item2\nitem10\nitem100", run("lines-sort-natural", "item100\nitem10\nitem2"))
    }

    @Test
    fun `sql in list quotes and escapes`() {
        assertEquals("'a', 'b''c'", run("lines-sql-in", "a\nb'c"))
    }

    @Test
    fun `properties and yaml convert both ways`() {
        val yaml = run("props-to-yaml", "server.port=8080\nserver.host=localhost")
        assertTrue(yaml, yaml.contains("server:"))
        assertTrue(yaml, yaml.contains("  port: 8080"))

        val properties = run("yaml-to-props", yaml)
        assertTrue(properties, properties.contains("server.port=8080"))
        assertTrue(properties, properties.contains("server.host=localhost"))
    }

    @Test
    fun `epoch converts in both directions`() {
        val iso = run("epoch-to-iso", "1700000000000")
        assertEquals("1700000000000", run("iso-to-epoch", iso))
    }

    @Test
    fun `report style transforms are kept out of the editor action`() {
        val editorSafe = StringTransforms.ALL.filter { it.editorSafe }.map { it.id }
        assertTrue(editorSafe.contains("base64-encode"))
        assertTrue(!editorSafe.contains("text-stats"))
        assertTrue(!editorSafe.contains("jwt-decode"))
    }

    @Test
    fun `unicode escaping round trips`() {
        assertEquals("naïve ☕", run("unicode-unescape", run("unicode-escape", "naïve ☕")))
    }
}
