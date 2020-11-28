import org.jetbrains.kotlin.gradle.dsl.KotlinJvmCompile

group = "org.zpa"
version = "1.0.0-SNAPSHOT"

plugins {
    `maven-publish`
    kotlin("jvm") version "1.4.20"
    application
}

tasks.withType<KotlinJvmCompile> {
    kotlinOptions {
        jvmTarget = "1.8"
    }
}

repositories {
    mavenLocal()
    mavenCentral()
    jcenter()
    maven { setUrl("https://oss.jfrog.org/artifactory/oss-snapshot-local") }
}

dependencies {
    implementation("com.github.ajalt:clikt:2.3.0")
    implementation("org.sonar.plsqlopen:zpa-core:3.0.0-SNAPSHOT")
    implementation("org.sonar.plsqlopen:plsql-checks:3.0.0-SNAPSHOT")
    implementation("com.google.guava:guava:28.2-jre")
    testImplementation(kotlin("test"))
}

application {
    mainClassName = "br.com.felipezorzo.zpa.cli.MainKt"
}

publishing {
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/felipebz/zpa-cli")
            credentials {
                username = project.findProperty("gpr.user") as String? ?: System.getenv("USERNAME")
                password = project.findProperty("gpr.key") as String? ?: System.getenv("TOKEN")
            }
        }
    }
    publications {
        create<MavenPublication>("gpr") {
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
