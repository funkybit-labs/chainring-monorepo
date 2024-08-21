package xyz.funkybit.core.services

import io.github.oshai.kotlinlogging.KotlinLogging
import xyz.funkybit.core.blockchain.bitcoin.MempoolSpaceApi
import xyz.funkybit.core.blockchain.bitcoin.MempoolSpaceClient
import xyz.funkybit.core.model.BitcoinAddress
import xyz.funkybit.core.model.bitcoin.UtxoId
import xyz.funkybit.core.model.db.BitcoinWalletStateEntity
import xyz.funkybit.core.model.db.UnspentUtxo
import xyz.funkybit.core.utils.bitcoin.BitcoinInputsSelector
import java.math.BigInteger

object UtxoSelectionService {

    private val logger = KotlinLogging.logger {}

    private val inputsSelector = BitcoinInputsSelector()

    fun selectUtxos(address: BitcoinAddress, amount: BigInteger, fee: BigInteger): List<UnspentUtxo> {
        val unspentUtxos = refreshUnspentUtxos(address)
        return inputsSelector.selectInputs(
            amount,
            unspentUtxos.filter { it.reservedBy == null },
            fee,
        )
    }

    fun refreshUnspentUtxos(address: BitcoinAddress): List<UnspentUtxo> {
        val bitcoinWalletState = BitcoinWalletStateEntity.findByAddress(address)

        val unspentUtxos = bitcoinWalletState?.unspentUtxos?.associate { it.utxoId to it }?.toMutableMap() ?: mutableMapOf()

        val lastSeenBlock = updateUtxoMap(unspentUtxos, address, bitcoinWalletState?.lastSeenBlockHeight)
        return unspentUtxos.values.toList().also {
            logger.debug { "available amount is ${it.sumOf { u -> u.amount }}" }
            if (bitcoinWalletState != null) {
                bitcoinWalletState.update(lastSeenBlock, it)
            } else {
                BitcoinWalletStateEntity.create(address, lastSeenBlock, it)
            }
        }
    }

    fun reserveUtxos(address: BitcoinAddress, utxoIds: Set<UtxoId>, reservedBy: String) {
        BitcoinWalletStateEntity.findByAddress(address)?.let { state ->
            state.update(
                state.lastSeenBlockHeight,
                state.unspentUtxos.map {
                    if (utxoIds.contains(it.utxoId)) {
                        if (it.reservedBy != null && it.reservedBy != reservedBy) {
                            throw Exception("trying to reserve an already reserved utxo")
                        }
                        it.copy(reservedBy = reservedBy)
                    } else {
                        it
                    }
                },
            )
        }
    }

    private fun updateUtxoMap(utxoMap: MutableMap<UtxoId, UnspentUtxo>, walletAddress: BitcoinAddress, lastSeenBlockHeight: Long?): Long? {
        logger.debug { "updating utxos for $walletAddress lastSeenBlockHeight=$lastSeenBlockHeight" }

        // mempool pagination is a little odd
        // when the address/{addr}/txs endpoint for an address is called with no after-txid query param it will return
        // up to 50 txs in the mempool and a page (docs says 25 but seems to be 10) of the most recent confirmed transactions.
        // Taking the last txId in the confirmed list and recalling the endpoint with after-txid query param set will return
        // the prior page of transactions. Thus to initially find all transactions for an address we have to walk backwards
        //
        // Separately we keep a last seen id, which is the most recent confirmed tx we have seen. Then when we pull the most
        // recent confirmed txs we work back till til we see the last seen one.
        //

        val allTxs = mutableListOf<MempoolSpaceApi.Transaction>()

        var txs = MempoolSpaceClient.getTransactions(walletAddress, null)
        var hasMore = true

        while (hasMore) {
            if (txs.isNotEmpty()) {
                run loop@{
                    txs.forEach {
                        if (it.status.blockHeight != null && lastSeenBlockHeight != null && it.status.blockHeight < lastSeenBlockHeight) {
                            logger.debug { "breaking out since we have already seen this block ${it.status.blockHeight} $lastSeenBlockHeight" }
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
        val spentUtxos = allTxs.map { tx -> tx.vins.filter { it.prevOut.scriptPubKeyAddress == walletAddress }.map { UtxoId.fromTxHashAndVout(it.txId, it.vout) } }.flatten().toSet()

        allTxs.reversed().forEach { tx ->
            spentUtxos.forEach {
                utxoMap.remove(it)
            }
            tx.vouts.forEachIndexed { index, vout ->
                if (vout.scriptPubKeyAddress == walletAddress) {
                    val utxoId = UtxoId.fromTxHashAndVout(tx.txId, index)
                    if (!spentUtxos.contains(utxoId)) {
                        utxoMap[utxoId] = UnspentUtxo(
                            utxoId,
                            vout.value,
                            tx.status.blockHeight,
                            utxoMap[utxoId]?.reservedBy,
                        )
                    }
                }
            }
        }
        return allTxs.firstOrNull { it.status.confirmed }?.status?.blockHeight ?: lastSeenBlockHeight
    }
}
