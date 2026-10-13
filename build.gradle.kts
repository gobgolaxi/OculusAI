plugins {
    id("java-library")
    id("com.gradleup.shadow") version "9.0.0"
    id("xyz.jpenilla.run-paper") version "3.0.2"
}

val mcVersion: String = project.findProperty("mcVersion") as String? ?: "1.16.5"
val versionDir = if (mcVersion.startsWith("1.21")) "v121" else "v116"
val modern = versionDir == "v121"

group = "me.everyone.oculusai"
// The real version, not a placeholder: the plugin asks the service whether a
// newer build exists and compares against this, so a jar reporting
// 1.0-SNAPSHOT forever can never know it is out of date. Override per build
// with -PpluginVersion=1.3.9.
version = (project.findProperty("pluginVersion") as String? ?: "1.3.8")

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
    if (modern) {
        compileOnly("io.papermc.paper:paper-api:$mcVersion-R0.1-SNAPSHOT")
        compileOnly("net.dmulloy2:ProtocolLib:5.4.0")
        testRuntimeOnly("io.papermc.paper:paper-api:$mcVersion-R0.1-SNAPSHOT")
    } else {
        compileOnly("com.destroystokyo.paper:paper-api:1.16.5-R0.1-SNAPSHOT")
        compileOnly("net.dmulloy2:ProtocolLib:5.4.0")
        testRuntimeOnly("com.destroystokyo.paper:paper-api:1.16.5-R0.1-SNAPSHOT")
    }

    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("com.google.code.gson:gson:2.10.1")
}

val release = if (modern) 21 else 16

java {
    toolchain.languageVersion = JavaLanguageVersion.of(21)
}

configurations.compileClasspath {
    attributes {
        attribute(org.gradle.api.attributes.java.TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 21)
    }
}
configurations.testCompileClasspath {
    attributes {
        attribute(org.gradle.api.attributes.java.TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 21)
    }
}

tasks {
    withType<JavaCompile>().configureEach {
        options.release.set(release)
    }

    test {
        useJUnitPlatform()
    }

    runServer {
        minecraftVersion(mcVersion)
        jvmArgs("-Xms2G", "-Xmx2G")
    }

    processResources {
        duplicatesStrategy = DuplicatesStrategy.INCLUDE
        // `flavour` is which of the two jars this is. The update check has to
        // ask for its own flavour by name - handing a 1.21 build to a 1.16
        // server would be worse than not updating at all - and the build is
        // the only thing that knows for certain which tree went in.
        filesMatching(listOf("plugin.yml", "build.properties")) {
            expand("version" to project.version, "flavour" to mcVersion)
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
