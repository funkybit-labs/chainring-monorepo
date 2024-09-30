package xyz.funkybit.core.services

import io.github.oshai.kotlinlogging.KotlinLogging
import xyz.funkybit.core.blockchain.bitcoin.MempoolSpaceApi
import xyz.funkybit.core.blockchain.bitcoin.MempoolSpaceClient
import xyz.funkybit.core.blockchain.bitcoin.bitcoinConfig
import xyz.funkybit.core.model.BitcoinAddress
import xyz.funkybit.core.model.db.BitcoinUtxoAddressMonitorEntity
import xyz.funkybit.core.model.db.BitcoinUtxoEntity
import xyz.funkybit.core.model.db.BitcoinUtxoId
import xyz.funkybit.core.model.db.DepositEntity
import xyz.funkybit.core.model.db.SymbolEntity
import xyz.funkybit.core.model.db.WalletEntity
import xyz.funkybit.core.utils.bitcoin.BitcoinInputsSelector
import java.math.BigInteger

object UtxoManager {

    private val logger = KotlinLogging.logger {}
    private val inputsSelector = BitcoinInputsSelector()

    fun getAllUnspent(address: BitcoinAddress): List<BitcoinUtxoEntity> {
        return BitcoinUtxoEntity.findUnspentByAddress(address)
    }

    fun selectUtxos(address: BitcoinAddress, amount: BigInteger, fee: BigInteger): List<BitcoinUtxoEntity> {
        return inputsSelector.selectInputs(
            amount,
            getAllUnspent(address),
            fee,
        )
    }

    fun reserveUtxos(utxos: List<BitcoinUtxoEntity>, reservedBy: String) {
        BitcoinUtxoEntity.reserve(utxos, reservedBy)
    }

    fun refreshUtxos(address: BitcoinAddress) {
        val bitcoinAddressMonitor = BitcoinUtxoAddressMonitorEntity.createIfNotExists(address)

        refresh(address, bitcoinAddressMonitor)?.let { lastSeenBlockHeight ->
            bitcoinAddressMonitor.updateLastSeenBlockHeight(lastSeenBlockHeight)
        }
    }

    private fun refresh(walletAddress: BitcoinAddress, addressInfo: BitcoinUtxoAddressMonitorEntity): Long? {
        logger.debug { "updating utxos for $walletAddress lastSeenBlockHeight=${addressInfo.lastSeenBlockHeight}" }

        // mempool pagination is a little odd
        // when the address/{addr}/txs endpoint for an address is called with no after-txid query param it will return
        // up to 50 txs in the mempool and a page (docs says 25 but seems to be 10) of the most recent confirmed transactions.
        // Taking the last txId in the confirmed list and recalling the endpoint with after-txid query param set will return
        // the prior page of transactions. Thus to initially find all transactions for an address we have to walk backwards
        //
        // Separately we keep a last seen confirmed block id with a tx, and then we start querying till and go back til we
        // see that block
        //
        val lastSeenBlockHeight = addressInfo.lastSeenBlockHeight

        val allTxs = mutableListOf<MempoolSpaceApi.Transaction>()
        var txs = MempoolSpaceClient.getTransactions(walletAddress, null).filter { addressInfo.allowMempoolTxs || it.status.confirmed }
        var hasMore = true

        while (hasMore) {
            if (txs.isNotEmpty()) {
                run loop@{
                    txs.forEach {
                        if (it.status.blockHeight != null && lastSeenBlockHeight != null && it.status.blockHeight < lastSeenBlockHeight) {
                            hasMore = false
                            return@loop
                        }
                        allTxs.add(it)
                    }
                    val lastId = txs.lastOrNull { it.status.confirmed }?.txId
                    if (lastId != null) {
                        txs =
                            MempoolSpaceClient.getTransactions(walletAddress, lastId).filterNot { it.txId == lastId }
                    } else {
                        hasMore = false
                    }
                }
            } else {
                hasMore = false
            }
        }
        return refreshForTxs(
            allTxs.filterNot {
                addressInfo.skipTxIds.contains(it.txId.value)
            },
            walletAddress,
            addressInfo.isDepositAddress,
        ) ?: lastSeenBlockHeight
    }

    private fun refreshForTxs(txs: List<MempoolSpaceApi.Transaction>, walletAddress: BitcoinAddress, isDepositAddress: Boolean): Long? {
        val spentUtxoMap = txs.associate { tx ->
            tx.txId to tx.inputsMatchingWallet(walletAddress).map {
                BitcoinUtxoId.fromTxHashAndVout(it.txId, it.vout)
            }
        }
        spentUtxoMap.forEach { (txId, spentUtxos) ->
            if (spentUtxos.isNotEmpty()) {
                logger.debug { "spending $spentUtxos by $txId" }
                BitcoinUtxoEntity.spend(spentUtxos, txId)
            }
        }
        val spentUtxos = spentUtxoMap.flatMap { it.value }.toSet()
        txs.reversed().forEach { tx ->
            tx.vouts.forEachIndexed { index, vout ->
                if (vout.scriptPubKeyAddress == walletAddress) {
                    val utxoId = BitcoinUtxoId.fromTxHashAndVout(tx.txId, index)
                    if (!spentUtxos.contains(utxoId)) {
                        BitcoinUtxoEntity.createIfNotExist(
                            utxoId,
                            walletAddress,
                            vout.value.toLong(),
                        )
                    }
                }
            }
            // if this the deposit address and
            // any of the outs are to this address and
            // none of the ins are from this address (otherwise the out is change) then see if we need to create a deposit
            if (isDepositAddress &&
                tx.vouts.any { it.scriptPubKeyAddress == walletAddress } &&
                !tx.vins.any { it.prevOut.scriptPubKeyAddress == walletAddress }
            ) {
                WalletEntity.findByAddresses(tx.vins.map { it.prevOut.scriptPubKeyAddress }.toSet().toList())?.let { wallet ->
                    DepositEntity.createOrUpdate(
                        wallet = wallet,
                        symbol = SymbolEntity.forChainAndContractAddress(bitcoinConfig.chainId, null),
                        amount = tx.outputsMatchingWallet(walletAddress).sumOf { it.value },
                        blockNumber = tx.status.blockHeight?.toBigInteger(),
                        transactionHash = xyz.funkybit.core.model.TxHash(tx.txId.value),
                    )
                }
            }
        }
        return txs.firstOrNull { it.status.confirmed }?.status?.blockHeight
    }
}
