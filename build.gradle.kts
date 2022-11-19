import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

group = "com.felipebz.zpa"
version = "2.0.0-SNAPSHOT"

plugins {
    `maven-publish`
    kotlin("jvm") version "1.7.21"
    application
    id("com.github.johnrengelman.shadow") version "7.1.0"
    id("org.jreleaser") version "1.3.1"
    id("org.beryx.runtime") version "1.12.7"
    id("org.jreleaser.jdks") version "1.3.1"
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

tasks.named<ShadowJar>("shadowJar") {
    archiveBaseName.set("zpa-cli")
    mergeServiceFiles()
    minimize()
    manifest {
        attributes(mapOf("Main-Class" to "br.com.felipezorzo.zpa.cli.MainKt"))
    }
}

tasks {
    build {
        dependsOn(shadowJar)
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
    implementation("com.felipebz.zpa:zpa-core:3.2.0-SNAPSHOT")
    implementation("com.felipebz.zpa:zpa-checks:3.2.0-SNAPSHOT")
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

data class Jdk(val arch: String, val os: String, val extension: String, val checksum: String)

val baseJdkUrl = "https://github.com/adoptium/temurin17-binaries/releases/download"
val jdkBuild = "17.0.5+8"
val jdkVersion = jdkBuild.split(".").first()
val jdkBuildFilename = jdkBuild.replace("+", "_")
val jdksToBuild = listOf(
    Jdk("x64", "linux", "tar.gz", "482180725ceca472e12a8e6d1a4af23d608d78287a77d963335e2a0156a020af"),
    Jdk("aarch64", "linux", "tar.gz", "1c26c0e09f1641a666d6740d802beb81e12180abaea07b47c409d30c7f368109"),
    Jdk("x64", "mac", "tar.gz", "94fe50982b09a179e603a096e83fd8e59fd12c0ae4bcb37ae35f00ef30a75d64"),
    Jdk("aarch64", "mac", "tar.gz", "2dc3e425b52d1cd2915d93af5e468596b9e6a90112056abdcebac8b65bf57049"),
    Jdk("x64", "windows", "zip", "3cdcd859c8421a0681e260dc4fbf46b37fb1211f47beb2326a00398ecc52fde0"),
)

jdks {
    jdksToBuild.forEach {
        create("${it.os}_${it.arch}") {
            platform.set(it.os)
            url.set("$baseJdkUrl/jdk-$jdkBuild/OpenJDK${jdkVersion}U-jdk_${it.arch}_${it.os}_hotspot_$jdkBuildFilename.${it.extension}")
            checksum.set(it.checksum)
        }
    }
}

runtime {
    options.set(listOf("--strip-debug", "--compress", "2", "--no-header-files", "--no-man-pages"))
    modules.set(listOf("java.logging", "java.xml"))
    imageZip.set(file("$buildDir/zpa-cli-$version.zip"))

    jdksToBuild.forEach {
        targetPlatform("${it.os}_${it.arch}") {
            val additionalDir = if (it.os == "mac") "/Contents/Home" else ""
            setJdkHome("build/jdks/${it.os}_${it.arch}/jdk-$jdkBuild$additionalDir")
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
    release {
        github {
            repoOwner.set("felipebz")
            overwrite.set(true)
        }
    }
    distributions {
        create("zpa-cli") {
            artifact {
                path.set(file("build/distributions/{{distributionName}}-{{projectVersion}}.zip"))
            }
            jdksToBuild.forEach {
                val jreleaserOs = if (it.os == "mac") "osx" else it.os
                val jreleaseArch = when (it.arch) {
                    "aarch64" -> "aarch_64"
                    "x64" -> "x86_64"
                    else -> ""
                }
                artifact {
                    path.set(file("build/{{distributionName}}-{{projectVersion}}-${it.os}_${it.arch}.zip"))
                    platform.set("$jreleaserOs-$jreleaseArch")
                }
            }
        }
    }
}

