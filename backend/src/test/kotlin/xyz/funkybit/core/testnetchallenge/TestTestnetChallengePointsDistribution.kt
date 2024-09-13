package xyz.funkybit.core.testnetchallenge

import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import xyz.funkybit.core.model.EvmAddress
import xyz.funkybit.core.model.db.TestnetChallengePNLEntity
import xyz.funkybit.core.model.db.TestnetChallengePNLTable
import xyz.funkybit.core.model.db.TestnetChallengePNLType
import xyz.funkybit.core.model.db.TestnetChallengeUserRewardType
import xyz.funkybit.core.model.db.UserEntity
import xyz.funkybit.core.model.db.UserId
import xyz.funkybit.core.model.db.WalletEntity
import xyz.funkybit.core.model.db.pointsBalances
import xyz.funkybit.testutils.TestWithDb
import java.math.BigDecimal

class TestTestnetChallengePointsDistribution : TestWithDb() {

    data class TestnetChallengeTestCase(
        var userId: UserId? = null,
        val givenPnl: List<Pair<TestnetChallengePNLType, Int>>,
        val expectedReward: List<Pair<TestnetChallengeUserRewardType, Int>>,
    )

    private var testData: List<TestnetChallengeTestCase> = listOf(
        TestnetChallengeTestCase(
            givenPnl = listOf(
                TestnetChallengePNLType.DailyPNL to 20,
                TestnetChallengePNLType.WeeklyPNL to 200,
                TestnetChallengePNLType.OverallPNL to 2000,
            ),
            // top 1
            expectedReward = listOf(
                TestnetChallengeUserRewardType.DailyReward to 12_500,
                TestnetChallengeUserRewardType.WeeklyReward to 50_000,
                TestnetChallengeUserRewardType.OverallReward to 500_000,
            ),
        ),
        TestnetChallengeTestCase(
            givenPnl = listOf(
                TestnetChallengePNLType.DailyPNL to 19,
                TestnetChallengePNLType.WeeklyPNL to 190,
                TestnetChallengePNLType.OverallPNL to 1900,
            ),
            // top 5% (2nd from 20)
            expectedReward = listOf(
                TestnetChallengeUserRewardType.DailyReward to 1250,
                TestnetChallengeUserRewardType.WeeklyReward to 5_000,
                TestnetChallengeUserRewardType.OverallReward to 25_000,
            ),
        ),
        TestnetChallengeTestCase(
            givenPnl = listOf(
                TestnetChallengePNLType.DailyPNL to 18,
                TestnetChallengePNLType.WeeklyPNL to 180,
                TestnetChallengePNLType.OverallPNL to 1800,
            ),
            // top 10%
            expectedReward = listOf(
                TestnetChallengeUserRewardType.DailyReward to 625,
                TestnetChallengeUserRewardType.WeeklyReward to 2_500,
                TestnetChallengeUserRewardType.OverallReward to 12_500,
            ),
        ),
        TestnetChallengeTestCase(
            givenPnl = listOf(
                TestnetChallengePNLType.DailyPNL to 17,
                TestnetChallengePNLType.WeeklyPNL to 170,
                TestnetChallengePNLType.OverallPNL to 1700,
            ),
            // top 15% -> top 25%
            expectedReward = listOf(
                TestnetChallengeUserRewardType.DailyReward to 250,
                TestnetChallengeUserRewardType.WeeklyReward to 1_000,
                TestnetChallengeUserRewardType.OverallReward to 5_000,
            ),
        ),
        TestnetChallengeTestCase(
            givenPnl = listOf(
                TestnetChallengePNLType.DailyPNL to 16,
                TestnetChallengePNLType.WeeklyPNL to 160,
                TestnetChallengePNLType.OverallPNL to 1600,
            ),
            // top 20% -> top 25%
            expectedReward = listOf(
                TestnetChallengeUserRewardType.DailyReward to 250,
                TestnetChallengeUserRewardType.WeeklyReward to 1_000,
                TestnetChallengeUserRewardType.OverallReward to 5_000,
            ),
        ),
        TestnetChallengeTestCase(
            givenPnl = listOf(
                TestnetChallengePNLType.DailyPNL to 15,
                TestnetChallengePNLType.WeeklyPNL to 150,
                TestnetChallengePNLType.OverallPNL to 1500,
            ),
            // top 25%
            expectedReward = listOf(
                TestnetChallengeUserRewardType.DailyReward to 250,
                TestnetChallengeUserRewardType.WeeklyReward to 1_000,
                TestnetChallengeUserRewardType.OverallReward to 5_000,
            ),
        ),
        TestnetChallengeTestCase(
            givenPnl = listOf(
                TestnetChallengePNLType.DailyPNL to 14,
                TestnetChallengePNLType.WeeklyPNL to 140,
                TestnetChallengePNLType.OverallPNL to 1400,
            ),
            // top 30% -> top 50%
            expectedReward = listOf(
                TestnetChallengeUserRewardType.DailyReward to 125,
                TestnetChallengeUserRewardType.WeeklyReward to 500,
                TestnetChallengeUserRewardType.OverallReward to 2_500,
            ),
        ),
        TestnetChallengeTestCase(
            givenPnl = listOf(
                TestnetChallengePNLType.DailyPNL to 13,
                TestnetChallengePNLType.WeeklyPNL to 130,
                TestnetChallengePNLType.OverallPNL to 1300,
            ),
            // top 35% -> top 50%
            expectedReward = listOf(
                TestnetChallengeUserRewardType.DailyReward to 125,
                TestnetChallengeUserRewardType.WeeklyReward to 500,
                TestnetChallengeUserRewardType.OverallReward to 2_500,
            ),
        ),
        TestnetChallengeTestCase(
            givenPnl = listOf(
                TestnetChallengePNLType.DailyPNL to 12,
                TestnetChallengePNLType.WeeklyPNL to 120,
                TestnetChallengePNLType.OverallPNL to 1200,
            ),
            // top 40% -> top 50%
            expectedReward = listOf(
                TestnetChallengeUserRewardType.DailyReward to 125,
                TestnetChallengeUserRewardType.WeeklyReward to 500,
                TestnetChallengeUserRewardType.OverallReward to 2_500,
            ),
        ),
        TestnetChallengeTestCase(
            givenPnl = listOf(
                TestnetChallengePNLType.DailyPNL to 11,
                TestnetChallengePNLType.WeeklyPNL to 110,
                TestnetChallengePNLType.OverallPNL to 1100,
            ),
            // top 45% -> top 50%
            expectedReward = listOf(
                TestnetChallengeUserRewardType.DailyReward to 125,
                TestnetChallengeUserRewardType.WeeklyReward to 500,
                TestnetChallengeUserRewardType.OverallReward to 2_500,
            ),
        ),
        TestnetChallengeTestCase(
            givenPnl = listOf(
                TestnetChallengePNLType.DailyPNL to 10,
                TestnetChallengePNLType.WeeklyPNL to 100,
                TestnetChallengePNLType.OverallPNL to 1000,
            ),
            // top 50%
            expectedReward = listOf(
                TestnetChallengeUserRewardType.DailyReward to 125,
                TestnetChallengeUserRewardType.WeeklyReward to 500,
                TestnetChallengeUserRewardType.OverallReward to 2_500,
            ),
        ),
        TestnetChallengeTestCase(
            givenPnl = listOf(
                TestnetChallengePNLType.DailyPNL to 9,
                TestnetChallengePNLType.WeeklyPNL to 90,
                TestnetChallengePNLType.OverallPNL to 900,
            ),
            // bottom 45%
            expectedReward = listOf(
                TestnetChallengeUserRewardType.DailyReward to 0,
                TestnetChallengeUserRewardType.WeeklyReward to 0,
                TestnetChallengeUserRewardType.OverallReward to 0,
            ),
        ),
        TestnetChallengeTestCase(
            givenPnl = listOf(
                TestnetChallengePNLType.DailyPNL to 8,
                TestnetChallengePNLType.WeeklyPNL to 80,
                TestnetChallengePNLType.OverallPNL to 800,
            ),
            // bottom 40%
            expectedReward = listOf(
                TestnetChallengeUserRewardType.DailyReward to 0,
                TestnetChallengeUserRewardType.WeeklyReward to 0,
                TestnetChallengeUserRewardType.OverallReward to 0,
            ),
        ),
        TestnetChallengeTestCase(
            givenPnl = listOf(
                TestnetChallengePNLType.DailyPNL to 7,
                TestnetChallengePNLType.WeeklyPNL to 70,
                TestnetChallengePNLType.OverallPNL to 700,
            ),
            // bottom 35%
            expectedReward = listOf(
                TestnetChallengeUserRewardType.DailyReward to 0,
                TestnetChallengeUserRewardType.WeeklyReward to 0,
                TestnetChallengeUserRewardType.OverallReward to 0,
            ),
        ),
        TestnetChallengeTestCase(
            givenPnl = listOf(
                TestnetChallengePNLType.DailyPNL to 6,
                TestnetChallengePNLType.WeeklyPNL to 60,
                TestnetChallengePNLType.OverallPNL to 600,
            ),
            // bottom 30%
            expectedReward = listOf(
                TestnetChallengeUserRewardType.DailyReward to 0,
                TestnetChallengeUserRewardType.WeeklyReward to 0,
                TestnetChallengeUserRewardType.OverallReward to 0,
            ),
        ),
        TestnetChallengeTestCase(
            givenPnl = listOf(
                TestnetChallengePNLType.DailyPNL to 5,
                TestnetChallengePNLType.WeeklyPNL to 50,
                TestnetChallengePNLType.OverallPNL to 500,
            ),
            // bottom 25%
            expectedReward = listOf(
                TestnetChallengeUserRewardType.DailyReward to 0,
                TestnetChallengeUserRewardType.WeeklyReward to 0,
                TestnetChallengeUserRewardType.OverallReward to 0,
            ),
        ),
        TestnetChallengeTestCase(
            givenPnl = listOf(
                TestnetChallengePNLType.DailyPNL to 4,
                TestnetChallengePNLType.WeeklyPNL to 40,
                TestnetChallengePNLType.OverallPNL to 400,
            ),
            // bottom 20%
            expectedReward = listOf(
                TestnetChallengeUserRewardType.DailyReward to 0,
                TestnetChallengeUserRewardType.WeeklyReward to 0,
                TestnetChallengeUserRewardType.OverallReward to 0,
            ),
        ),
        TestnetChallengeTestCase(
            givenPnl = listOf(
                TestnetChallengePNLType.DailyPNL to 3,
                TestnetChallengePNLType.WeeklyPNL to 30,
                TestnetChallengePNLType.OverallPNL to 300,
            ),
            // bottom 15%
            expectedReward = listOf(
                TestnetChallengeUserRewardType.DailyReward to 0,
                TestnetChallengeUserRewardType.WeeklyReward to 0,
                TestnetChallengeUserRewardType.OverallReward to 0,
            ),
        ),
        TestnetChallengeTestCase(
            givenPnl = listOf(
                TestnetChallengePNLType.DailyPNL to 2,
                TestnetChallengePNLType.WeeklyPNL to 20,
                TestnetChallengePNLType.OverallPNL to 200,
            ),
            // bottom 10%
            expectedReward = listOf(
                TestnetChallengeUserRewardType.DailyReward to 0,
                TestnetChallengeUserRewardType.WeeklyReward to 0,
                TestnetChallengeUserRewardType.OverallReward to 0,
            ),
        ),
        TestnetChallengeTestCase(
            givenPnl = listOf(
                TestnetChallengePNLType.DailyPNL to 1,
                TestnetChallengePNLType.WeeklyPNL to 10,
                TestnetChallengePNLType.OverallPNL to 100,
            ),
            // bottom 1
            expectedReward = listOf(
                TestnetChallengeUserRewardType.DailyReward to 250,
                TestnetChallengeUserRewardType.WeeklyReward to 1_000,
                TestnetChallengeUserRewardType.OverallReward to 10_000,
            ),
        ),
    )

