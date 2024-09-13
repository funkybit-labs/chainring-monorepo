package xyz.funkybit.apps.api.model

import kotlinx.serialization.Serializable
import xyz.funkybit.core.model.db.TestnetChallengePNLType

@Serializable
data class SetNickname(val name: String)

@Serializable
data class SetAvatarUrl(val url: String)

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
enum class CardType {
    Enrolled,
    RecentPoints,
    BitcoinConnect,
    BitcoinWithdrawal,
    EvmWithdrawal,
}

@Serializable
data class Card(
    val type: CardType,
    val params: Map<String, String>,
)
