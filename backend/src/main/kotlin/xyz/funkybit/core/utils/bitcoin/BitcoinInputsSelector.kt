package xyz.funkybit.core.utils.bitcoin

import xyz.funkybit.core.model.db.BitcoinUtxoEntity
import java.math.BigInteger

private typealias InputShuffleFn = (List<BitcoinUtxoEntity>) -> List<BitcoinUtxoEntity>

class BitcoinInputsSelector(
    private val iterations: Int = 10,
    private val shuffleInputs: InputShuffleFn = { it.shuffled() },
) {

    fun selectInputs(
        amount: BigInteger,
        availableInputs: List<BitcoinUtxoEntity>,
        fee: BigInteger,
    ): List<BitcoinUtxoEntity> {
        val totalAvailable = availableInputs.sumOf { it.amount }

        if (totalAvailable < amount + fee) {
            throw BitcoinInsufficientFundsException("Insufficient funds, needed ${amount + fee}, but only $totalAvailable BTC available")
        }

        val selectionCandidates = (1..iterations).mapNotNull {
            singleRandomDraw(amount, availableInputs, fee)
        }

        return selectionCandidates
            .minByOrNull { it.amountLocked }?.inputs
            ?: throw BitcoinInsufficientFundsException("Insufficient funds, needed ${amount + fee} including fee, but only $totalAvailable BTC available")
    }

    private data class InputsSelectionCandidate(
        val inputs: List<BitcoinUtxoEntity>,
        val amountLocked: BigInteger,
    )

    // see https://murch.one/wp-content/uploads/2016/11/erhardt2016coinselection.pdf
    private fun singleRandomDraw(
        requestedAmount: BigInteger,
        availableInputs: List<BitcoinUtxoEntity>,
        fee: BigInteger,
    ): InputsSelectionCandidate? {
        val selectedInputs = mutableListOf<BitcoinUtxoEntity>()
        var selectedAmount = BigInteger.ZERO

        shuffleInputs(availableInputs).forEach { input ->
            selectedInputs.add(input)
            selectedAmount += input.amount

            if (selectedAmount >= requestedAmount + fee) {
                return InputsSelectionCandidate(selectedInputs, selectedAmount)
            }
        }

        return null
    }
}

open class BitcoinInsufficientFundsException(
    message: String,
) : Exception(message)
