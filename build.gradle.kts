import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jreleaser.model.api.common.ArchiveOptions

group = "com.felipebz.zpa"
version = "2.0.0-SNAPSHOT"

plugins {
    `maven-publish`
    kotlin("jvm") version "2.0.0"
    application
    id("org.jreleaser") version "1.12.0"
    id("org.jreleaser.jdks") version "1.12.0"
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
    implementation("org.sonarsource.api.plugin:sonar-plugin-api:9.14.0.375")
    implementation("com.github.ajalt.clikt:clikt:3.5.0")
    implementation("com.felipebz.zpa:sonar-zpa-plugin:3.3.0-SNAPSHOT")
    implementation("org.pf4j:pf4j:3.11.0")
    implementation("org.slf4j:slf4j-jdk14:2.0.7")
    testImplementation(kotlin("test"))
}

application {
    mainClass.set("br.com.felipezorzo.zpa.cli.MainKt")
}

val copyDependencies = tasks.create<Sync>("copyDependencies") {
    from(configurations.runtimeClasspath)
    into(layout.buildDirectory.dir("dependencies/flat"))
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

val baseJdkUrl = "https://github.com/adoptium/temurin21-binaries/releases/download"
val jdkBuild = "21.0.3+9"
val jdkVersion = jdkBuild.split('.', '+').first()
val jdkBuildFilename = jdkBuild.replace('+', '_')
val jdksToBuild = listOf(
    Jdk(
        arch = "x64",
        os = "linux",
        extension = "tar.gz",
        checksum = "fffa52c22d797b715a962e6c8d11ec7d79b90dd819b5bc51d62137ea4b22a340"
    ),

    Jdk(
        arch = "aarch64",
        os = "linux",
        extension = "tar.gz",
        checksum = "7d3ab0e8eba95bd682cfda8041c6cb6fa21e09d0d9131316fd7c96c78969de31"
    ),

    Jdk(
        arch = "x64",
        os = "mac",
        extension = "tar.gz",
        checksum = "f777103aab94330d14a29bd99f3a26d60abbab8e2c375cec9602746096721a7c",
        platform = "osx"
    ),

    Jdk(
        arch = "aarch64",
        os = "mac",
        extension = "tar.gz",
        checksum = "b6be6a9568be83695ec6b7cb977f4902f7be47d74494c290bc2a5c3c951e254f",
        platform = "osx"
    ),

    Jdk(
        arch = "x64",
        os = "windows",
        extension = "zip",
        checksum = "c43a66cff7a403d56c5c5e1ff10d3d5f95961abf80f97f0e35380594909f0e4d"
    ),

    Jdk(
        arch = "x64",
        os = "alpine-linux",
        extension = "tar.gz",
        checksum = "8e861638bf6b08c6d5837de6dc929930550928ec5fcc81b9fa7e8296afd0f9c0",
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
                        languageVersion.set(JavaLanguageVersion.of(21))
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
