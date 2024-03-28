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

val log4j2Version = "2.23.1"
val grpcKotlinStubVersion = "1.4.1"
val grpcProtobufVersion = "1.62.2"
val protobufKotlinVersion = "4.26.0"

dependencies {
    testImplementation("org.jetbrains.kotlin:kotlin-test")
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
}

tasks.test {
    useJUnitPlatform()
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
    jvmToolchain(11)
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

application {
    mainClass.set("co.chainring.sequencer.MainKt")
    applicationDefaultJvmArgs = listOf("--illegal-access=permit", "--add-exports=java.base/jdk.internal.ref=ALL-UNNAMED")
}

sourceSets {
    main {
        proto {
            // In addition to the default 'src/main/proto'
        }
        kotlin {
            srcDir("src/main/kotlin")
        }
    }
}
protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:$protobufKotlinVersion"
    }
    plugins {
        create("grpc") {
            artifact = "io.grpc:protoc-gen-grpc-java:$grpcProtobufVersion"
        }
        create("grpckt") {
            artifact = "io.grpc:protoc-gen-grpc-kotlin:$grpcKotlinStubVersion:jdk8@jar"
        }
    }
    generateProtoTasks {
        all().forEach {
            it.plugins {
                create("grpc")
                create("grpckt")
            }
            it.builtins {
                create("kotlin")
            }
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
        image = "851725450525.dkr.ecr.us-east-2.amazonaws.com/sequencer"
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
            "-Dlog4j2.formatMsgNoLookups=True",
            "--illegal-access=permit",
            "--add-exports=java.base/jdk.internal.ref=ALL-UNNAMED"
        )

        creationTime.set("USE_CURRENT_TIMESTAMP")
    }
}
