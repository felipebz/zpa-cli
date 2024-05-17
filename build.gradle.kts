import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile
import org.jreleaser.model.api.common.ArchiveOptions

group = "com.felipebz.zpa"
version = "2.0.0-SNAPSHOT"

plugins {
    `maven-publish`
    kotlin("jvm") version "1.9.24"
    application
    id("org.jreleaser") version "1.8.0"
    id("org.jreleaser.jdks") version "1.8.0"
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
    into("${layout.buildDirectory}/dependencies/flat")
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
val jdkBuild = "21+35"
val jdkVersion = jdkBuild.split('.', '+').first()
val jdkBuildFilename = jdkBuild.replace('+', '_')
val jdksToBuild = listOf(
    Jdk(
        arch = "x64",
        os = "linux",
        extension = "tar.gz",
        checksum = "82f64c53acaa045370d6762ebd7441b74e6fda14b464d54d1ff8ca941ec069e6"
    ),

    Jdk(
        arch = "aarch64",
        os = "linux",
        extension = "tar.gz",
        checksum = "33e440c237438aa2e3866d84ead8d4e00dc0992d98d9fd0ee2fe48192f2dbc4b"
    ),

    Jdk(
        arch = "x64",
        os = "mac",
        extension = "tar.gz",
        checksum = "25f3d8c875255362a3e31a0783f9f0422de01f8e4b515c45bd68e43ef3812a9d",
        platform = "osx"
    ),

    Jdk(
        arch = "aarch64",
        os = "mac",
        extension = "tar.gz",
        checksum = "107d1b16cda1da20d2f7aa45b1bfb8574bbfca2e15bb0ff720ce2678473b00d5",
        platform = "osx"
    ),

    Jdk(
        arch = "x64",
        os = "windows",
        extension = "zip",
        checksum = "653dc46f31dd0e8c5c13dfefe72754615dc0fdc123a03390e71e2cff2f1f17e1"
    ),

    Jdk(
        arch = "x64",
        os = "alpine-linux",
        extension = "tar.gz",
        checksum = "4fd74f93f0b1a94d8471e0ed801fe9d938f7471f6efe8791880c85e7716c943f",
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
