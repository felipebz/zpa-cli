import org.jreleaser.model.Archive
import org.jreleaser.model.api.common.ArchiveOptions

group = "com.felipebz.zpa"
version = providers.gradleProperty("version").get()
val zpaVersion = providers.gradleProperty("zpaVersion").get()

plugins {
    `maven-publish`
    kotlin("jvm") version "2.4.21"
    application
    id("org.jreleaser") version "1.27.0"
    id("org.jreleaser.jdks") version "1.27.0"
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

repositories {
    mavenLocal()
    mavenCentral()
    maven {
        setUrl("https://central.sonatype.com/repository/maven-snapshots/")
    }
}

dependencies {
    implementation("org.jcommander:jcommander:3.0")
    implementation("com.felipebz.zpa:zpa-core:$zpaVersion")
    implementation("com.felipebz.zpa:zpa-checks:$zpaVersion")
    implementation("com.felipebz.zpa:zpa-oracle-metadata:$zpaVersion")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.23.0")
    implementation("org.pf4j:pf4j:3.16.1")
    implementation("org.slf4j:slf4j-jdk14:2.0.20")
    implementation("me.lucko:jar-relocator:1.7")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

// Database-backed tests: never part of `check`/`build`. They start an Oracle container (Testcontainers) or use the
// database named by ZPA_ORACLE_* environment variables (see README.md).
val oracleIntegrationTest by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output + configurations.testRuntimeClasspath.get()
    runtimeClasspath += output + compileClasspath
}

dependencies {
    "oracleIntegrationTestImplementation"(kotlin("test"))
    // The Oracle JDBC driver is never a runtime dependency of the CLI: users supply it in the `drivers` directory.
    "oracleIntegrationTestImplementation"("com.oracle.database.jdbc:ojdbc17:23.26.4.0.0")
    "oracleIntegrationTestImplementation"("org.testcontainers:testcontainers-oracle-free:2.0.5")
}

val oracleIntegrationTestTask = tasks.register<Test>("oracleIntegrationTest") {
    description = "Runs the Oracle database integration tests (needs Docker, or ZPA_ORACLE_* environment variables)."
    group = "verification"
    testClassesDirs = oracleIntegrationTest.output.classesDirs
    classpath = oracleIntegrationTest.runtimeClasspath
    useJUnitPlatform()
    outputs.upToDateWhen { false }
    systemProperty("zpa.oracle.image", providers.gradleProperty("zpaOracleImage").get())
}

application {
    mainClass.set("br.com.felipezorzo.zpa.cli.MainKt")
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            artifactId = "zpa-cli"
            from(components["java"])
            artifact(tasks["distZip"])
            versionMapping {
                usage("java-api") {
                    fromResolutionOf("runtimeClasspath")
                }
                usage("java-runtime") {
                    fromResolutionResult()
                }
            }
        }
    }
}

data class Jdk(val arch: String, val os: String, val extension: String, val platform: String = os) {
    fun fileName(build: String) = "bellsoft-jdk$build-$os-$arch.$extension"
}

val baseJdkUrl = "https://download.bell-sw.com/java"
val jdkBuild = "25.0.5.1+5"
val jdkVersion = jdkBuild.split('+').first()
val jdksToBuild = listOf(
    Jdk(arch = "amd64", os = "linux", extension = "tar.gz"),
    Jdk(arch = "aarch64", os = "linux", extension = "tar.gz"),
    Jdk(arch = "amd64", os = "macos", extension = "tar.gz", platform = "osx"),
    Jdk(arch = "aarch64", os = "macos", extension = "tar.gz", platform = "osx"),
    Jdk(arch = "amd64", os = "windows", extension = "zip"),
    Jdk(arch = "x64-musl", os = "linux", extension = "tar.gz", platform = "linux_musl"),
)

val jdkChecksums: Map<String, String> by lazy {
    uri("$baseJdkUrl/$jdkBuild/sha1sum.txt").toURL().readText().lines()
        .mapNotNull { line -> line.trim().split(Regex("\\s+"), limit = 2).takeIf { it.size == 2 } }
        .associate { (checksum, fileName) -> fileName.removePrefix("*") to checksum }
}

