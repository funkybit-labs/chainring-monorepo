package co.chainring.core.db

import io.github.oshai.kotlinlogging.KLogger
import kotlinx.datetime.Clock
import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils.create
import org.jetbrains.exposed.sql.exists
import org.jetbrains.exposed.sql.insertIgnoreAndGetId
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import org.jetbrains.exposed.sql.transactions.transaction

abstract class Migration {
    val name: String
    val version: Int

    init {
        val groups =
            Regex("^V(\\d+)_(.*)$").matchEntire(this::class.simpleName!!)?.groupValues
                ?: throw IllegalArgumentException("Migration class name doesn't match convention")
        version = groups[1].toInt()
        name = groups[2]
    }

    abstract fun run()
}

object Migrations : IdTable<Int>() {
    override val id = integer("version").entityId()
    override val primaryKey = PrimaryKey(id)

    val name = varchar("name", length = 400)
    val executedAt = timestamp("executed_at")

    init {
        index(true, name)
    }
}

class MigrationEntity(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<MigrationEntity>(Migrations)

    var version by Migrations.id
    var name by Migrations.name
    var executedAt by Migrations.executedAt
}

fun Database.upgrade(
    migrations: List<Migration>,
    logger: KLogger,
) {
    fun checkVersions(migrations: List<Migration>) {
        val sorted = migrations.map { it.version }.sorted()
        if ((1..migrations.size).toList() != sorted) {
            throw IllegalStateException("List of migrations version is not consecutive: $sorted")
        }
    }

    fun createTableIfNotExists() {
        if (Migrations.exists()) {
            return
        }
        val tableNames = this.dialect.allTablesNames()
        when (tableNames.isEmpty()) {
            true -> {
                logger.info { "Empty database found, creating table for migrations" }
                create(Migrations)
            }
            false -> throw IllegalStateException(
                "Tried to run migrations against a non-empty database without a Migrations table. This is not supported.",
            )
        }
    }

    fun shouldRun(
        latestVersion: Int?,
        migration: Migration,
    ): Boolean {
        val run = latestVersion?.let { migration.version > it } ?: true
        if (!run) {
            logger.debug { "Skipping migration version ${migration.version}: ${migration.name}" }
        }
        return run
    }

    checkVersions(migrations)

    logger.info { "Running migrations on database ${this.url}" }

    val latestVersion =
        transaction {
            createTableIfNotExists()
            MigrationEntity.all().maxByOrNull { it.version }?.version?.value
        }

    logger.info { "Database version before migrations: $latestVersion" }

    migrations
        .sortedBy { it.version }
        .filter { shouldRun(latestVersion, it) }
        .forEach { migration ->
            transaction {
                // to avoid errors when multiple instance of the app are trying to migrate the database
                // we insert a migration record with ON CONFLICT DO NOTHING
                // and only if the record was actually inserted we apply the respective schema changes
                val shouldRun =
                    Migrations.insertIgnoreAndGetId {
                        it[id] = EntityID(migration.version, Migrations)
                        it[name] = migration.name
                        it[executedAt] = Clock.System.now()
                    } != null

                if (shouldRun) {
                    logger.info { "Running migration version ${migration.version}: ${migration.name}" }
                    migration.run()
                }
            }
        }

    logger.info { "Migrations finished successfully" }
}
