plugins {
    kotlin("jvm") version "1.9.23"
    id("org.jmailen.kotlinter") version "4.2.0"
    id("com.google.cloud.tools.jib") version "3.4.1"
    application
}

group = "co.chainring"
version = "1.0"

repositories {
    mavenCentral()
    maven("https://jitpack.io")
}

val log4j2Version = "2.23.1"
val http4kVersion = "5.14.1.0"
val kotlinxSerializationVersion = "1.6.3"
val exposedVersion = "0.48.0"

dependencies {
    testImplementation("org.jetbrains.kotlin:kotlin-test")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")

    // Database
    implementation("org.jetbrains.exposed:exposed-core:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-dao:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-jdbc:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-kotlin-datetime:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-json:$exposedVersion")
    implementation("org.postgresql:postgresql:42.3.9")
    implementation("org.apache.commons:commons-compress:1.26.1")
    implementation("org.apache.commons:commons-dbcp2:2.12.0")

    // Blockchain client
    implementation("org.web3j:core:4.10.3") // 4.11 introduces dependency (tech.pegasys:jc-kzg-4844) that is published to cloudsmith repository (https://github.com/web3j/web3j/issues/2013)

    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    implementation("org.apache.commons:commons-lang3:3.14.0")

    implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.5.0")

    implementation("org.http4k:http4k-format-kotlinx-serialization:$http4kVersion")
    implementation("org.http4k:http4k-client-websocket:$http4kVersion")
    implementation("io.github.oshai:kotlin-logging-jvm:6.0.3")
    implementation("org.apache.logging.log4j:log4j-api:$log4j2Version")
    implementation("org.apache.logging.log4j:log4j-core:$log4j2Version")
    implementation("org.apache.logging.log4j:log4j-layout-template-json:$log4j2Version")
    implementation("org.apache.logging.log4j:log4j-slf4j2-impl:$log4j2Version")
    implementation("io.arrow-kt:arrow-core:1.2.1")
    implementation("com.github.ehsannarmani:EasyBot:1.0.0")
    implementation(project(":backend"))
}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(17)
}

application {
    mainClass = "co.chainring.telegrambot.MainKt"
}

val buildNumber = System.getenv("BUILD_NUMBER") ?: "1"

jib {
    from {
        platforms {
            platform {
                architecture = "amd64"
                os = "linux"
            }
        }
    }

    to {
        image = "851725450525.dkr.ecr.us-east-2.amazonaws.com/telegrambot"
        credHelper.helper = "ecr-login"
        tags = setOf("${version}-${buildNumber}")
    }

    container {
        jvmFlags = listOf(
            "-XX:+PrintCommandLineFlags",
            "-XshowSettings:vm",
            "-XX:MinRAMPercentage=60.0",
            "-XX:MaxRAMPercentage=90.0",
            "-Dlog4j2.configurationFile=log4j2-container.xml",
            "-Dlog4j2.formatMsgNoLookups=True"
        )

        creationTime.set("USE_CURRENT_TIMESTAMP")
    }
}

tasks.register("printImageTag") {
    doLast {
        println("IMAGE_TAG=${version}-${buildNumber}")
    }
}