jdks {
    jdksToBuild.forEach {
        create("${it.platform}_${it.arch}") {
            platform.set(it.platform)
            url.set("$baseJdkUrl/$jdkBuild/${it.fileName(jdkBuild)}")
            checksum.set("SHA-1/0")
        }
    }
}

tasks.withType<de.undercouch.gradle.tasks.download.Verify>().configureEach {
    doFirst {
        val fileName = (src as File).name
        checksum(jdkChecksums[fileName] ?: throw GradleException("No checksum for $fileName in $baseJdkUrl/$jdkBuild/sha1sum.txt"))
    }
}

jreleaser {
    project {
        description.set("The command-line interface of the Z PL/SQL Analyzer.")
        authors.set(listOf("felipebz"))
        license.set("LGPL-3.0")
        links {
            homepage.set("https://zpa.felipebz.com")
        }
        inceptionYear.set("2019")
        snapshot {
            fullChangelog.set(true)
        }
    }
    assemble {
        jlink {
            create("zpa-cli") {
                active.set(org.jreleaser.model.Active.ALWAYS)
                exported.set(true)
                stereotype.set(org.jreleaser.model.Stereotype.CLI)
                imageName.set("{{distributionName}}-{{projectVersion}}")
                // java.sql for metadata acquisition; the others are needed by the user-supplied Oracle JDBC driver (jdeps on ojdbc17).
                moduleNames.set(listOf("java.logging", "java.xml", "java.sql", "java.naming", "java.management", "jdk.net", "jdk.security.jgss"))
                formats.set(setOf(Archive.Format.ZIP, Archive.Format.TAR_GZ))
                jdeps {
                    multiRelease.set("base")
                    ignoreMissingDeps.set(true)
                }
                jdksToBuild.forEach {
                    targetJdk {
                        val jreleaserOs = it.platform
                        val jreleaseArch = when (it.arch) {
                            "aarch64" -> "aarch_64"
                            "amd64" -> "x86_64"
                            "x64-musl" -> "x86_64"
                            else -> ""
                        }
                        val dirArch = when (it.arch) {
                            "x64-musl" -> "x64Musl"
                            else -> it.arch
                        }
                        val additionalDir = if (it.os == "macos") ".jdk" else ""
                        path.set(file("build/jdks/${jreleaserOs}_${dirArch}/jdk-$jdkVersion$additionalDir"))
                        platform.set("$jreleaserOs-$jreleaseArch")
                        extraProperties.put("skipTargz", jreleaserOs == "windows")
                        extraProperties.put("skipZip", jreleaserOs != "windows")
                        options {
                            longFileMode.set(ArchiveOptions.TarMode.POSIX)
                        }
                    }
                }
                java {
                    jvmOptions {
                        universal("-XX:+UseCompactObjectHeaders")
                    }
                }
                jdk {
                    val jdkPath = javaToolchains.launcherFor {
                        languageVersion.set(JavaLanguageVersion.of(25))
                    }.get().metadata.installationPath

                    path.set(file(jdkPath))
                }
                javaArchive {
                    path = "build/distributions/zpa-cli-{{projectVersion}}.tar"
                }
                fileSet {
                    input = "src/dist/plugins"
                    output = "plugins"
                    includes = listOf("*")
                }
                fileSet {
                    input = "src/dist/drivers"
                    output = "drivers"
                    includes = listOf("*")
                }
            }
        }
    }
    release {
        github {
            tagName.set("{{projectVersion}}")
            skipTag.set(true)
            skipRelease.set(true)
            token.set("changelog-only") // JReleaser validates the GitHub token even in changelog-only mode.
            changelog {
                formatted.set(org.jreleaser.model.Active.ALWAYS)
                preset.set("conventional-commits")
                contentTemplate.set(file("template/changelog.tpl"))
                extraProperties.put("zpaVersion", zpaVersion)
                contributors {
                    enabled.set(false)
                }
                hide {
                    uncategorized.set(true)
                }
            }
        }
    }
    distributions {
        create("zpa-cli") {
            artifact {
                path.set(file("build/distributions/{{distributionName}}-{{projectVersion}}.zip"))
            }
        }
    }
}
