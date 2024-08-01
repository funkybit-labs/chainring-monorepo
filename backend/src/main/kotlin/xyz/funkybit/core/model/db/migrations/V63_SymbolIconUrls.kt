package xyz.funkybit.core.model.db.migrations

import org.jetbrains.exposed.sql.transactions.transaction
import xyz.funkybit.core.db.Migration

@Suppress("ClassName")
class V63_SymbolIconUrls : Migration() {

    override fun run() {
        transaction {
            exec(
                """
                UPDATE symbol SET icon_url = 'https://chainring-web-icons.s3.us-east-2.amazonaws.com/symbols/' || LOWER(SPLIT_PART(name, ':', 1)) || '.svg'
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
