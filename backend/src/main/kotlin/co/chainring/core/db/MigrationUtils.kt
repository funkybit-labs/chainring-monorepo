package co.chainring.core.db

import co.chainring.core.model.db.enumDeclaration
import co.chainring.core.utils.generateHexString
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.transactions.transaction

inline fun <reified T : Enum<T>> updateEnum(columns: List<Column<*>>, enumName: String) {
    transaction {
        val tempName = "${enumName}_${generateHexString(4)}"
        exec("CREATE TYPE $tempName AS ENUM (${enumDeclaration<T>()})")
        columns.forEach { column ->
            exec("""ALTER TABLE "${column.table.tableName}" ALTER COLUMN ${column.name} TYPE $tempName USING ${column.name}::text::$tempName""")
        }
        exec("DROP TYPE $enumName")
        exec("ALTER TYPE $tempName RENAME TO $enumName")
    }
}
