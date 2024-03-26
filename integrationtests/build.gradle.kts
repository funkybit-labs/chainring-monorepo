import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.9.23"
    kotlin("plugin.serialization") version "1.9.23"
    id("org.jmailen.kotlinter") version "4.2.0"
}

group = "co.chainring"
version = "1.0"

repositories {
    mavenCentral()
}

val exposedVersion = "0.48.0"
val log4j2Version = "2.23.1"
val kotlinxSerializationVersion = "1.6.3"
val http4kVersion = "5.14.1.0"

dependencies {
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter-params")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:$kotlinxSerializationVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-cbor:$kotlinxSerializationVersion")

    // Blockchain client
    implementation("org.web3j:core:4.10.3") // 4.11 introduces dependency (tech.pegasys:jc-kzg-4844) that is published to cloudsmith repository (https://github.com/web3j/web3j/issues/2013)

    // Database
    implementation("org.jetbrains.exposed:exposed-core:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-dao:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-jdbc:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-kotlin-datetime:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-json:$exposedVersion")
    implementation("org.postgresql:postgresql:42.3.9")
    implementation("org.apache.commons:commons-compress:1.26.1")
    implementation("org.apache.commons:commons-dbcp2:2.12.0")

    // Logging
    implementation("io.github.oshai:kotlin-logging-jvm:6.0.3")
    implementation("org.apache.logging.log4j:log4j-api:$log4j2Version")
    implementation("org.apache.logging.log4j:log4j-core:$log4j2Version")
    implementation("org.apache.logging.log4j:log4j-slf4j-impl:$log4j2Version")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    implementation("de.fxlae:typeid-java:0.2.0")
    implementation("org.awaitility:awaitility-kotlin:4.2.0")

    implementation("org.http4k:http4k-format-kotlinx-serialization:$http4kVersion")
    implementation("org.http4k:http4k-client-websocket:$http4kVersion")

    implementation(project(":backend"))
    testImplementation(project(mapOf("path" to ":")))
}

tasks.test {
    useJUnitPlatform()
}

tasks.withType<KotlinCompile> {
    kotlinOptions.jvmTarget = "17"
}
