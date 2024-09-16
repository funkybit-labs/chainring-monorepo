package xyz.funkybit.core.model.db

import de.fxlae.typeid.TypeId
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.dao.EntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import org.jetbrains.exposed.sql.lowerCase
import org.jetbrains.exposed.sql.selectAll
import xyz.funkybit.core.model.Address
import xyz.funkybit.core.model.SequencerAccountId
import xyz.funkybit.core.model.db.WalletTable.nullable
import xyz.funkybit.core.sequencer.toSequencerId
import xyz.funkybit.core.utils.TestnetChallengeUtils

@Serializable
@JvmInline
value class UserId(override val value: String) : EntityId {
    companion object {
        fun generate(): UserId = UserId(TypeId.generate("user").toString())
    }

    override fun toString(): String = value
}

@Serializable
enum class TestnetChallengeStatus {
    Unenrolled,
    PendingAirdrop,
    PendingDeposit,
    PendingDepositConfirmation,
    Enrolled,
    Disqualified,
}

object UserTable : GUIDTable<UserId>("user", ::UserId) {
    val createdAt = timestamp("created_at")
    val createdBy = varchar("created_by", 10485760)
    val updatedAt = timestamp("updated_at").nullable()
    val updatedBy = varchar("updated_by", 10485760).nullable()
    val sequencerId = long("sequencer_id").uniqueIndex()

    // is admin
    val nickName = varchar("nick_name", 10485760).uniqueIndex().nullable()
    val avatarUrl = varchar("avatar_url", 10485760).nullable()
    val testnetChallengeStatus = customEnumeration(
        "testnet_challenge_status",
        "TestnetChallengeStatus",
        { value -> TestnetChallengeStatus.valueOf(value as String) },
        { PGEnum("TestnetChallengeStatus", it) },
    ).index()
    val inviteCode = varchar("invite_code", 10485760).uniqueIndex()
    val invitedBy = reference("invited_by", UserTable).index().nullable()
    val testnetAirdropTxHash = varchar("testnet_airdrop_tx_hash", 10485760).nullable()
}

class UserEntity(guid: EntityID<UserId>) : GUIDEntity<UserId>(guid) {

    companion object : EntityClass<UserId, UserEntity>(UserTable) {
        fun create(createdBy: Address): UserEntity {
            val userId = UserId.generate()
            return UserEntity.new(userId) {
                this.createdAt = Clock.System.now()
                this.createdBy = createdBy.canonicalize().toString()
                this.sequencerId = userId.toSequencerId()
                this.inviteCode = TestnetChallengeUtils.inviteCode()
                this.testnetChallengeStatus = TestnetChallengeStatus.Unenrolled
            }
        }

        fun getBySequencerIds(sequencerIds: Set<SequencerAccountId>): List<UserEntity> {
            return UserEntity.find {
                UserTable.sequencerId.inList(sequencerIds.map { it.value })
            }.toList()
        }

        fun findWithTestnetChallengeStatus(status: TestnetChallengeStatus): List<UserEntity> {
            return UserEntity.find {
                UserTable.testnetChallengeStatus eq status
            }.toList()
        }

        fun getWithWalletsBySequencerAccountIds(sequencerAccountIds: Set<SequencerAccountId>): List<Pair<UserEntity, List<WalletEntity>>> {
            val wallets = mutableMapOf<UserId, MutableList<WalletEntity>>()
            val users = UserTable
                .join(WalletTable, JoinType.LEFT, WalletTable.userGuid, UserTable.guid)
                .selectAll().where { UserTable.sequencerId.inList(sequencerAccountIds.map { it.value }) }
                .mapNotNull { resultRow ->
                    val walletsForUser = wallets[resultRow[UserTable.guid].value]
                    if (walletsForUser != null) {
                        walletsForUser.add(WalletEntity.wrapRow(resultRow))
                        null
                    } else {
                        UserEntity.wrapRow(resultRow).also {
                            wallets[it.guid.value] = listOfNotNull(
                                resultRow[WalletTable.guid.nullable()]?.let {
                                    WalletEntity.wrapRow(resultRow)
                                },
                            ).toMutableList()
                        }
                    }
                }
                .toList()

            return users.map { Pair(it, wallets[it.guid.value] ?: emptyList()) }
        }

        fun findByNickname(name: String): UserEntity? {
            return UserEntity.find {
                UserTable.nickName eq name
            }.singleOrNull()
        }

        fun findByInviteCode(inviteCode: String): UserEntity? {
            return UserEntity.find {
                UserTable.inviteCode.lowerCase() eq inviteCode.lowercase()
            }.singleOrNull()
        }
    }

    var sequencerId by UserTable.sequencerId.transform(
        toReal = { SequencerAccountId(it) },
        toColumn = { it.value },
    )

    var createdAt by UserTable.createdAt
    var createdBy by UserTable.createdBy

    var updatedAt by UserTable.updatedAt
    var updatedBy by UserTable.updatedBy

    var nickname by UserTable.nickName
    var avatarUrl by UserTable.avatarUrl
    var testnetChallengeStatus by UserTable.testnetChallengeStatus
    var inviteCode by UserTable.inviteCode
    var invitedBy by UserTable.invitedBy
    var testnetAirdropTxHash by UserTable.testnetAirdropTxHash
}
