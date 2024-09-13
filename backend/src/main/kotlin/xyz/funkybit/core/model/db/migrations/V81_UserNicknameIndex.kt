package xyz.funkybit.core.model.db.migrations

import org.jetbrains.exposed.sql.transactions.transaction
import xyz.funkybit.core.db.Migration

@Suppress("ClassName")
class V81_UserNicknameIndex : Migration() {

    override fun run() {
        transaction {
            // add unique index on nick name
            exec("""CREATE UNIQUE INDEX user_nick_name ON "user" (nick_name)""")
        }
    }
}
