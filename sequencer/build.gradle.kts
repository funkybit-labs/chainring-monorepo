import org.jmailen.gradle.kotlinter.tasks.FormatTask
import org.jmailen.gradle.kotlinter.tasks.LintTask

plugins {
    kotlin("jvm")
    id("com.google.protobuf") version "0.9.4"
    id("org.jmailen.kotlinter") version "4.2.0"
    id("com.google.cloud.tools.jib") version "3.4.1"
    application
}

group = "co.censo"
version = "1.0"

repositories {
    mavenCentral()
}

val exposedVersion = "0.48.0"
val log4j2Version = "2.23.1"
val grpcKotlinStubVersion = "1.4.1"
val grpcProtobufVersion = "1.62.2"
val protobufKotlinVersion = "4.26.0"

val chronicleJvmArgs = listOf(
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

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter-params:5.10.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.0")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.5.0")

    // Database
    implementation("org.jetbrains.exposed:exposed-core:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-dao:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-jdbc:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-kotlin-datetime:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-json:$exposedVersion")
    implementation("org.postgresql:postgresql:42.3.9")
    implementation("org.apache.commons:commons-compress:1.26.1")
    implementation("org.apache.commons:commons-dbcp2:2.12.0")

    implementation("io.github.oshai:kotlin-logging-jvm:6.0.3")
    implementation("org.apache.logging.log4j:log4j-api:$log4j2Version")
    implementation("org.apache.logging.log4j:log4j-core:$log4j2Version")
    implementation("org.apache.logging.log4j:log4j-layout-template-json:$log4j2Version")
    implementation("org.apache.logging.log4j:log4j-slf4j2-impl:$log4j2Version")
    implementation("io.grpc:grpc-kotlin-stub:$grpcKotlinStubVersion")
    implementation("io.grpc:grpc-protobuf:$grpcProtobufVersion")
    implementation("com.google.protobuf:protobuf-kotlin:$protobufKotlinVersion")
    implementation("io.grpc:grpc-netty:$grpcProtobufVersion")
    implementation("net.openhft:chronicle-queue:5.22.28")
    implementation(project(":sequencercommon"))

    implementation(project(":backend"))
}

tasks.test {
    useJUnitPlatform()
    jvmArgs = chronicleJvmArgs
}
tasks {
    // ignore protobuf-generated files (identified by ending with `Kt.kt`)
    "formatKotlinMain"(FormatTask::class) {
        exclude("**/*Kt.kt")
    }
    "lintKotlinMain"(LintTask::class) {
        exclude("**/*Kt.kt")
    }
}
kotlin {
    jvmToolchain(17)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

application {
    mainClass.set("xyz.funkybit.sequencer.MainKt")
    applicationDefaultJvmArgs = chronicleJvmArgs
}

sourceSets {
    main {
        kotlin {
            srcDir("src/main/kotlin")
        }
    }
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
        image = "851725450525.dkr.ecr.us-east-2.amazonaws.com/sequencer:${version}-${buildNumber}"
        credHelper.helper = "ecr-login"
    }

    container {
        jvmFlags = listOf(
            "-XX:+PrintCommandLineFlags",
            "-XshowSettings:vm",
            "-XX:MinRAMPercentage=60.0",
            "-XX:MaxRAMPercentage=90.0",
            "-Dlog4j2.configurationFile=log4j2-container.xml",
            "-Dlog4j2.formatMsgNoLookups=True",
        ) + chronicleJvmArgs

        creationTime.set("USE_CURRENT_TIMESTAMP")

        volumes = listOf(
            "/data/queues"
        )
    }
}
