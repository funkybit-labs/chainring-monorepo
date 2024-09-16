package xyz.funkybit.apps.api.model

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator
import xyz.funkybit.core.model.db.TestnetChallengePNLType
import xyz.funkybit.core.model.db.TestnetChallengeRewardCategory
import xyz.funkybit.core.model.db.TestnetChallengeUserRewardType

@Serializable
data class SetNickname(val name: String)

@Serializable
data class SetAvatarUrl(val url: String)

@Serializable
data class Enroll(val inviteCode: String?)

@Serializable
data class Leaderboard(
    val type: TestnetChallengePNLType,
    val page: Int,
    val lastPage: Int,
    val entries: List<LeaderboardEntry>,
)

@Serializable
data class LeaderboardEntry(
    val label: String,
    val iconUrl: String?,
    val value: Double,
    val pnl: Double,
)

@Serializable
@OptIn(ExperimentalSerializationApi::class)
@JsonClassDiscriminator("type")
sealed class Card {
    @Serializable
    @SerialName("Enrolled")
    data object Enrolled : Card()

    @Serializable
    @SerialName("RecentPoints")
    data class RecentPoints(
        val points: Long,
        val pointType: TestnetChallengeUserRewardType,
        val category: TestnetChallengeRewardCategory?,
    ) : Card()

    @Serializable
    @SerialName("BitcoinConnect")
    data object BitcoinConnect : Card()

    @Serializable
    @SerialName("BitcoinWithdrawal")
    data object BitcoinWithdrawal : Card()

    @Serializable
    @SerialName("EvmWithdrawal")
    data object EvmWithdrawal : Card()
}
