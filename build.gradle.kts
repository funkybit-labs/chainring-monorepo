import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.util.ArrayList

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

val compileContracts by tasks.registering(Exec::class) {
    // requires solidity compiler (brew install solidity)
    commandLine(
        "bash", "-c", """
            forge build --root "${projectDir}/contracts" --out "${buildDir}/contracts" 
        """.trimIndent()
    )
    doLast {
        println("Solidity contracts compiled successfully.")
    }
}

val web3jGenerate by tasks.registering(Exec::class) {
    // requires web3j (brew tap web3j/web3j && brew install web3j)
    dependsOn(compileContracts)
    doFirst {
        val contractFiles = File("${projectDir}/contracts/src").listFiles {  _, name -> name.endsWith(".sol") }.map { it.name } +
                listOf("ERC1967Proxy.sol", "UUPSUpgradeable.sol")
        val contractsBuildDir = File("${buildDir}/contracts")
        var command = ""
        contractsBuildDir.listFiles { _, name -> contractFiles.contains(name) }
            ?.map { dirFile -> File(dirFile.absolutePath, "${dirFile.nameWithoutExtension}.json") }
            ?.forEach { jsonFile ->
                val abiFile = File(jsonFile.absolutePath.replace(".json", ".abi"))
                val binFile = File(jsonFile.absolutePath.replace(".json", ".bin"))
                val json = groovy.json.JsonSlurper().parseText(jsonFile.readText()) as Map<String, String>
                abiFile.writeText("${groovy.json.JsonOutput.toJson(json["abi"] as ArrayList<*>)}")
                val byteCode: String = groovy.json.JsonOutput.toJson((json["bytecode"] as Map<String, String>)["object"])!!
                binFile.writeText(byteCode.replace("0x", "").replace("\"", ""))
                // Create java wrappers for each contract
                command +=  "web3j generate solidity -b ${binFile.absolutePath} -a ${abiFile.absolutePath} -o ${projectDir}/backend/src/main/java -p co.chainring.contracts.generated; "
            }
        commandLine(
            "bash", "-c", command.trimIndent()
        )
    }
    doLast {
        println("Web3j contract wrappers generated successfully for all contracts.")
    }
}