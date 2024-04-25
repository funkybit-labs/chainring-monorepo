import org.jmailen.gradle.kotlinter.tasks.FormatTask
import org.jmailen.gradle.kotlinter.tasks.LintTask

plugins {
    kotlin("jvm")
    id("com.google.protobuf") version "0.9.4"
    id("org.jmailen.kotlinter") version "4.2.0"
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
    implementation("io.grpc:grpc-kotlin-stub:$grpcKotlinStubVersion")
    implementation("io.grpc:grpc-protobuf:$grpcProtobufVersion")
    implementation("com.google.protobuf:protobuf-kotlin:$protobufKotlinVersion")
    implementation("io.grpc:grpc-netty:$grpcProtobufVersion")
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
    jvmToolchain(17)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}


sourceSets {
    main {
        proto {
            // In addition to the default 'src/main/proto'
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

