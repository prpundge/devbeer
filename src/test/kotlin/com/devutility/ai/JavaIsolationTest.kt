package com.devutility.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Enforces the architectural invariant that lets DevBeer run in IDEs without Java support.
 *
 * DevBeer declares `com.intellij.modules.java` optional. A class whose signatures or bodies
 * reference `PsiClass`, `PsiMethod` or `JavaPsiFacade` cannot be *loaded* where that module is
 * absent — it fails at link time with `NoClassDefFoundError`, before any runtime guard could
 * run. So exactly one class in the AI layer is permitted to touch Java PSI, and it is
 * instantiated reflectively behind an availability check.
 *
 * This is checked by reading the compiled bytecode rather than the source, because it is the
 * constant pool that decides linkage. Verifying against PyCharm would also catch it, but the
 * Plugin Verifier cannot distinguish a guarded reference from a real defect and so reports
 * both — this test reports only the regression.
 */
class JavaIsolationTest {

    /** Types that only exist when the Java module is loaded. */
    private val javaPsiTypes = listOf(
        "com/intellij/psi/PsiClass",
        "com/intellij/psi/PsiMethod",
        "com/intellij/psi/PsiField",
        "com/intellij/psi/JavaPsiFacade",
        "com/intellij/psi/PsiMethodCallExpression",
        "com/intellij/psi/PsiModifierListOwner",
        "com/intellij/psi/search/PsiShortNamesCache",
        "com/intellij/psi/PsiJavaFile",
        "com/intellij/psi/PsiAnnotation",
    )

    /** The only classes allowed to reference them, each guarded at runtime. */
    private val permitted = listOf(
        "com/devutility/ai/context/JavaSymbolEnricher",
        // Pre-existing Spring module: registered solely in the optional devutility-java.xml.
        "com/devutility/springprops/SpringValueInlayProvider",
    )

    private fun classesRoot(): File? =
        listOf("build/classes/kotlin/main", "build/classes/java/main")
            .map { File(it) }
            .firstOrNull { it.isDirectory }

    @Test
    fun `only the designated classes reference java psi`() {
        val root = classesRoot()
        assumeTrue("compiled classes not available; run after compileKotlin", root != null)

        val offenders = mutableMapOf<String, MutableSet<String>>()

        root!!.walkTopDown()
            .filter { it.isFile && it.extension == "class" }
            .forEach { file ->
                val internalName = file.relativeTo(root).path.replace('\\', '/').removeSuffix(".class")
                if (permitted.any { internalName.startsWith(it) }) return@forEach

                // Type names live as UTF-8 constants in the pool, so a byte search is exact
                // enough and needs no bytecode library.
                val bytes = file.readBytes()
                val text = String(bytes, Charsets.ISO_8859_1)
                for (type in javaPsiTypes) {
                    if (text.contains(type)) {
                        offenders.getOrPut(internalName) { mutableSetOf() } += type
                    }
                }
            }

        assertTrue(
            buildString {
                append("These classes reference Java PSI but are not guarded, so they would fail\n")
                append("to load in an IDE without Java support:\n\n")
                offenders.forEach { (cls, types) ->
                    append("  ").append(cls).append("\n    ").append(types.joinToString(", ")).append('\n')
                }
                append("\nMove the Java PSI use into JavaSymbolEnricher, or add the class to the\n")
                append("optional devutility-java.xml descriptor.")
            },
            offenders.isEmpty(),
        )
    }

    @Test
    fun `the ai layer entry points are free of java psi`() {
        val root = classesRoot()
        assumeTrue("compiled classes not available", root != null)

        // Spot-check the classes a non-Java IDE loads eagerly: services, UI and actions.
        val mustBeClean = listOf(
            "com/devutility/ai/AiService",
            "com/devutility/ai/context/ContextResolver",
            "com/devutility/ai/context/SymbolEnricher",
            "com/devutility/ai/error/SourceLocationResolver",
            "com/devutility/ai/ui/AiToolWindowPanel",
            "com/devutility/ai/action/AskDevBeerAction",
            "com/devutility/ai/settings/DevBeerAiSettings",
        )

        for (name in mustBeClean) {
            val file = File(root, "$name.class")
            if (!file.isFile) continue
            val text = String(file.readBytes(), Charsets.ISO_8859_1)
            val found = javaPsiTypes.filter { text.contains(it) }
            assertEquals("$name must not reference Java PSI, found: $found", emptyList<String>(), found)
        }
    }

    @Test
    fun `the reflective enricher class name resolves`() {
        // SymbolEnricher loads JavaSymbolEnricher by name and silently falls back to the noop
        // on any failure — which is right for robustness but means a typo in that string would
        // permanently disable Java support with no error anywhere. Assert the name is real.
        val loaded = Class.forName("com.devutility.ai.context.JavaSymbolEnricher")
        assertTrue(
            "JavaSymbolEnricher must implement SymbolEnricher for the reflective cast to work",
            com.devutility.ai.context.SymbolEnricher::class.java.isAssignableFrom(loaded),
        )
        assertTrue("it must have a no-arg constructor", loaded.getDeclaredConstructor() != null)
    }

    @Test
    fun `the fallback enricher reports itself unavailable`() {
        assertTrue(!com.devutility.ai.context.NoopSymbolEnricher.available)
    }

    @Test
    fun `java availability detection does not throw`() {
        // Called during service construction; an exception here would take the AI layer down.
        com.devutility.ai.context.SymbolEnricher.isJavaAvailable()
        assertTrue(com.devutility.ai.context.SymbolEnricher.get().let { true })
    }
}
