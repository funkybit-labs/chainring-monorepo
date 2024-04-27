plugins {
    kotlin("jvm") version "1.9.23"
}

group = "co.chainring"
version = "1.0"

repositories {
    mavenCentral()
}

val log4j2Version = "2.23.1"
val http4kVersion = "5.14.1.0"
val kotlinxSerializationVersion = "1.6.3"

dependencies {
    testImplementation("org.jetbrains.kotlin:kotlin-test")

    // Blockchain client
    implementation("org.web3j:core:4.10.3") // 4.11 introduces dependency (tech.pegasys:jc-kzg-4844) that is published to cloudsmith repository (https://github.com/web3j/web3j/issues/2013)

    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    implementation("org.http4k:http4k-format-kotlinx-serialization:$http4kVersion")
    implementation("org.http4k:http4k-client-websocket:$http4kVersion")
    implementation("io.github.oshai:kotlin-logging-jvm:6.0.3")
    implementation("org.apache.logging.log4j:log4j-api:$log4j2Version")
    implementation("org.apache.logging.log4j:log4j-core:$log4j2Version")
    implementation("org.apache.logging.log4j:log4j-layout-template-json:$log4j2Version")
    implementation("org.apache.logging.log4j:log4j-slf4j2-impl:$log4j2Version")
    implementation("io.arrow-kt:arrow-core:1.2.1")
    implementation(project(":backend"))
    implementation(project(":integrationtests"))
}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(17)
}