import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jreleaser.model.api.common.ArchiveOptions

group = "com.felipebz.zpa"
version = "3.1.0-SNAPSHOT"

plugins {
    `maven-publish`
    kotlin("jvm") version "2.3.10"
    application
    id("org.jreleaser") version "1.22.0"
    id("org.jreleaser.jdks") version "1.22.0"
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

repositories {
    mavenLocal()
    mavenCentral()
    maven {
        setUrl("https://s01.oss.sonatype.org/content/repositories/snapshots/")
    }
}

dependencies {
    implementation("org.jcommander:jcommander:3.0")
    implementation("com.felipebz.zpa:zpa-core:4.0.0")
    implementation("com.felipebz.zpa:zpa-checks:4.0.0")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.21.0")
    implementation("org.pf4j:pf4j:3.15.0")
    implementation("org.slf4j:slf4j-jdk14:2.0.17")
    implementation("me.lucko:jar-relocator:1.7")
    testImplementation(kotlin("test"))
}

application {
    mainClass.set("br.com.felipezorzo.zpa.cli.MainKt")
}

publishing {
    repositories {
        maven {
            val releaseRepo = uri("https://s01.oss.sonatype.org/service/local/staging/deploy/maven2/")
            val snapshotRepo = uri("https://s01.oss.sonatype.org/content/repositories/snapshots/")
            url = if (project.version.toString().endsWith("SNAPSHOT")) snapshotRepo else releaseRepo
            credentials {
                username = project.findProperty("ossrh.user") as String? ?: System.getenv("OSSRH_USERNAME")
                password = project.findProperty("ossrh.password") as String? ?: System.getenv("OSSRH_PASSWORD")
            }
        }
    }
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

data class Jdk(val arch: String, val os: String, val extension: String, val checksum: String, val platform: String = os)

val baseJdkUrl = "https://download.bell-sw.com/java"
val jdkBuild = "25+37"
val jdkVersion = jdkBuild.split('.', '+').first()
val jdksToBuild = listOf(
    Jdk(
        arch = "amd64",
        os = "linux",
        extension = "tar.gz",
        checksum = "227e712de039721f59b69a18e5f7f3eaffb4ee1d"
    ),

    Jdk(
        arch = "aarch64",
        os = "linux",
        extension = "tar.gz",
        checksum = "eb4ef3e14fdbec3923dcbb66b7e94a9d9f2233ac"
    ),

    Jdk(
        arch = "amd64",
        os = "macos",
        extension = "tar.gz",
        checksum = "8c7382ddba61ecda4d8e8140adfc12672e3be9a1",
        platform = "osx"
    ),

    Jdk(
        arch = "aarch64",
        os = "macos",
        extension = "tar.gz",
        checksum = "ea5b2adf6d597708aeec750e4a26ed277520df21",
        platform = "osx"
    ),

    Jdk(
        arch = "amd64",
        os = "windows",
        extension = "zip",
        checksum = "7a62e1df0da604723115acfb3240a79cd1261771"
    ),

    Jdk(
        arch = "x64-musl",
        os = "linux",
        extension = "tar.gz",
        checksum = "52c62204a06c73c91147d34b1319858f2b5e1fc8",
        platform = "linux_musl"
    ),
)

jdks {
    jdksToBuild.forEach {
        create("${it.platform}_${it.arch}") {
            platform.set(it.platform)
            url.set("$baseJdkUrl/$jdkBuild/bellsoft-jdk${jdkBuild}-${it.os}-${it.arch}.${it.extension}")
            checksum.set("SHA-1/${it.checksum}")
        }
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
                moduleNames.set(listOf("java.logging", "java.xml"))
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
                        extraProperties.put("archiveFormat", if (jreleaserOs == "windows") "ZIP" else "TAR_GZ")
                        options {
                            longFileMode.set(ArchiveOptions.TarMode.POSIX)
                        }
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
            }
        }
    }
    release {
        github {
            overwrite.set(true)
            tagName.set("{{projectVersion}}")
            draft.set(true)
            changelog {
                formatted.set(org.jreleaser.model.Active.ALWAYS)
                preset.set("conventional-commits")
                contentTemplate.set(file("template/changelog.tpl"))
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
