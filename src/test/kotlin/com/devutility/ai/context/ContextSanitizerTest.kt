package com.devutility.ai.context

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextSanitizerTest {

    private fun assertRedacted(input: String, vararg mustNotContain: String) {
        val result = ContextSanitizer.sanitize(input)
        assertTrue("nothing was redacted in: $input", result.redactions > 0)
        for (secret in mustNotContain) {
            assertFalse("secret survived: $secret\nin: ${result.text}", result.text.contains(secret))
        }
    }

    @Test
    fun `redacts assignments to key-shaped names`() {
        assertRedacted("""val apiKey = "sk-abcdefghijklmnopqrstuv"""", "sk-abcdefghijklmnopqrstuv")
        assertRedacted("""password: hunter2secret""", "hunter2secret")
        assertRedacted("""spring.datasource.password=Sup3rS3cret!""", "Sup3rS3cret")
        assertRedacted("""ACCESS_TOKEN=abcd1234efgh5678""", "abcd1234efgh5678")
    }

    @Test
    fun `redacts provider specific key shapes`() {
        assertRedacted("Use sk-1234567890abcdefghij for the call", "sk-1234567890abcdefghij")
        assertRedacted("token ghp_ABCDEFGHIJKLMNOPQRSTUVWXYZ012345", "ghp_ABCDEFGHIJKLMNOPQRSTUVWXYZ012345")
        assertRedacted("AKIAIOSFODNN7EXAMPLE is the id", "AKIAIOSFODNN7EXAMPLE")
        assertRedacted("xoxb-123456789012-abcdefghijkl", "xoxb-123456789012-abcdefghijkl")
    }

    @Test
    fun `redacts the jetbrains publish token shape`() {
        // Every segment is invented. Fixtures must never carry material copied from a real
        // credential, even a revoked one and even the non-secret identifier segments.
        assertRedacted(
            "perm-ZXhhbXBsZS51c2Vy.MDAtMDAwMDA=.EXAMPLEONLYNOTREAL01",
            "EXAMPLEONLYNOTREAL01",
        )
    }

    @Test
    fun `redacts authorization headers`() {
        assertRedacted("Authorization: Bearer abcdefghijklmnopqrst", "abcdefghijklmnopqrst")
        assertRedacted("Authorization: Basic dXNlcjpwYXNzd29yZA==", "dXNlcjpwYXNzd29yZA==")
    }

    @Test
    fun `redacts credentials embedded in a url`() {
        val result = ContextSanitizer.sanitize("jdbc:postgresql://admin:s3cr3tpw@db.internal:5432/app")
        assertFalse(result.text, result.text.contains("s3cr3tpw"))
        // The host and user survive — they are the useful part for diagnosing a connection bug.
        assertTrue(result.text, result.text.contains("db.internal"))
        assertTrue(result.text, result.text.contains("admin"))
    }

    @Test
    fun `redacts a whole private key block`() {
        val pem = """
            -----BEGIN RSA PRIVATE KEY-----
            MIIEowIBAAKCAQEAxyz1234secretmaterial
            morekeymaterialhere
            -----END RSA PRIVATE KEY-----
        """.trimIndent()
        assertRedacted(pem, "MIIEowIBAAKCAQEAxyz1234secretmaterial", "morekeymaterialhere")
    }

    @Test
    fun `redacts jwts`() {
        assertRedacted(
            "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dBjftJeZ4CVPmB92K27uhbUJU1p1r",
            "dBjftJeZ4CVPmB92K27uhbUJU1p1r",
        )
    }

    @Test
    fun `leaves ordinary code untouched`() {
        val code = """
            public class PaymentService {
                private final PaymentRepository repository;
                public Payment create(PaymentRequest request) {
                    return repository.save(new Payment(request.amount()));
                }
            }
        """.trimIndent()
        val result = ContextSanitizer.sanitize(code)
        assertEquals(0, result.redactions)
        assertEquals(code, result.text)
    }

    @Test
    fun `keeps the shape of the code it redacts`() {
        // The model still needs to see that a key is assigned, just not what it is.
        val result = ContextSanitizer.sanitize("""String apiKey = "sk-abcdefghijklmnopqrst";""")
        assertTrue(result.text, result.text.contains("apiKey"))
        assertTrue(result.text, result.text.contains(ContextSanitizer.REDACTED))
    }

    @Test
    fun `reports which rules fired`() {
        val result = ContextSanitizer.sanitize("""apiKey = "sk-abcdefghijklmnopqrst"""")
        assertTrue(result.rulesTriggered.toString(), result.rulesTriggered.isNotEmpty())
    }

    @Test
    fun `containsSecret is a usable predicate`() {
        assertTrue(ContextSanitizer.containsSecret("""password = "letmein12345""""))
        assertFalse(ContextSanitizer.containsSecret("int total = amount + tax;"))
    }

    @Test
    fun `empty input is handled`() {
        val result = ContextSanitizer.sanitize("")
        assertEquals("", result.text)
        assertEquals(0, result.redactions)
    }
}
