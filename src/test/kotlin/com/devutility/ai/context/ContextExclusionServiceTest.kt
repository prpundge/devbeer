package com.devutility.ai.context

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextExclusionServiceTest {

    @Test
    fun `excludes generated and vcs directories`() {
        for (dir in listOf(".git", ".idea", ".gradle", "build", "out", "target", "node_modules", "dist", "coverage", "__pycache__")) {
            assertTrue("$dir should be excluded", ContextExclusionService.isExcludedDirectory(dir))
        }
    }

    @Test
    fun `detects an excluded directory anywhere in the path`() {
        assertTrue(ContextExclusionService.isInExcludedDirectory("/project/build/classes/Foo.class"))
        assertTrue(ContextExclusionService.isInExcludedDirectory("/project/node_modules/x/index.js"))
        assertFalse(ContextExclusionService.isInExcludedDirectory("/project/src/main/kotlin/Foo.kt"))
    }

    @Test
    fun `handles windows path separators`() {
        assertTrue(ContextExclusionService.isInExcludedDirectory("""C:\project\build\Foo.class"""))
        assertFalse(ContextExclusionService.isInExcludedDirectory("""C:\project\src\Foo.kt"""))
    }

    @Test
    fun `a directory name that merely contains an excluded word is kept`() {
        // "rebuild" is not "build"; segment matching must be exact.
        assertFalse(ContextExclusionService.isInExcludedDirectory("/project/rebuild/Foo.kt"))
        assertFalse(ContextExclusionService.isInExcludedDirectory("/project/outbound/Foo.kt"))
    }

    @Test
    fun `excludes secret files`() {
        for (name in listOf(
            ".env", ".env.local", ".env.production",
            "server.pem", "private.key", "store.p12", "keystore.jks",
            "credentials.json", "secrets.yml", "secret.txt",
            "id_rsa", "id_ed25519", "publish.token",
        )) {
            assertTrue("$name should be a secret file", ContextExclusionService.isSecretFile(name))
        }
    }

    @Test
    fun `does not treat ordinary source or config as secret`() {
        for (name in listOf("Application.java", "application.yml", "build.gradle.kts", "README.md")) {
            assertFalse("$name should not be a secret file", ContextExclusionService.isSecretFile(name))
        }
    }

    @Test
    fun `excludes binaries and archives`() {
        for (name in listOf("Foo.class", "lib.jar", "img.png", "doc.pdf", "native.dll", "yarn.lock")) {
            assertTrue("$name should be binary", ContextExclusionService.isBinary(name))
        }
        assertFalse(ContextExclusionService.isBinary("Foo.java"))
    }

    @Test
    fun `excludes files over the size ceiling`() {
        assertFalse(
            ContextExclusionService.isEligible("/p/src/Big.java", "Big.java", ContextExclusionService.MAX_FILE_BYTES + 1),
        )
        assertTrue(
            ContextExclusionService.isEligible("/p/src/Big.java", "Big.java", ContextExclusionService.MAX_FILE_BYTES - 1),
        )
    }

    @Test
    fun `eligibility combines every rule`() {
        assertTrue(ContextExclusionService.isEligible("/p/src/main/java/Foo.java", "Foo.java", 1000))
        assertFalse(ContextExclusionService.isEligible("/p/build/Foo.java", "Foo.java", 1000))
        assertFalse(ContextExclusionService.isEligible("/p/src/.env", ".env", 10))
        assertFalse(ContextExclusionService.isEligible("/p/src/Foo.class", "Foo.class", 10))
    }

    @Test
    fun `reports why a file was excluded`() {
        assertEquals(
            "generated or VCS directory",
            ContextExclusionService.reasonForExclusion("/p/build/Foo.java", "Foo.java"),
        )
        assertEquals(
            "matches a secret-file pattern",
            ContextExclusionService.reasonForExclusion("/p/src/.env", ".env"),
        )
        assertNull(ContextExclusionService.reasonForExclusion("/p/src/Foo.java", "Foo.java", 10))
    }
}
