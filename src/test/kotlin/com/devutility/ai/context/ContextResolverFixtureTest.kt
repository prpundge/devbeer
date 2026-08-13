package com.devutility.ai.context

import com.devutility.ai.error.SourceLocationResolver
import com.devutility.ai.model.CodeLocation
import com.devutility.ai.settings.ContextBudget
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Exercises the parts that need a real project: index-backed search, file resolution and
 * location verification.
 *
 * These were the acknowledged gap in the unit suite — everything either side of them was
 * covered, but nothing proved that the resolver actually finds a file or that an invented
 * path is rejected. Uses IntelliJ's own fixtures, so it runs headless with the rest (§28).
 */
class ContextResolverFixtureTest : BasePlatformTestCase() {

    private fun seedProject() {
        myFixture.addFileToProject(
            "src/main/java/com/example/PaymentValidator.java",
            """
            package com.example;

            public class PaymentValidator {
                public void validate(Payment payment) {
                    if (payment == null) throw new IllegalArgumentException("payment");
                }
            }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "src/main/java/com/example/PaymentService.java",
            """
            package com.example;

            public class PaymentService {
                private final PaymentValidator validator = new PaymentValidator();
                public void create(Payment payment) {
                    validator.validate(payment);
                }
            }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "src/main/resources/application.properties",
            "server.port=8080\npayment.enabled=true\n",
        )
    }

    // ------------------------------------------------------------------ SourceLocationResolver

    fun `test finds a file by bare name`() {
        seedProject()
        val file = SourceLocationResolver.findFile(project, "PaymentValidator.java")
        assertNotNull("PaymentValidator.java should be found by name", file)
        assertEquals("PaymentValidator.java", file!!.name)
    }

    fun `test finds a file by relative path`() {
        seedProject()
        val file = SourceLocationResolver.findFile(project, "com/example/PaymentService.java")
        assertNotNull("should resolve via the path suffix", file)
        assertEquals("PaymentService.java", file!!.name)
    }

    fun `test returns null for a file that does not exist`() {
        seedProject()
        assertNull(SourceLocationResolver.findFile(project, "TotallyInvented.java"))
    }

    fun `test verifies a real location and marks it navigable`() {
        seedProject()
        val resolved = SourceLocationResolver.verify(project, CodeLocation("PaymentValidator.java", 4))

        assertTrue("a real file and line must verify", resolved.isNavigable)
        assertTrue(resolved.location.verified)
        assertNotNull(resolved.file)
    }

    fun `test refuses to verify an invented file`() {
        seedProject()
        // The property that makes AI navigation trustworthy: a hallucinated path must never
        // become a clickable link.
        val resolved = SourceLocationResolver.verify(project, CodeLocation("Imaginary.java", 42))

        assertFalse("an invented file must not verify", resolved.isNavigable)
        assertFalse(resolved.location.verified)
        assertNull(resolved.file)
    }

    fun `test refuses a line past the end of a real file`() {
        seedProject()
        val resolved = SourceLocationResolver.verify(project, CodeLocation("PaymentValidator.java", 9999))

        assertFalse("an out-of-range line must not verify", resolved.isNavigable)
        assertFalse(resolved.location.verified)
    }

    fun `test accepts a file-only location with no line`() {
        seedProject()
        val resolved = SourceLocationResolver.verify(project, CodeLocation("PaymentValidator.java", 0))
        assertTrue("line 0 means the file itself, which is still navigable", resolved.isNavigable)
    }

    // ------------------------------------------------------------------ ContextResolver

    fun `test resolves context for a question about the project`() {
        seedProject()
        val resolution = ContextResolver.resolve(project, "Where is payment validation handled?", ContextBudget())

        assertTrue(
            "expected at least one candidate, got ${resolution.selected.size}",
            resolution.selected.isNotEmpty(),
        )
        val paths = resolution.selected.map { it.candidate.fileName }
        assertTrue("PaymentValidator should surface, got $paths", paths.any { it.contains("Payment") })
    }

    fun `test context carries real snippets and line numbers`() {
        seedProject()
        val resolution = ContextResolver.resolve(project, "PaymentValidator", ContextBudget())
        val candidate = resolution.selected.firstOrNull()?.candidate

        assertNotNull("expected a candidate", candidate)
        assertTrue("snippet should not be empty", candidate!!.text.isNotEmpty())
        assertTrue("line should be positive, was ${candidate.line}", candidate.line > 0)
    }

