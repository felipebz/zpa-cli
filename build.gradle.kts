import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

group = "com.felipebz.zpa"
version = "2.0.0-SNAPSHOT"

plugins {
    `maven-publish`
    kotlin("jvm") version "1.8.10"
    application
    id("org.jreleaser") version "1.5.1"
    id("org.jreleaser.jdks") version "1.5.1"
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
    implementation("com.felipebz.zpa:zpa-core:3.3.0-SNAPSHOT")
    implementation("com.felipebz.zpa:zpa-checks:3.3.0-SNAPSHOT")
    implementation("com.google.guava:guava:31.1-jre")
    implementation("org.sonarsource.sonarqube:sonar-scanner-protocol:7.9")
    implementation("org.sonarsource.sonarqube:sonar-ws:7.9")
    implementation("com.squareup.okhttp3:okhttp:4.10.0")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-xml:2.14.0")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.14.0")
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
val jdkBuild = "17.0.5+8"
val jdkVersion = jdkBuild.split(".").first()
val jdkBuildFilename = jdkBuild.replace("+", "_")
val jdksToBuild = listOf(
    Jdk(
        arch = "x64",
        os = "linux",
        extension = "tar.gz",
        checksum = "482180725ceca472e12a8e6d1a4af23d608d78287a77d963335e2a0156a020af"
    ),

    Jdk(
        arch = "aarch64",
        os = "linux",
        extension = "tar.gz",
        checksum = "1c26c0e09f1641a666d6740d802beb81e12180abaea07b47c409d30c7f368109"
    ),

    Jdk(
        arch = "x64",
        os = "mac",
        extension = "tar.gz",
        checksum = "94fe50982b09a179e603a096e83fd8e59fd12c0ae4bcb37ae35f00ef30a75d64",
        platform = "osx"
    ),

    Jdk(
        arch = "aarch64",
        os = "mac",
        extension = "tar.gz",
        checksum = "2dc3e425b52d1cd2915d93af5e468596b9e6a90112056abdcebac8b65bf57049",
        platform = "osx"
    ),

    Jdk(
        arch = "x64",
        os = "windows",
        extension = "zip",
        checksum = "3cdcd859c8421a0681e260dc4fbf46b37fb1211f47beb2326a00398ecc52fde0"
    ),

    Jdk(
        arch = "x64",
        os = "alpine-linux",
        extension = "tar.gz",
        checksum = "cb154396ff3bfb6a9082e3640c564643d31ecae1792fab0956149ed5258ad84b",
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
                            "x64" -> "x86_64"
                            else -> ""
                        }
                        val additionalDir = if (it.os == "mac") "/Contents/Home" else ""
                        path.set(file("build/jdks/${it.platform}_${it.arch}/jdk-$jdkBuild$additionalDir"))
                        platform.set("$jreleaserOs-$jreleaseArch")
                        extraProperties.put("archiveFormat", if (jreleaserOs == "windows") "ZIP" else "TAR_GZ")
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
