import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.9.23"
    kotlin("plugin.serialization") version "1.9.23"
    id("org.jmailen.kotlinter") version "4.2.0"
    id("com.google.cloud.tools.jib") version "3.4.1"
    application
}

group = "co.chainring"
version = "1.0"

repositories {
    mavenCentral()
}

val exposedVersion = "0.48.0"
val http4kVersion = "5.14.0.0"
val log4j2Version = "2.23.1"

dependencies {
    testImplementation(kotlin("test"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.5.0")

    // Blockchain client
    implementation("org.web3j:core:4.10.0") // 4.11 introduces dependency (tech.pegasys:jc-kzg-4844) that is published to cloudsmith repository (tech.pegasys:jc-kzg-4844, https://github.com/web3j/web3j/issues/2013)

    // Database
    implementation("org.jetbrains.exposed:exposed-core:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-dao:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-jdbc:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-kotlin-datetime:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-json:$exposedVersion")
    implementation("org.postgresql:postgresql:42.7.2")
    implementation("org.apache.commons:commons-dbcp2:2.12.0")

    // Logging
    implementation("io.github.oshai:kotlin-logging-jvm:6.0.3")
    implementation("org.apache.logging.log4j:log4j-api:$log4j2Version")
    implementation("org.apache.logging.log4j:log4j-core:$log4j2Version")
    implementation("org.apache.logging.log4j:log4j-slf4j2-impl:$log4j2Version")

    // for REST services
    implementation("org.http4k:http4k-core:$http4kVersion")
    implementation("org.http4k:http4k-realtime-core:$http4kVersion")
    implementation("org.http4k:http4k-contract:$http4kVersion")
    implementation("org.http4k:http4k-format-kotlinx-serialization:$http4kVersion")
    implementation("org.http4k:http4k-server-netty:$http4kVersion")
    implementation("org.http4k:http4k-client-okhttp:$http4kVersion")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    implementation("de.fxlae:typeid-java:0.2.0")
    implementation("org.awaitility:awaitility-kotlin:4.2.0")

    implementation("org.bouncycastle:bcpkix-jdk18on:1.77")
    implementation("org.bouncycastle:bcprov-jdk18on:1.77")
    implementation("io.github.novacrypto:Base58:2022.01.17")

    testImplementation("org.junit.jupiter:junit-jupiter-params:5.10.2")
}

tasks.test {
    useJUnitPlatform()
}

tasks.withType<KotlinCompile> {
    kotlinOptions.jvmTarget = "17"
}

application {
    mainClass.set("co.chainring.MainKt")
}

val compileSolidity by tasks.registering(Exec::class) {
    // requires solidity compiler (brew install solidity)
    commandLine(
        "bash", "-c", """
            solc --bin --abi --optimize --overwrite -o ${buildDir}/contracts ${projectDir}/src/main/solidity/*.sol
        """.trimIndent()
    )
    doLast {
        println("Solidity contracts compiled successfully.")
    }
}

val web3jGenerate by tasks.registering(Exec::class) {
    // requires web3j (brew tap web3j/web3j && brew install web3j)
    dependsOn(compileSolidity)
    doFirst {
        val contractsDir = File("${buildDir}/contracts")
        contractsDir.listFiles { _, name -> name.endsWith(".abi") }
            ?.forEach { abiFile ->
                val contractName = abiFile.nameWithoutExtension
                val binFile = File(contractsDir, "$contractName.bin")

                // Create java wrappers for each contract
                commandLine(
                    "bash", "-c", """
                        web3j generate solidity -b ${binFile.absolutePath} -a ${abiFile.absolutePath} -o ${projectDir}/src/main/java -p co.chainring.contracts.generated
                    """.trimIndent()
                )
            }
    }
    doLast {
        println("Web3j contract wrappers generated successfully for all contracts.")
    }
}

jib {
    to {
        image = "851725450525.dkr.ecr.us-east-2.amazonaws.com/backend"
        credHelper.helper = "ecr-login"
        tags = setOf("${version}-${System.getenv("BUILD_NUMBER") ?: "1"}")
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

        environment = mapOf(
            "VERSION" to version.toString(),
            "BUILD_NUMBER" to (System.getenv("BUILD_NUMBER") ?: "1")
        )

        creationTime.set("USE_CURRENT_TIMESTAMP")
    }
}
