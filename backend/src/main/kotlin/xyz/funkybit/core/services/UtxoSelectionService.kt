package xyz.funkybit.core.services

import io.github.oshai.kotlinlogging.KotlinLogging
import xyz.funkybit.core.blockchain.bitcoin.MempoolSpaceApi
import xyz.funkybit.core.blockchain.bitcoin.MempoolSpaceClient
import xyz.funkybit.core.model.db.BitcoinWalletStateEntity
import xyz.funkybit.core.model.db.UnspentUtxo
import xyz.funkybit.core.utils.bitcoin.BitcoinInputsSelector
import java.math.BigInteger

object UtxoSelectionService {

    private val logger = KotlinLogging.logger {}

    private val inputsSelector = BitcoinInputsSelector()

    fun selectUtxos(address: String, amount: BigInteger, fee: BigInteger): List<UnspentUtxo> {
        val unspentUtxos = refreshUnspentUtxos(address)
        return inputsSelector.selectInputs(
            amount,
            unspentUtxos,
            fee,
        )
    }

    fun refreshUnspentUtxos(address: String): List<UnspentUtxo> {
        val bitcoinWalletState = BitcoinWalletStateEntity.findByAddress(address)

        val previousUnspentUtxos = bitcoinWalletState?.unspentUtxos?.associate { it.utxoId to it }?.toMutableMap() ?: mutableMapOf()

        val lastTxId = updateUtxoMap(previousUnspentUtxos, address, bitcoinWalletState?.lastTxId)
        return previousUnspentUtxos.values.toList().also {
            if (bitcoinWalletState != null) {
                bitcoinWalletState.update(lastTxId, it)
            } else {
                BitcoinWalletStateEntity.create(address, lastTxId, it)
            }
        }
    }

    private fun updateUtxoMap(utxoMap: MutableMap<String, UnspentUtxo>, walletAddress: String, lastSeenTxId: String?): String? {
        logger.debug { "updating utxos for $walletAddress lastSeenTxId=$lastSeenTxId" }

        // mempool pagination is a little odd
        // when the address/{addr}/txs endpoint for an address is called with no after-txid query param it will return
        // up to 50 txs in the mempool and a page (docs says 25 but seems to be 10) of the most recent confirmed transactions.
        // Taking the last txId in the confirmed list and recalling the endpoint with after-txid query param set will return
        // the prior page of transactions. Thus to initially find all transactions for an address we have to walk backwards
        //
        // Separately we keep a last seen id, which is the most recent confirmed tx we have seen. Then when we pull the most
        // recent confirmed txs we work back till til we see the last seen one.
        //
        // WRT to mempool - if we see a tx in mempool spending a UTXO then it is marked as spent, however we do not use
        // outputs to the wallet that are in the mempool as spendable - they will be spendable once there is a confirm.

        val allConfirmed = mutableListOf<MempoolSpaceApi.Transaction>()

        var (confirmed, unconfirmed) = MempoolSpaceClient.getTransactions(walletAddress, null).partition { it.status.confirmed }
        val spentUtxosInMempool = unconfirmed.map { tx -> tx.vins.filter { it.prevOut.scriptPubKeyAddress == walletAddress }.map { "${it.txId}:${it.vout}" } }.flatten().toSet()
        var hasMore = true

        while (hasMore) {
            if (confirmed.isNotEmpty()) {
                run loop@{
                    confirmed.forEach {
                        if (it.txId == lastSeenTxId) {
                            hasMore = false
                            return@loop
                        }
                        allConfirmed.add(it)
                    }
                    val lastId = confirmed.last().txId
                    confirmed = MempoolSpaceClient.getTransactions(walletAddress, confirmed.last().txId).filterNot { it.txId == lastId }
                }
            } else {
                hasMore = false
            }
        }
        allConfirmed.reversed().forEach { tx ->
            spentUtxosInMempool.forEach {
                utxoMap.remove(it)
            }
            tx.vins.filter { it.prevOut.scriptPubKeyAddress == walletAddress }.forEach {
                utxoMap.remove("${it.txId}:${it.vout}")
            }
            tx.vouts.forEachIndexed { index, vout ->
                if (vout.scriptPubKeyAddress == walletAddress) {
                    val utxoId = "${tx.txId}:$index"
                    if (!spentUtxosInMempool.contains(utxoId)) {
                        utxoMap[utxoId] = UnspentUtxo(utxoId, vout.value, tx.status.blockHeight)
                    }
                }
            }
        }
        return allConfirmed.firstOrNull()?.txId ?: lastSeenTxId
    }
}
