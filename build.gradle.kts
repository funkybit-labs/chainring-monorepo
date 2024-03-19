import groovy.json.JsonOutput
import groovy.json.JsonSlurper
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

val compileContractsAndGenerateWrappers by tasks.register("compileContractsAndGenerateWrappers") {
    data class Contract(
        val nameInBuildDir: String,
        val generateJavaWrapper: Boolean,
        val generateTypescriptAbi: Boolean,
        val generateJavaTestWrapper: Boolean = false,
    ) {
        val typeScriptAbiName = "${File(nameInBuildDir).nameWithoutExtension}Abi"
    }

    val contracts = listOf(
        Contract(
            nameInBuildDir = "Exchange.sol",
            generateJavaWrapper = true,
            generateTypescriptAbi = true
        ),
        Contract(
            nameInBuildDir = "ERC1967Proxy.sol",
            generateJavaWrapper = true,
            generateTypescriptAbi = false
        ),
        Contract(
            nameInBuildDir = "UUPSUpgradeable.sol",
            generateJavaWrapper = true,
            generateTypescriptAbi = false
        ),
        Contract(
            nameInBuildDir = "ERC20.sol",
            generateJavaWrapper = true,
            generateTypescriptAbi = true
        ),
        Contract(
            nameInBuildDir = "ERC20Mock.sol",
            generateJavaWrapper = false,
            generateTypescriptAbi = false,
            generateJavaTestWrapper = true
        ),
    )

    println("Building Solidity contracts")
    exec {
        isIgnoreExitValue = true
        commandLine(
            "bash", "-c", "forge build --root ${projectDir}/contracts --out ${buildDir}/contracts"
        )
    }.also {
        if (it.exitValue == 127) {
            throw Exception("Foundry is missing, please follow the instructions: https://book.getfoundry.sh/getting-started/installation")
        }
    }

    val javaWrappersOutputDir = File("${projectDir}/backend/src/main/java")
    val javaTestWrappersOutputDir = File("${projectDir}/integrationtests/src/test/java")
    val typeScriptAbiOutputDir = File("${projectDir}/web-ui/src/contracts")

    contracts.forEach { contract ->
        val dirFile = File("${buildDir}/contracts/${contract.nameInBuildDir}")
        val jsonFile = File("${dirFile.absolutePath}/${dirFile.nameWithoutExtension}.json")

        if (!dirFile.exists()) {
            throw Exception("File not found ${dirFile.absolutePath}")
        }

        if (!jsonFile.exists()) {
            throw Exception("File not found ${jsonFile.absolutePath}")
        }

        val json = JsonSlurper().parseText(jsonFile.readText()) as Map<*, *>
        val abiJson = JsonOutput.prettyPrint(JsonOutput.toJson(json["abi"] as ArrayList<*>))

        val abiFile = File(jsonFile.absolutePath.replace(".json", ".abi")).also {
            it.writeText(abiJson)
        }

        val binFile = File(jsonFile.absolutePath.replace(".json", ".bin")).also {
            val byteCode = JsonOutput.toJson((json["bytecode"] as Map<*, *>)["object"])!!
            it.writeText(byteCode.replace("0x", "").replace("\"", ""))
        }

        if (contract.generateJavaWrapper) {
            exec {
                isIgnoreExitValue = true
                commandLine(
                    "bash",
                    "-c",
                    "web3j generate solidity -b ${binFile.absolutePath} -a ${abiFile.absolutePath} -o ${javaWrappersOutputDir.absolutePath} -p co.chainring.contracts.generated"
                )
            }.also {
                if (it.exitValue == 127) {
                    throw Exception("Web3j is missing, please run 'brew tap web3j/web3j && brew install web3j' to install")
                }
            }
        }

        if (contract.generateJavaTestWrapper) {
            exec {
                isIgnoreExitValue = true
                commandLine(
                    "bash",
                    "-c",
                    "web3j generate solidity -b ${binFile.absolutePath} -a ${abiFile.absolutePath} -o ${javaTestWrappersOutputDir.absolutePath} -p co.chainring.contracts.generated"
                )
            }.also {
                if (it.exitValue == 127) {
                    throw Exception("Web3j is missing, please run 'brew tap web3j/web3j && brew install web3j' to install")
                }
            }
        }

        if (contract.generateTypescriptAbi) {
            File("${typeScriptAbiOutputDir.absolutePath}/${contract.typeScriptAbiName}.ts").also {
                print("Generating TypeScript ABI for ${contract.nameInBuildDir} ...")
                it.writeText("export default $abiJson as const")
                println("File written to ${it.absolutePath}")
            }
        }
    }

    // generate web-ui/contracts/index.ts
    contracts
        .filter { it.generateTypescriptAbi }
        .map { it.typeScriptAbiName }
        .also {
            if (it.isNotEmpty()) {
                File("${typeScriptAbiOutputDir.absolutePath}/index.ts")
                    .writeText(it.joinToString("\n") { abiName -> "import $abiName from './${abiName}'" } + "\n" + "export { ${it.joinToString(", ")} }\n")
            }
        }

    println("Web3j contract wrappers and Typescript ABIs generated successfully for all contracts.")
}
