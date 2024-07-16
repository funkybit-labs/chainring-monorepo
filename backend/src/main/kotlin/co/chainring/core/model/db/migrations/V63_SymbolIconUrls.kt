package co.chainring.core.model.db.migrations

import co.chainring.core.db.Migration
import org.jetbrains.exposed.sql.transactions.transaction

@Suppress("ClassName")
class V63_SymbolIconUrls : Migration() {

    override fun run() {
        transaction {
            exec(
                """
                UPDATE symbol SET icon_url = '/src/assets/' || LOWER(SPLIT_PART(name, ':', 1)) ||'.svg'
                """.trimIndent(),
            )
            exec(
                """
                ALTER TABLE symbol ALTER COLUMN icon_url SET NOT NULL
                """.trimIndent(),
            )
        }
    }
}
