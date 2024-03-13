package co.chainring.core.model.db

import org.postgresql.util.PGobject

class PGEnum<T : Enum<T>>(enumTypeName: String, enumValue: T?) : PGobject() {
    init {
        value = enumValue?.name
        type = enumTypeName
    }
}

inline fun <reified T : Enum<T>> enumDeclaration() = enumValues<T>().joinToString(", ") { "'$it'" }
