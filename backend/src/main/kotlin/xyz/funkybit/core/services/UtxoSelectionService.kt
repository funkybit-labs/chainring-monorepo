package xyz.funkybit.core.services

import xyz.funkybit.core.model.BitcoinAddress
import xyz.funkybit.core.model.db.ArchAccountEntity
import xyz.funkybit.core.model.db.BitcoinUtxoEntity
import xyz.funkybit.core.model.db.DeployedSmartContractEntity
import xyz.funkybit.core.utils.bitcoin.BitcoinInputsSelector
import java.math.BigInteger

object UtxoSelectionService {

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

    fun selectUtxosForProgram(amount: BigInteger, fee: BigInteger): List<BitcoinUtxoEntity> {
        val rentUtxoId = ArchAccountEntity.findProgramAccount()!!.utxoId
        return selectUtxos(DeployedSmartContractEntity.programBitcoinAddress(), amount, fee).filterNot { it.guid.value == rentUtxoId }
    }

    fun reserveUtxos(utxos: List<BitcoinUtxoEntity>, reservedBy: String) {
        BitcoinUtxoEntity.reserve(utxos, reservedBy)
    }
}
