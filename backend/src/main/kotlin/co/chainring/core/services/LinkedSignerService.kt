package co.chainring.core.services

import co.chainring.core.db.notifyDbListener
import co.chainring.core.model.Address
import co.chainring.core.model.db.ChainId
import co.chainring.core.model.db.WalletEntity
import co.chainring.core.model.db.WalletLinkedSigner
import co.chainring.core.model.db.WalletLinkedSignerEntity
import co.chainring.core.utils.PgListener
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.concurrent.ConcurrentHashMap

typealias LinkedSigners = ConcurrentHashMap<Address, Address>

object LinkedSignerService {
    private val linkedSignerByChain = ConcurrentHashMap<ChainId, LinkedSigners>()
    private const val CHANNEL_NAME = "linked_signer_ctl"

    private lateinit var pgListener: PgListener

    fun start(db: Database) {
        loadAll()

        pgListener = PgListener(
            db,
            threadName = "linked-signer-listener",
            channel = CHANNEL_NAME,
            onReconnect = {
                loadAll()
            },
            onNotifyLogic = { notification ->
                val walletLinkedSigner =
                    Json.decodeFromJsonElement<WalletLinkedSigner>(Json.parseToJsonElement(notification.parameter))
                if (walletLinkedSigner.signerAddress == Address.zero) {
                    removeSigner(walletLinkedSigner)
                } else {
                    addSigner(walletLinkedSigner)
                }
            },
        )
        pgListener.start()
    }

    fun stop() {
        if (this::pgListener.isInitialized) {
            pgListener.stop()
        }
        linkedSignerByChain.clear()
    }

    private fun loadAll() {
        linkedSignerByChain.clear()
        transaction {
            WalletLinkedSignerEntity.findAll().forEach {
                addSigner(it)
            }
        }
    }

    private fun addSigner(linkedSigner: WalletLinkedSigner) {
        linkedSignerByChain.getOrPut(linkedSigner.chainId) {
            LinkedSigners()
        }[linkedSigner.walletAddress] = linkedSigner.signerAddress
    }

    private fun removeSigner(linkedSigner: WalletLinkedSigner) {
        linkedSignerByChain.getOrPut(linkedSigner.chainId) {
            LinkedSigners()
        }.remove(linkedSigner.walletAddress)
    }

    fun getLinkedSigner(walletAddress: Address, chainId: ChainId): Address? = linkedSignerByChain[chainId]?.get(walletAddress)

    fun createOrUpdateWalletLinkedSigner(walletAddress: Address, chainId: ChainId, linkedSignerAddress: Address) {
        val walletEntity = WalletEntity.getOrCreate(walletAddress)
        if (linkedSignerAddress == Address.zero) {
            WalletLinkedSignerEntity.findByWalletAndChain(walletEntity, chainId)?.delete()
        } else {
            WalletLinkedSignerEntity.createOrUpdate(
                wallet = walletEntity,
                chainId = chainId,
                signerAddress = linkedSignerAddress,
            )
        }
        TransactionManager.current().notifyDbListener(
            CHANNEL_NAME,
            Json.encodeToString(WalletLinkedSigner(walletAddress, chainId, linkedSignerAddress)),
        )
    }
}
