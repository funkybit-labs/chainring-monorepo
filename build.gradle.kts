import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

group = "co.chainring"
version = "1.0"

repositories {
    mavenCentral()
}

plugins {
    kotlin("jvm") version "1.9.23"
    application
}

application {
    mainClass.set("co.chainring.LocalMainKt")
}

tasks.withType<KotlinCompile> {
    kotlinOptions.jvmTarget = "17"
}

val log4j2Version = "2.23.1"

dependencies {
    implementation("io.github.oshai:kotlin-logging-jvm:6.0.3")
    implementation("org.apache.logging.log4j:log4j-api:$log4j2Version")
    implementation("org.apache.logging.log4j:log4j-core:$log4j2Version")
    implementation("org.apache.logging.log4j:log4j-slf4j-impl:$log4j2Version")

    implementation("org.awaitility:awaitility-kotlin:4.2.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    implementation(project(":backend"))
}

allprojects {
    apply(plugin = "base")
}
