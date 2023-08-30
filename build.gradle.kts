import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile
import org.jreleaser.model.api.common.ArchiveOptions

group = "com.felipebz.zpa"
version = "2.0.0-SNAPSHOT"

plugins {
    `maven-publish`
    kotlin("jvm") version "1.9.0"
    application
    id("org.jreleaser") version "1.7.0"
    id("org.jreleaser.jdks") version "1.7.0"
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

tasks.withType<KotlinJvmCompile> {
    kotlinOptions {
        jvmTarget = "17"
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
    implementation("com.github.ajalt.clikt:clikt:3.5.0")
    implementation("com.felipebz.zpa:sonar-zpa-plugin:3.3.0-SNAPSHOT")
    implementation("org.sonarsource.sonarqube:sonar-scanner-protocol:7.9")
    implementation("org.sonarsource.sonarqube:sonar-ws:7.9")
    implementation("com.squareup.okhttp3:okhttp:4.10.0")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-xml:2.14.0")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.14.0")
    implementation("org.pf4j:pf4j:3.9.0")
    implementation("org.slf4j:slf4j-jdk14:2.0.7")
    testImplementation(kotlin("test"))
}

application {
    mainClass.set("br.com.felipezorzo.zpa.cli.MainKt")
}

val copyDependencies = tasks.create<Sync>("copyDependencies") {
    from(configurations.runtimeClasspath)
    into("${buildDir}/dependencies/flat")
}
tasks["assemble"].dependsOn(copyDependencies)

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

val baseJdkUrl = "https://github.com/adoptium/temurin17-binaries/releases/download"
val jdkBuild = "17.0.8.1+1"
val jdkVersion = jdkBuild.split(".").first()
val jdkBuildFilename = jdkBuild.replace("+", "_")
val jdksToBuild = listOf(
    Jdk(
        arch = "x64",
        os = "linux",
        extension = "tar.gz",
        checksum = "c25dfbc334068a48c19c44ce39ad4b8427e309ae1cfa83f23c102e78b8a6dcc0"
    ),

    Jdk(
        arch = "aarch64",
        os = "linux",
        extension = "tar.gz",
        checksum = "eefd3cf3b3dd47ff269fa5b5c10b5e096b163f4e9c1810023abdbc00dc6cc304"
    ),

    Jdk(
        arch = "x64",
        os = "mac",
        extension = "tar.gz",
        checksum = "18be56732c1692ef131625d814dcb02ee091a43fdd6f214a33d87cc14842fc3f",
        platform = "osx"
    ),

    Jdk(
        arch = "aarch64",
        os = "mac",
        extension = "tar.gz",
        checksum = "2e95eed48650f00650e963c8213b6c6ecda54458edf8d254ebc99d6a6966ffad",
        platform = "osx"
    ),

    Jdk(
        arch = "x64",
        os = "windows",
        extension = "zip",
        checksum = "651a795155dc918c06cc9fd4b37253b9cbbca5ec8e76d4a8fa7cdaeb1f52761c"
    ),

    Jdk(
        arch = "x64",
        os = "alpine-linux",
        extension = "tar.gz",
        checksum = "85d298268db61c4a538df905ec510eba7dc09300e7f7132bb2e25e5e8aef1933",
        platform = "linux_musl"
    ),
)

jdks {
    jdksToBuild.forEach {
        create("${it.platform}_${it.arch}") {
            platform.set(it.platform)
            url.set("$baseJdkUrl/jdk-$jdkBuild/OpenJDK${jdkVersion}U-jdk_${it.arch}_${it.os}_hotspot_$jdkBuildFilename.${it.extension}")
            checksum.set(it.checksum)
        }
    }
}

jreleaser {
    project {
        description.set("The command-line interface of the Z PL/SQL Analyzer.")
        authors.set(listOf("felipebz"))
        license.set("LGPL-3.0")
        links {
            homepage.set("https://felipezorzo.com.br/zpa/")
        }
        inceptionYear.set("2019")
    }
    assemble {
        jlink {
            create("zpa-cli") {
                active.set(org.jreleaser.model.Active.ALWAYS)
                exported.set(true)
                stereotype.set(org.jreleaser.model.Stereotype.CLI)
                imageName.set("{{distributionName}}-{{projectVersion}}")
                moduleNames.set(listOf("java.logging", "java.xml", "java.sql"))
                jdeps {
                    multiRelease.set("base")
                    ignoreMissingDeps.set(true)
                }
                jdksToBuild.forEach {
                    targetJdk {
                        val jreleaserOs = it.platform
                        val jreleaseArch = when (it.arch) {
                            "aarch64" -> "aarch_64"
                            "x64" -> "x86_64"
                            else -> ""
                        }
                        val additionalDir = if (it.os == "mac") "/Contents/Home" else ""
                        path.set(file("build/jdks/${it.platform}_${it.arch}/jdk-$jdkBuild$additionalDir"))
                        platform.set("$jreleaserOs-$jreleaseArch")
                        extraProperties.put("archiveFormat", if (jreleaserOs == "windows") "ZIP" else "TAR_GZ")
                        options {
                            longFileMode.set(ArchiveOptions.TarMode.POSIX)
                        }
                    }
                }
                jdk {
                    val jdkPath = javaToolchains.launcherFor {
                        languageVersion.set(JavaLanguageVersion.of(17))
                    }.get().metadata.installationPath

                    path.set(file(jdkPath))
                }
                mainJar {
                    path.set(file("build/libs/zpa-cli-{{projectVersion}}.jar"))
                }
                jars {
                    pattern.set("build/dependencies/flat/*.jar")
                }
            }
        }
    }
    release {
        github {
            overwrite.set(true)
            changelog {
                formatted.set(org.jreleaser.model.Active.ALWAYS)
                preset.set("conventional-commits")
                format.set("- {{commitShortHash}} {{commitTitle}}")
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
