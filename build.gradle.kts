plugins {
    id("org.jetbrains.kotlin.jvm") version "1.9.22"
    id("org.jetbrains.intellij.platform") version "2.1.0"
}

group = "com.plugin.springprops"
version = "1.0.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdeaCommunity("2024.1.4")
        bundledPlugins("com.intellij.java", "com.intellij.properties")
        instrumentationTools()
    }
}

intellijPlatform {
    pluginConfiguration {
        id = "com.plugin.springprops.viewer"
        name = "Spring Boot Property Viewer"
        version = "1.0.0"
        vendor {
            name = "Open Source"
        }
        ideaVersion {
            sinceBuild = "241"
            untilBuild = "242.*"
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
