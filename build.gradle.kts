plugins {
    id("org.jetbrains.kotlin.jvm") version "1.9.22"
    id("org.jetbrains.intellij.platform") version "2.1.0"
}

fun prop(name: String): String = providers.gradleProperty(name).get()

/** Where a resolved publish token came from, so we can report it without printing the token. */
data class TokenSource(val origin: String, val token: String)

/**
 * Resolves the Marketplace publish token. In order:
 *
 *   1. the `JETBRAINS_PUBLISH_TOKEN` environment variable
 *   2. a `JETBRAINS_PUBLISH_TOKEN=...` line in a gitignored `.env` in the project root
 *   3. a `jetbrainsPublishToken` Gradle property — put it in `~/.gradle/gradle.properties`
 *      to keep it outside the repository entirely
 *
 * Returns null when none is set. Only `publishPlugin` needs a token, so every other task
 * must keep working without one.
 */
fun resolvePublishToken(): TokenSource? {
    System.getenv("JETBRAINS_PUBLISH_TOKEN")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.let { return TokenSource("JETBRAINS_PUBLISH_TOKEN environment variable", it) }

    val envFile = rootProject.file(".env")
    if (envFile.isFile) {
        for (raw in envFile.readLines()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val key = line.substringBefore('=', "").trim().removePrefix("export ").trim()
            if (key != "JETBRAINS_PUBLISH_TOKEN") continue
            val value = line.substringAfter('=', "").trim().trim('"', '\'')
            if (value.isNotEmpty() && !value.startsWith("<")) {
                return TokenSource(".env file", value)
            }
        }
    }

    return (findProperty("jetbrainsPublishToken") as String?)
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.let { TokenSource("jetbrainsPublishToken Gradle property", it) }
}

/** Reports whether a publish token is configured, without ever echoing its value. */
tasks.register("checkPublishToken") {
    group = "intellij platform"
    description = "Reports whether a Marketplace publish token is configured (never prints it)."
    doLast {
        val found = resolvePublishToken()
        if (found == null) {
            logger.lifecycle("No publish token found.")
            logger.lifecycle("  Put it in the .env file in the project root, or set")
            logger.lifecycle("  JETBRAINS_PUBLISH_TOKEN in your environment.")
            logger.lifecycle("  Generate one at https://plugins.jetbrains.com/author/me/tokens")
        } else {
            logger.lifecycle("Publish token found via ${found.origin} (${found.token.length} chars).")
            logger.lifecycle("Ready to run: gradlew publishPlugin")
        }
    }
}

group = "com.devutility"
version = prop("pluginVersion")

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    testImplementation(kotlin("stdlib"))
    testImplementation("junit:junit:4.13.2")

    intellijPlatform {
        intellijIdeaCommunity(prop("platformVersion"))
        // Java is an *optional* dependency of the plugin (see devutility-java.xml), but it
        // still has to be on the compile classpath to build the Spring inlay module.
        bundledPlugins("com.intellij.java")
        instrumentationTools()
        pluginVerifier()

        // Needed by the fixture-based tests that exercise the VFS- and PSI-dependent code
        // (ContextResolver, SourceLocationResolver) which plain unit tests cannot reach.
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
    }
}

intellijPlatform {
    pluginConfiguration {
        id = prop("pluginId")
        name = prop("pluginName")
        version = prop("pluginVersion")
        vendor {
            name = "prpundge"
            email = "pravins.exe@gmail.com"
        }
        ideaVersion {
            sinceBuild = prop("pluginSinceBuild")
            untilBuild = prop("pluginUntilBuild")
        }
    }

    // `gradlew verifyPlugin` runs the JetBrains Plugin Verifier against the whole
    // supported range. The Marketplace runs the same check on upload, so failing
    // locally is cheaper than failing there.
    pluginVerification {
        ides {
            // The oldest build we claim to support. Verify it explicitly rather than
            // trusting recommended(), which does not necessarily include the floor.
            ide(org.jetbrains.intellij.platform.gradle.IntelliJPlatformType.IntellijIdeaCommunity, "2023.1")

            // NOT verified here: a Java-less IDE such as PyCharm.
            //
            // Running `ide(PyCharmCommunity, "2023.3.7")` reports 27 "compatibility problems",
            // every one of them inside JavaSymbolEnricher or SpringValueInlayProvider — the two
            // classes that are *meant* to reference Java PSI and are guarded at runtime, one by
            // Class.forName and one by the optional devutility-java.xml descriptor. The verifier
            // cannot tell a guarded reference from a real one, so it would fail permanently and
            // the gate would stop meaning anything.
            //
            // The invariant that actually matters — no other class touches Java PSI — is
            // enforced by JavaIsolationTest, which reads the compiled bytecode. Re-add the line
            // above if you want to inspect the full report by hand.
            recommended()
        }
    }

    publishing {
        // Never hardcode the token here. See resolvePublishToken() above for the sources it
        // reads; all of them are outside version control. `gradlew checkPublishToken` says
        // which one was picked up without printing the value.
        token.set(providers.provider { resolvePublishToken()?.token })

        // `-PpublishChannel=beta` uploads to the beta channel. Omitted, it is the default (stable)
        // channel, exactly as before; the CI workflow is the only caller that sets it.
        channels.set(
            providers.gradleProperty("publishChannel").map { listOf(it) }.orElse(listOf("default")),
        )
    }
}

kotlin {
    jvmToolchain(17)
}
