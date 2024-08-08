package xyz.funkybit.core.db

import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.transactions.transaction
import xyz.funkybit.core.model.db.enumDeclaration
import xyz.funkybit.core.utils.generateHexString

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
