package co.chainring.tasks

import co.chainring.core.db.DbConfig
import co.chainring.core.db.connect
import co.chainring.core.model.db.ChainEntity
import co.chainring.core.model.db.MarketEntity
import co.chainring.core.model.db.MarketId
import co.chainring.core.model.db.SymbolEntity
import co.chainring.core.model.db.SymbolId
import co.chainring.tasks.fixtures.Fixtures
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction

fun seedDatabase(fixtures: Fixtures, symbolContractAddresses: List<SymbolContractAddress>) {
    val db = Database.connect(DbConfig())
    TransactionManager.defaultDatabase = db

    transaction {
        fixtures.chains.forEach { chain ->
            if (ChainEntity.findById(chain.id) == null) {
                ChainEntity.create(chain.id, chain.name).flush()
                println("Created chain ${chain.name} with id=${chain.id}")
            }
        }

         val symbolEntities = fixtures.symbols.map { symbol ->
             SymbolEntity.findById(SymbolId(symbol.chainId, symbol.name))
                 ?: run {
                     SymbolEntity.create(
                         symbol.name,
                         symbol.chainId,
                         contractAddress = if (symbol.isNative) {
                             null
                         } else {
                             symbolContractAddresses.first { it.symbolId == symbol.id }.address
                         },
                         decimals = symbol.decimals.toUByte(),
                         description = ""
                     ).also {
                         it.flush()
                         println("Created symbol ${symbol.name} with guid=${it.guid.value}")
                     }
                 }
         }.associateBy { it.id.value }

        fixtures.markets.forEach { (baseSymbolId, quoteSymbolId, tickSize) ->
            val baseSymbol = symbolEntities.getValue(baseSymbolId)
            val quoteSymbol = symbolEntities.getValue(quoteSymbolId)

            if (MarketEntity.findById(MarketId(baseSymbol, quoteSymbol)) == null) {
                val marketEntity = MarketEntity
                    .create(baseSymbol, quoteSymbol, tickSize)
                    .also {
                        it.flush()
                    }

                println("Created market ${marketEntity.guid.value}")
            }
        }
    }
}