    fun `test respects the file budget`() {
        seedProject()
        val resolution = ContextResolver.resolve(
            project,
            "payment validate service",
            ContextBudget(maxFiles = 1),
        )
        val distinctFiles = resolution.selected.map { it.candidate.path }.distinct()
        assertTrue("budget of 1 file exceeded: $distinctFiles", distinctFiles.size <= 1)
    }

    fun `test a query of only stop words returns nothing rather than everything`() {
        seedProject()
        val resolution = ContextResolver.resolve(project, "how does the code work", ContextBudget())
        assertTrue("stop-word-only query must not dump the repository", resolution.selected.isEmpty())
    }

    fun `test excluded directories are not offered as context`() {
        seedProject()
        myFixture.addFileToProject(
            "build/generated/PaymentGenerated.java",
            "package com.example; public class PaymentGenerated { void validate() {} }",
        )
        val resolution = ContextResolver.resolve(project, "PaymentGenerated validate", ContextBudget())

        assertTrue(
            "generated output must never be sent",
            resolution.selected.none { it.candidate.path.contains("/build/") },
        )
    }

    fun `test secret files are not offered as context`() {
        seedProject()
        myFixture.addFileToProject(".env", "PAYMENT_API_KEY=sk-abcdefghijklmnopqrst\n")
        val resolution = ContextResolver.resolve(project, "payment api key", ContextBudget())

        assertTrue(
            "a .env file must never be sent",
            resolution.selected.none { it.candidate.fileName == ".env" },
        )
    }

    // ------------------------------------------------------------------ code flow (§14)

    fun `test code flow is resolved from the project model, not invented`() {
        myFixture.addFileToProject(
            "src/OrderRepository.java",
            "public class OrderRepository {\n    public void save(Object o) {}\n}\n",
        )
        myFixture.addFileToProject(
            "src/OrderService.java",
            """
            public class OrderService {
                private final OrderRepository repository = new OrderRepository();
                public void placeOrder(Object order) {
                    repository.save(order);
                }
            }
            """.trimIndent(),
        )

        val nodes = com.devutility.ai.flow.CodeFlowAnalyzer.resolve(
            project,
            "placeOrder",
            com.devutility.ai.settings.ContextBudget(),
        )
        val symbols = nodes.map { it.symbol }

        assertTrue("expected a flow for placeOrder, got $symbols", nodes.isNotEmpty())
        assertTrue(
            "the entry point should be placeOrder, got ${nodes.first().symbol}",
            nodes.first().symbol.contains("placeOrder"),
        )
        // The chain must actually be followed, not just the entry point echoed back.
        assertTrue("the flow should reach save, got $symbols", symbols.any { it.contains("save") })
        // Every node must carry a location that actually exists — that is the whole point.
        nodes.forEach { node ->
            assertNotNull("node ${node.symbol} has no location", node.location)
            assertTrue("node ${node.symbol} was not verified", node.location!!.verified)
        }
    }

    fun `test code flow follows a call into another class`() {
        // No package declaration, and files directly under the fixture's source root: a
        // package/directory mismatch stops the callee resolving, which is a defect in the
        // test setup rather than in the analyzer.
        myFixture.addFileToProject(
            "src/Repo.java",
            "public class Repo {\n    public void store() {}\n}\n",
        )
        myFixture.addFileToProject(
            "src/Svc.java",
            """
            public class Svc {
                private final Repo repo = new Repo();
                public void handle() {
                    repo.store();
                }
            }
            """.trimIndent(),
        )

        val symbols = com.devutility.ai.flow.CodeFlowAnalyzer
            .resolve(project, "handle", com.devutility.ai.settings.ContextBudget())
            .map { it.symbol }

        assertTrue("expected handle then store, got $symbols", symbols.any { it.contains("handle") })
        assertTrue("the called method should appear, got $symbols", symbols.any { it.contains("store") })
    }

    fun `test an unknown method yields no flow rather than a fabricated one`() {
        seedProject()
        val nodes = com.devutility.ai.flow.CodeFlowAnalyzer.resolve(
            project,
            "methodThatDoesNotExistAnywhere",
            com.devutility.ai.settings.ContextBudget(),
        )
        assertTrue("an unknown method must produce nothing, got ${nodes.map { it.symbol }}", nodes.isEmpty())
    }
}
