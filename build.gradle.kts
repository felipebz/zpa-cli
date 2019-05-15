import org.jetbrains.kotlin.gradle.dsl.KotlinJvmCompile

plugins {
    kotlin("jvm") version "1.3.31"
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
    implementation("com.github.ajalt:clikt:1.7.0")
    implementation("org.sonar.plsqlopen:zpa-core:2.4.0-SNAPSHOT")
    implementation("org.sonar.plsqlopen:plsql-checks:2.4.0-SNAPSHOT") {
        exclude("org.sonarsource.sonarqube", "sonar-plugin-api")
    }
    implementation(kotlin("stdlib-jdk8"))
    testImplementation(kotlin("test"))
}

application {
    mainClassName = "br.com.felipezorzo.zpa.cli.MainKt"
}
