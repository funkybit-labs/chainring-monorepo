package xyz.funkybit.apps.ring

import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import xyz.funkybit.core.blockchain.ContractType
import xyz.funkybit.core.blockchain.bitcoin.BitcoinClient
import xyz.funkybit.core.model.Address
import xyz.funkybit.core.model.BitcoinAddress
import xyz.funkybit.core.model.TxHash
import xyz.funkybit.core.model.db.BitcoinUtxoAddressMonitorEntity
import xyz.funkybit.core.model.db.BitcoinUtxoEntity
import xyz.funkybit.core.model.db.BitcoinUtxoId
import xyz.funkybit.core.model.db.BlockEntity
import xyz.funkybit.core.model.db.BlockTable
import xyz.funkybit.core.model.db.ChainEntity
import xyz.funkybit.core.model.db.ChainId
import xyz.funkybit.core.model.db.DeployedSmartContractEntity
import xyz.funkybit.core.model.db.DepositEntity
import xyz.funkybit.core.model.db.SymbolEntity
import xyz.funkybit.core.model.db.WalletEntity
import xyz.funkybit.core.model.rpc.BitcoinRpc
import xyz.funkybit.core.utils.toSatoshi
import java.math.BigInteger
import kotlin.concurrent.thread

class BitcoinBlockProcessor(
    private val pollingIntervalInMs: Long =
        System.getenv("BITCOIN_BLOCK_PROCESSOR_POLLING_INTERVAL_MS")?.toLongOrNull()
            ?: 1000L,
) {
    val logger = KotlinLogging.logger {}
    private val chainId = BitcoinClient.chainId
    private var workerThread: Thread? = null

    private var chainEntryExists: Boolean = false

    private val lookbackBlockCount = 200

    fun stop() {
        workerThread?.let {
            it.interrupt()
            it.join(100)
        }
    }

    fun start() {
        logger.debug { "Starting bitcoin block processor" }

        workerThread = thread(start = true, name = "bitcoin-block-processor", isDaemon = true) {
            while (true) {
                try {
                    if (validateChainExists()) {
                        processBlocks()
                    }
                    Thread.sleep(pollingIntervalInMs)
                } catch (ie: InterruptedException) {
                    logger.warn { "Exiting bitcoin block processor thread" }
                    return@thread
                } catch (e: Exception) {
                    logger.error(e) { "Unhandled exception in bitcoin block processor" }
                }
            }
        }
    }

    fun processBlocks() {
        val (blocksToProcess, lastHash) = transaction {
            val lastProcessed = getLastProcessedBlock()
            val currentBlockHeight = BitcoinClient.getBlockCount()
            Pair(
                (lastProcessed?.number?.let { it.toLong() + 1L } ?: (currentBlockHeight - lookbackBlockCount)).rangeTo(currentBlockHeight),
                lastProcessed?.hash ?: BitcoinClient.getBlockHash(currentBlockHeight - lookbackBlockCount - 1),
            )
        }

        var lastProcessedHash: String = lastHash
        var nextBlockHash: String? = null
        for (blockNumber in blocksToProcess) {
            val header = BitcoinClient.getBlockHeader(nextBlockHash ?: BitcoinClient.getBlockHash(blockNumber))
            if (header.confirmations < 1) { // not sure if this will happen, but don't process til 1 confirmation
                return
            }
            // if the previous block for the new one is the last one we processed,
            // then process the new block normally otherwise find split point
            if (header.previousBlockhash == lastProcessedHash) {
                nextBlockHash = processBlock(blockNumber, nextBlockHash)
                lastProcessedHash = header.hash
            } else {
                transaction {
                    handleSwitchToNewBestChain(blockNumber)
                }
                // this will delete blocks handled after the split point, so next run will start
                // from block after the split point again.
                return
            }
        }
    }

    private val maxBlocksToRollbackOnFork =
        BigInteger.valueOf((System.getenv("BITCOIN_DEPOSIT_HANDLER_NUM_CONFIRMATIONS")?.toIntOrNull() ?: BitcoinDepositHandler.DEFAULT_NUM_CONFIRMATIONS) + 1L)

    private fun validateChainExists(): Boolean {
        if (!chainEntryExists) {
            chainEntryExists = transaction { ChainEntity.findById(chainId) } != null
        }
        return chainEntryExists
    }

    private fun handleSwitchToNewBestChain(blockHeight: Long) {
        // worthy of an alarm, so we can check out the recovery
        logger.error { "It looks like we forked, looking for split point starting at $blockHeight" }
        val blocksToRollback = BlockEntity.getRecentBlocksUpToNumber(blockHeight.toBigInteger().subtract(maxBlocksToRollbackOnFork + BigInteger.TWO), chainId).filterNot { block ->
            val hashFromNode = BitcoinClient.getBlockHash(block.number.toLong())
            hashFromNode == block.hash
        }

        // finds the blocks to rollback
        if (blocksToRollback.size > maxBlocksToRollbackOnFork.toInt()) {
            logger.error { "Failed to rollback - Found ${blocksToRollback.size} to rollback, max allowed is $maxBlocksToRollbackOnFork" }
            return
        }

        // ok to try to rollback
        blocksToRollback.forEach {
            rollbackBlock(it)
        }
    }

    private fun processBlock(blockHeight: Long, blockHash: String? = null): String? {
        val hash = blockHash ?: BitcoinClient.getBlockHash(blockHeight)
        val block = BitcoinClient.getBlock(hash)
        logger.debug { "Processing Block $blockHeight, has ${block.numberOfTx} transactions" }
        transaction {
            val newBlock = BlockEntity.create(block, blockHeight, chainId)
            val addresses = BitcoinUtxoAddressMonitorEntity.getMonitoredAddresses().map { it.value }.toSet()
            logger.debug { "addresses being monitored $addresses" }
            val unspentUtxos = BitcoinUtxoEntity.findAllReservedOrUnspent().map { it.guid.value }.toSet()
            val utxosSpent = mutableListOf<BitcoinUtxoId>()
            (block.transactions ?: listOf()).forEach { tx ->

                // check if any unspent utxos we currently know about are in the inputs - they are being spent
                utxosSpent.addAll(inputsMatchingUnspentUtxos(tx, unspentUtxos))

                // now check the outputs for any addresses for addresses we are monitoring
                outputsMatchingWallets(tx, addresses).forEach { txOut ->
                    val address = BitcoinAddress.canonicalize(
                        txOut.scriptPubKey.address ?: txOut.scriptPubKey.addresses!!.toSet().intersect(addresses).first(),
                    )
                    val amount = txOut.value.toSatoshi()
                    logger.debug { "Found a transaction, $address $amount, $tx" }
                    BitcoinUtxoEntity.create(
                        BitcoinUtxoId.fromTxHashAndVout(tx.txId, txOut.index),
                        address,
                        newBlock,
                        amount,
                    )
                    handleDepositsToExchange(tx, address, amount.toBigInteger(), newBlock)
                }
            }
            if (utxosSpent.isNotEmpty()) {
                logger.debug { "Utxos spent = $utxosSpent in ${newBlock.number}" }
                BitcoinUtxoEntity.spend(utxosSpent, newBlock)
            }
        }
        logger.debug { "Successfully processed Block $blockHeight" }
        return block.nextBlockhash
    }

    private fun rollbackBlock(block: BlockEntity) {
        transaction {
            if (DepositEntity.countConfirmedOrCompleted(listOf(block.number), chainId) > 0) {
                logger.error { "Failed to rollback due to finalized deposits in blocks to rollback, chainId=$chainId" }
            } else {
                DepositEntity.markAsFailedByBlockNumbers(listOf(block.number), chainId, error = "Fork rollback")
            }
            BitcoinUtxoEntity.rollback(block)
            block.delete()
        }
    }

    private fun handleDepositsToExchange(tx: BitcoinRpc.Transaction, address: BitcoinAddress, amount: BigInteger, blockEntity: BlockEntity) {
        DeployedSmartContractEntity.validContracts(ChainId(0u)).firstOrNull { it.name == ContractType.Exchange.name }?.let {
            val programBitcoinAddress = it.proxyAddress as BitcoinAddress
            if (address == programBitcoinAddress) {
                // this will only catch deposits if they were not added via the API endpoint which they should be
                // Furthermore this may not work reliably since for hierarchical wallets, the source of the UTXO may
                // be some child address now necessarily the wallet address registered with us
                getSourceAddress(tx)?.let { sourceAddress ->
                    WalletEntity.findByAddress(Address.auto(sourceAddress))?.let {
                        DepositEntity.createOrUpdate(
                            wallet = it,
                            symbol = SymbolEntity.forChainAndContractAddress(chainId, null),
                            amount = amount,
                            blockNumber = blockEntity.number,
                            transactionHash = TxHash(tx.txId.value),
                        )
                    }
                }
            }
        }
    }

    private fun getSourceAddress(tx: BitcoinRpc.Transaction): String? {
        return try {
            if (tx.txIns.isNotEmpty() && tx.txIns[0].txId != null && tx.txIns[0].outIndex != null) {
                BitcoinClient.getRawTransaction(tx.txIns[0].txId!!)?.txOuts?.firstOrNull {
                    it.index == tx.txIns[0].outIndex
                }?.let {
                    it.scriptPubKey.addresses?.firstOrNull() ?: it.scriptPubKey.address
                }
            } else {
                null
            }
        } catch (e: Exception) {
            logger.warn(e) { "Unable to get source address" }
            null
        }
    }

    private fun inputsMatchingUnspentUtxos(tx: BitcoinRpc.Transaction, unspentUtxos: Set<BitcoinUtxoId>) =
        tx.txIns.mapNotNull { txIn ->
            if (txIn.txId != null && txIn.outIndex != null) {
                BitcoinUtxoId.fromTxHashAndVout(
                    txIn.txId,
                    txIn.outIndex,
                )
            } else {
                null
            }
        }.filter {
            unspentUtxos.contains(it)
        }

    private fun outputsMatchingWallets(tx: BitcoinRpc.Transaction, addresses: Set<String>) =
        tx.txOuts.filter { txOut ->
            (
                txOut.scriptPubKey.addresses != null &&
                    txOut.scriptPubKey.addresses.toSet().intersect(addresses).isNotEmpty()
                ) ||
                (txOut.scriptPubKey.address != null && addresses.contains(txOut.scriptPubKey.address))
        }

    private fun getLastProcessedBlock(): BlockEntity? =
        BlockTable
            .selectAll()
            .where { BlockTable.chainId.eq(chainId) }
            .orderBy(Pair(BlockTable.number, SortOrder.DESC))
            .limit(1)
            .map { BlockEntity.wrapRow(it) }
            .firstOrNull()
}
