import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.9.23"
    kotlin("plugin.serialization") version "1.9.23"
    id("org.jmailen.kotlinter") version "4.2.0"
}

group = "xyz.funkybit"
version = "1.0"

repositories {
    mavenCentral()
}

val exposedVersion = "0.48.0"
val log4j2Version = "2.23.1"
val kotlinxSerializationVersion = "1.6.3"
val http4kVersion = "5.14.1.0"
val grpcKotlinStubVersion = "1.4.1"
val grpcProtobufVersion = "1.62.2"
val protobufKotlinVersion = "4.26.0"

dependencies {
    implementation(kotlin("test"))
    implementation("org.junit.jupiter:junit-jupiter-params:5.10.2")
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

    implementation("io.grpc:grpc-kotlin-stub:$grpcKotlinStubVersion")
    implementation("io.grpc:grpc-protobuf:$grpcProtobufVersion")
    implementation("com.google.protobuf:protobuf-kotlin:$protobufKotlinVersion")
    implementation("io.grpc:grpc-netty:$grpcProtobufVersion")
    implementation("net.openhft:chronicle-queue:5.22.28")

    implementation("io.arrow-kt:arrow-core:1.2.1")
    implementation("org.bitcoinj:bitcoinj-core:0.16.3")

    implementation(project(":backend"))
    implementation(project(":sequencer"))
    implementation(project(":sequencercommon"))
    implementation("org.bitcoinj:bitcoinj-core:0.16.3")

    testImplementation(project(mapOf("path" to ":")))
    testImplementation(testFixtures(project(":backend")))
}

tasks.test {
    useJUnitPlatform()
    jvmArgs = listOf(
        "--add-exports=java.base/jdk.internal.ref=ALL-UNNAMED",
        "--add-exports=java.base/sun.nio.ch=ALL-UNNAMED",
        "--add-exports=jdk.unsupported/sun.misc=ALL-UNNAMED",
        "--add-exports=jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED",
        "--add-opens=jdk.compiler/com.sun.tools.javac=ALL-UNNAMED",
        "--add-opens=java.base/java.lang=ALL-UNNAMED",
        "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED",
        "--add-opens=java.base/java.io=ALL-UNNAMED",
        "--add-opens=java.base/java.util=ALL-UNNAMED"
    )
}

tasks.withType<KotlinCompile> {
    kotlinOptions.jvmTarget = "17"
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
