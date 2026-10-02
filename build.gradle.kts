plugins {
    id("java-library")
    id("com.gradleup.shadow") version "9.0.0"
    id("xyz.jpenilla.run-paper") version "3.0.2"
}

val mcVersion: String = project.findProperty("mcVersion") as String? ?: "1.16.5"
val versionDir = when (mcVersion) {
    "1.21.4" -> "v121"
    else     -> "v116"
}

group = "me.everyone.yuppyai"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.codemc.io/repository/maven-releases/")
}

sourceSets {
    main {
        java.srcDirs("src/main/java", "src/$versionDir/java")
        resources.srcDirs("src/main/resources", "src/$versionDir/resources")
    }
}

dependencies {
    when (mcVersion) {
        "1.21.4" -> {
            compileOnly("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")
            compileOnly("net.dmulloy2:ProtocolLib:5.3.0")
            testRuntimeOnly("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")
        }
        else -> {
            compileOnly("com.destroystokyo.paper:paper-api:1.16.5-R0.1-SNAPSHOT")
            compileOnly("net.dmulloy2:ProtocolLib:5.4.0")
            testRuntimeOnly("com.destroystokyo.paper:paper-api:1.16.5-R0.1-SNAPSHOT")
        }
    }

    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // ApiManagerTest reads the plugin's own JSON, and gson reaches the main
    // source set only through paper-api, which is compileOnly. Without this the
    // whole test compilation fails, so no test in the project can run.
    testImplementation("com.google.code.gson:gson:2.10.1")
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(
        when (mcVersion) {
            "1.21.4" -> 21
            else     -> 17
        }
    )
}

tasks {
    test {
        useJUnitPlatform()
    }

    runServer {
        minecraftVersion(mcVersion)
        jvmArgs("-Xms2G", "-Xmx2G")
    }

    processResources {
        duplicatesStrategy = DuplicatesStrategy.INCLUDE
        filesMatching("plugin.yml") {
            expand("version" to project.version)
        }
    }

    shadowJar {
        archiveClassifier.set(mcVersion)
    }

    build {
        dependsOn(shadowJar)
    }

    jar {
        enabled = false
    }
}

// Build for a specific version:
//   gradlew shadowJar -PmcVersion=1.16.5
//   gradlew shadowJar -PmcVersion=1.21.4
// Build both (run twice):
//   gradlew shadowJar -PmcVersion=1.16.5 && gradlew shadowJar -PmcVersion=1.21.4