    @BeforeEach
    fun setup() {
        testData = testData.map {
            transaction {
                val wallet = WalletEntity.getOrCreateWithUser(EvmAddress.generate())
                val user = wallet.user

                // setup pnl balance per type
                TestnetChallengePNLEntity.initializeForUser(user)
                it.givenPnl.forEach { pnl ->
                    TestnetChallengePNLTable.update({
                        TestnetChallengePNLTable.userGuid.eq(user.guid) and TestnetChallengePNLTable.type.eq(pnl.first)
                    }) {
                        it[initialBalance] = BigDecimal(10000)
                        it[currentBalance] = BigDecimal(10000) + pnl.second.toBigDecimal()
                    }
                }

                it.copy(userId = user.guid.value)
            }
        }
    }

    @Test
    fun `test testnet challenge points distribution - daily`() {
        `test testnet challenge points distribution`(TestnetChallengeUserRewardType.DailyReward)
    }

    @Test
    fun `test testnet challenge points distribution - weekly`() {
        `test testnet challenge points distribution`(TestnetChallengeUserRewardType.WeeklyReward)
    }

    @Test
    fun `test testnet challenge points distribution - overall`() {
        `test testnet challenge points distribution`(TestnetChallengeUserRewardType.OverallReward)
    }

    private fun `test testnet challenge points distribution`(rewardType: TestnetChallengeUserRewardType) {
        val balancesBefore = lookupBalances(rewardType)

        transaction {
            TestnetChallengePNLEntity.distributePoints(
                when (rewardType) {
                    TestnetChallengeUserRewardType.DailyReward -> TestnetChallengePNLType.DailyPNL
                    TestnetChallengeUserRewardType.WeeklyReward -> TestnetChallengePNLType.WeeklyPNL
                    TestnetChallengeUserRewardType.OverallReward -> TestnetChallengePNLType.OverallPNL
                    TestnetChallengeUserRewardType.ReferralBonus -> throw IllegalArgumentException()
                },
            )
        }

        val balancesAfter = lookupBalances(rewardType)

        val actualChanges = actualBalanceChanges(balancesBefore, balancesAfter)
        val expectedChanges = expectedBalanceChanges(rewardType)

        assertEquals(expectedChanges, actualChanges)
    }

    private fun expectedBalanceChanges(rewardType: TestnetChallengeUserRewardType) =
        testData.map { it.expectedReward.first { it.first == rewardType }.second.toBigDecimal() }

    private fun actualBalanceChanges(
        balancesBefore: List<BigDecimal>,
        balancesAfter: List<BigDecimal>,
    ) = balancesBefore
        .zip(balancesAfter)
        .map { (before, after) -> (after - before).setScale(0) }

    private fun lookupBalances(rewardType: TestnetChallengeUserRewardType): List<BigDecimal> {
        return transaction {
            testData.map {
                UserEntity[it.userId!!].pointsBalances()
                    .filter {
                        it.key == rewardType
                    }
                    .values
                    .singleOrNull() ?: BigDecimal.ZERO
            }
        }
    }
}
