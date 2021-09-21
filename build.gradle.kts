import org.jetbrains.kotlin.gradle.dsl.KotlinJvmCompile
import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

group = "org.zpa"
version = "2.0.0-SNAPSHOT"

plugins {
    `maven-publish`
    kotlin("jvm") version "1.5.31"
    application
    id("com.github.johnrengelman.shadow") version "7.0.0"
}

tasks.withType<KotlinJvmCompile> {
    kotlinOptions {
        jvmTarget = "11"
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
        setUrl("https://pkgs.dev.azure.com/felipebz/z-plsql-analyzer/_packaging/public_feed/maven/v1")
    }
    jcenter()
}

dependencies {
    implementation("com.github.ajalt.clikt:clikt:3.1.0")
    implementation("org.sonar.plsqlopen:zpa-core:3.0.0-SNAPSHOT")
    implementation("org.sonar.plsqlopen:plsql-checks:3.0.0-SNAPSHOT")
    implementation("com.google.guava:guava:28.2-jre")
    implementation("org.sonarsource.sonarqube:sonar-scanner-protocol:7.9")
    implementation("com.squareup.okhttp3:okhttp:4.9.0")
    testImplementation(kotlin("test"))
}

application {
    mainClass.set("br.com.felipezorzo.zpa.cli.MainKt")
}

publishing {
    repositories {
        maven {
            name = "AzureArtifacts"
            url = uri("https://pkgs.dev.azure.com/felipebz/z-plsql-analyzer/_packaging/public_feed/maven/v1")
            credentials {
                username = project.findProperty("gpr.user") as String? ?: System.getenv("DEPLOY_USERNAME")
                password = project.findProperty("gpr.key") as String? ?: System.getenv("DEPLOY_TOKEN")
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
