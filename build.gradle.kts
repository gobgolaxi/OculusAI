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
// newer build exists and compares against this, so a jar reporting the wrong
// number can never know it is out of date.
//
// It comes from gradle.properties and from nowhere else. The previous arrangement
// - a command-line property with a literal fallback - meant a build without the
// flag stamped whatever number was hard-coded here months ago, and that number
// was still 1.1.1 at version 1.8.1. Failing is better than stamping a lie.
version = (project.findProperty("pluginVersion") as String?)
    ?.takeIf { it.isNotBlank() }
    ?: error("pluginVersion is not set - it belongs in gradle.properties")

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
        // The tests build stubs of the Bukkit types, so the API has to be on
        // the compile classpath as well, not only on the runtime one.
        testImplementation("io.papermc.paper:paper-api:$mcVersion-R0.1-SNAPSHOT")
    } else {
        compileOnly("com.destroystokyo.paper:paper-api:1.16.5-R0.1-SNAPSHOT")
        compileOnly("net.dmulloy2:ProtocolLib:5.4.0")
        testImplementation("com.destroystokyo.paper:paper-api:1.16.5-R0.1-SNAPSHOT")
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

// Folia as a second test server, next to `runServer`.
//
// The plugin's Folia support is all scheduler work - per entity, per region,
// per the thread that owns the thing being touched - and none of it can be
// proven by compiling. It needs the fork itself, so `./gradlew runFolia`
// brings one up with this jar in it.
runPaper {
    folia {
        registerTask()
    }
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
        // IPv4 принудительно: машина разработчика двустековая, и без этого
        // клиент ходит к сервису то с IPv4, то с IPv6. Ключ привязан к одному
        // адресу, так что половина запросов получала бы 001.
        jvmArgs("-Xms2G", "-Xmx2G", "-Djava.net.preferIPv4Stack=true")
        minecraftVersion(mcVersion)
    }

    named<xyz.jpenilla.runpaper.task.RunServer>("runFolia") {
        jvmArgs("-Xms2G", "-Xmx2G", "-Djava.net.preferIPv4Stack=true")
        minecraftVersion(mcVersion)
        // Своя папка, не общая с `runServer`: у Folia другой формат данных
        // региона, и мир, который тикал на Paper, ей отдавать нечестно - потом
        // не разберёшь, падение это плагина или смешанных данных.
        runDirectory.set(layout.projectDirectory.dir("run-folia"))
        // ProtocolLib - жёсткая зависимость, и своя папка начинается пустой.
        // Версия та же, что в compileOnly; её plugin.yml объявляет
        // folia-supported, иначе Folia не загрузила бы и наш плагин.
        downloadPlugins {
            hangar("ProtocolLib", "5.4.0")
        }
        doFirst {
            if (!modern) {
                error("Folia существует только для 1.20+, а эта сборка - $mcVersion."
                        + " Запускать так: ./gradlew runFolia -PmcVersion=1.21.11")
            }
        }
    }

    processResources {
        duplicatesStrategy = DuplicatesStrategy.INCLUDE
        // The stamp says which flavour this jar is, and the update check asks
        // the service for a build of that exact flavour. Both are project
        // properties, so Gradle has to be told they are inputs: without this
        // the task is "up to date" after the first build and every later jar
        // carries the stamp of the jar built before it.
        inputs.property("flavour", mcVersion)
        inputs.property("buildVersion", project.version.toString())
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
