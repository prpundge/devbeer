package com.devutility.props

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class YamlParserTest {

    private fun values(yaml: String): Map<String, String> =
        YamlParser.parse(yaml.trimIndent()).mapValues { it.value.value }

    @Test
    fun `flattens nested maps into dotted keys`() {
        val result = values(
            """
            spring:
              datasource:
                url: jdbc:postgresql://localhost:5432/app
                username: admin
              jpa:
                hibernate:
                  ddl-auto: validate
            server:
              port: 8080
            """
        )

        assertEquals("jdbc:postgresql://localhost:5432/app", result["spring.datasource.url"])
        assertEquals("admin", result["spring.datasource.username"])
        assertEquals("validate", result["spring.jpa.hibernate.ddl-auto"])
        assertEquals("8080", result["server.port"])
    }

    @Test
    fun `records one-based line numbers`() {
        val parsed = YamlParser.parse("server:\n  port: 8080\n")
        assertEquals(2, parsed["server.port"]?.line)
    }

    @Test
    fun `dedents correctly when returning to a shallower level`() {
        // The old parser reset its state on any dedent and lost every key after the first
        // nested block. This is the regression that motivated the rewrite.
        val result = values(
            """
            a:
              b:
                c: 1
            d:
              e: 2
            f: 3
            """
        )

        assertEquals("1", result["a.b.c"])
        assertEquals("2", result["d.e"])
        assertEquals("3", result["f"])
    }

    @Test
    fun `does not confuse a similarly named key in another branch`() {
        val result = values(
            """
            first:
              port: 1111
            second:
              port: 2222
            """
        )

        assertEquals("1111", result["first.port"])
        assertEquals("2222", result["second.port"])
    }

    @Test
    fun `indexes scalar sequences`() {
        val result = values(
            """
            hosts:
              - alpha
              - beta
              - gamma
            """
        )

        assertEquals("alpha", result["hosts[0]"])
        assertEquals("beta", result["hosts[1]"])
        assertEquals("gamma", result["hosts[2]"])
    }

    @Test
    fun `indexes sequences of maps`() {
        val result = values(
            """
            servers:
              - name: alpha
                port: 1
                tls:
                  enabled: true
              - name: beta
                port: 2
            """
        )

        assertEquals("alpha", result["servers[0].name"])
        assertEquals("1", result["servers[0].port"])
        assertEquals("true", result["servers[0].tls.enabled"])
        assertEquals("beta", result["servers[1].name"])
        assertEquals("2", result["servers[1].port"])
    }

    @Test
    fun `later documents win in a multi document file`() {
        val result = values(
            """
            server:
              port: 8080
            ---
            spring:
              config:
                activate:
                  on-profile: prod
            server:
              port: 9090
            """
        )

        assertEquals("9090", result["server.port"])
        assertEquals("prod", result["spring.config.activate.on-profile"])
    }

    @Test
    fun `keeps colons and hashes inside values`() {
        val result = values(
            """
            url: http://example.com/path#fragment
            note: value  # this part is a comment
            quoted: "text: with a colon"
            """
        )

        assertEquals("http://example.com/path#fragment", result["url"])
        assertEquals("value", result["note"])
        assertEquals("text: with a colon", result["quoted"])
    }

    @Test
    fun `reads block scalars`() {
        val result = values(
            """
            literal: |
              line one
              line two
            folded: >
              word one
              word two
            after: done
            """
        )

        assertEquals("line one\nline two", result["literal"])
        assertEquals("word one word two", result["folded"])
        assertEquals("done", result["after"])
    }

    @Test
    fun `reads flow collections`() {
        val result = values(
            """
            list: [a, b, c]
            map: {x: 1, y: 2}
            """
        )

        assertEquals("a", result["list[0]"])
        assertEquals("c", result["list[2]"])
        assertEquals("1", result["map.x"])
        assertEquals("2", result["map.y"])
    }

    @Test
    fun `ignores comment lines and blank lines`() {
        val result = values(
            """
            # a leading comment

            server:
              # an indented comment
              port: 8080
            """
        )

        assertEquals(1, result.size)
        assertEquals("8080", result["server.port"])
    }

    @Test
    fun `render rebuilds a nested tree from flat keys`() {
        val original = """
            spring:
              datasource:
                url: jdbc:h2:mem:test
            hosts:
              - alpha
              - beta
        """.trimIndent()

        val flat = values(original)
        val rendered = YamlParser.render(flat)

        // The round trip has to preserve every key and value.
        assertEquals(flat, values(rendered))
        assertTrue("expected an indented tree, got:\n$rendered", rendered.contains("\n    url:"))
    }
}
