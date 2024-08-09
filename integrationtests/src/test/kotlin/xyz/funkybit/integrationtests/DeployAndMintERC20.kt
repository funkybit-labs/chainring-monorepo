package xyz.funkybit.integrationtests

import xyz.funkybit.core.blockchain.ChainManager
import xyz.funkybit.core.model.Address
import xyz.funkybit.core.model.toChecksumAddress
import xyz.funkybit.core.utils.toFundamentalUnits
import xyz.funkybit.tasks.FixturesBlockchainClient
import java.math.BigDecimal
import java.math.BigInteger

class DeployAndMintERC20 {

    // to deploy an erc-20 to a specific chain/chains and mint to a specified address, set the receiver address below,
    // uncomment the @Test property and set these environment variables:
    // EVM_CHAINS=Chain1,Chain2
    // EVM_NETWORK_URL_Chain1=...
    // EVM_NETWORK_URL_Chain2=...
    // DEFAULT_MAX_PRIORITY_FEE_PER_GAS_WEI_Chain1=...
    // DEFAULT_MAX_PRIORITY_FEE_PER_GAS_WEI_Chain2=...
    // EVM_CONTRACT_MANAGEMENT_PRIVATE_KEY=...
    // @Test
    fun `deploy and mint erc-20s`() {
        val tokenName = "FUNK"
        val decimals = BigInteger.valueOf(18L)
        val receiver = Address("0xA1AA16E2C4AAD014A89a6cF873B4bA5C31d060FC")
        ChainManager.blockchainConfigs.map { config ->
            val client = FixturesBlockchainClient(config)
            val address = client.deployMockERC20(tokenName, decimals)
            println("$tokenName deployed to ${config.name} at ${address.toChecksumAddress()}")
            client.sendMintERC20Tx(address, receiver, BigDecimal.valueOf(1000000L).toFundamentalUnits(18))
        }
    }
}
