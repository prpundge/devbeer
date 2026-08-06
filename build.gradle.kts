plugins {
    id("org.jetbrains.kotlin.jvm") version "1.9.22"
    id("org.jetbrains.intellij.platform") version "2.1.0"
}

fun prop(name: String): String = providers.gradleProperty(name).get()

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
            recommended()
        }
    }

    publishing {
        // We use an environment variable so we don't hardcode secret tokens into the git repository.
        token.set(System.getenv("JETBRAINS_PUBLISH_TOKEN"))
    }
}

kotlin {
    jvmToolchain(17)
}
