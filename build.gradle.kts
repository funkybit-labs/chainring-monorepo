import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

group = "co.chainring"
version = "1.0"

repositories {
    mavenCentral()
}

plugins {
    kotlin("jvm") version "1.9.0"
    application
}

application {
    mainClass.set("co.chainring.LocalMainKt")
}

tasks.withType<KotlinCompile> {
    kotlinOptions.jvmTarget = "17"
}

val log4j2Version = "2.20.0"

dependencies {
    implementation("io.github.oshai:kotlin-logging-jvm:5.1.0")
    implementation("org.apache.logging.log4j:log4j-api:$log4j2Version")
    implementation("org.apache.logging.log4j:log4j-core:$log4j2Version")
    implementation("org.apache.logging.log4j:log4j-slf4j-impl:$log4j2Version")
    implementation("com.newrelic.logging:log4j2:3.0.0")
    implementation("org.awaitility:awaitility-kotlin:4.2.0")
    implementation("com.squareup.okhttp3:okhttp:4.11.0")

    implementation(project(":backend"))
}

allprojects {
    apply(plugin = "base")
}
