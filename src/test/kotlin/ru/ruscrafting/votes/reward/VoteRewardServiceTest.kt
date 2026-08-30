package ru.ruscrafting.votes.reward

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.Server
import org.bukkit.entity.Player
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.TestTaskScheduler
import ru.arc.network.NetworkPlayerName
import ru.arc.onetime.OneTimeUseAbandonResult
import ru.arc.onetime.OneTimeUseClaim
import ru.arc.onetime.OneTimeUseClaimRequest
import ru.arc.onetime.OneTimeUseClaimResult
import ru.arc.onetime.OneTimeUseCommitResult
import ru.arc.onetime.OneTimeUseLedger
import ru.arc.onetime.OneTimeUseReleaseResult
import ru.ruscrafting.votes.config.ArcVotesSettings
import ru.ruscrafting.votes.config.MonitoringSource
import ru.ruscrafting.votes.config.RewardComponentSettings
import ru.ruscrafting.votes.config.RewardSettings
import ru.ruscrafting.votes.domain.AuthenticatedVote
import ru.ruscrafting.votes.domain.RewardProvider
import ru.ruscrafting.votes.domain.RewardState
import ru.ruscrafting.votes.domain.VoteEvent
import ru.ruscrafting.votes.domain.VoteRecordResult
import ru.ruscrafting.votes.domain.VoteRewardBundle
import ru.ruscrafting.votes.domain.VoteRewardComponent
import ru.ruscrafting.votes.storage.VoteRepository
import ru.ruscrafting.votes.text.VoteLocale
import java.math.BigDecimal
import java.time.Instant
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.logging.Level
import java.util.logging.Logger

class VoteRewardServiceTest : FreeSpec({
    "periodic reconciliation batches online player names without a rejoin" {
        val scheduler = TestTaskScheduler()
        val player = player("OnlineSteve")
        val server = mockk<Server>()
        every { server.onlinePlayers } returns mutableListOf(player)
        val event = voteEvent("OnlineSteve")
        val repository = RecordingRepository(pending = listOf(event))
        val deposited = mutableListOf<String>()
        val service = service(server, scheduler, repository, RecordingLedger(), VoteRewardDepositor { _, component ->
            deposited += component.key
            RewardDepositResult.APPLIED
        })

        service.start()
        repository.polledNames.single().map { it.value } shouldContainExactly listOf("OnlineSteve")
        repeat(16) { scheduler.executeImmediate() }
        deposited shouldContainExactly listOf("standard", "premium")
        repository.granted shouldContainExactly listOf(event.id)

        scheduler.advanceMs(5_000)
        repository.polledNames.size shouldBe 2
        repository.singlePlayerLookups shouldBe 0
    }

    "standard and premium rewards use independent one-time claims" {
        val scheduler = TestTaskScheduler()
        val player = player("Steve")
        val server = mockk<Server>()
        every { server.onlinePlayers } returns mutableListOf(player)
        val event = voteEvent()
        val repository = RecordingRepository(pending = listOf(event))
        val ledger = RecordingLedger()
        val deposited = mutableListOf<String>()
        val service = service(server, scheduler, repository, ledger, VoteRewardDepositor { _, component ->
            deposited += component.key
            RewardDepositResult.APPLIED
        })

        service.deliverPending(player)
        repeat(16) { scheduler.executeImmediate() }

        deposited shouldContainExactly listOf("standard", "premium")
        ledger.claimed.map { it.identity.useId }.distinct().size shouldBe 2
        ledger.committed.size shouldBe 2
        repository.granted shouldContainExactly listOf(event.id)
    }

    "a committed standard component is not deposited again while premium retries" {
        val scheduler = TestTaskScheduler()
        val player = player("Steve")
        val server = mockk<Server>()
        every { server.onlinePlayers } returns mutableListOf(player)
        val event = voteEvent()
        val standard = requireNotNull(event.reward).component("standard")!!
        val standardUseId = event.oneTimeUseIdentity(standard).useId
        val repository = RecordingRepository(pending = listOf(event))
        val ledger = RecordingLedger(alreadyConsumed = setOf(standardUseId))
        val deposited = mutableListOf<String>()
        val service = service(server, scheduler, repository, ledger, VoteRewardDepositor { _, component ->
            deposited += component.key
            RewardDepositResult.APPLIED
        })

        service.deliverPending(player)
        repeat(16) { scheduler.executeImmediate() }

        deposited shouldContainExactly listOf("premium")
        ledger.committed.size shouldBe 1
        repository.granted shouldContainExactly listOf(event.id)
    }
})

private fun service(
    server: Server,
    scheduler: TestTaskScheduler,
    repository: RecordingRepository,
    ledger: RecordingLedger,
    depositor: VoteRewardDepositor,
): VoteRewardService {
    val settings = mockk<ArcVotesSettings>()
    every { settings.serverId } returns "spawn"
    every { settings.reward } returns rewardSettings()
    val locale = mockk<VoteLocale>(relaxed = true)
    return VoteRewardService(
        server = server,
        tasks = LifecycleTaskScope(scheduler),
        repository = repository,
        ledger = ledger,
        depositor = depositor,
        settings = settings,
        locale = locale,
        logger = Logger.getAnonymousLogger().apply { level = Level.OFF },
    )
}

private fun rewardSettings(): RewardSettings = RewardSettings(
    enabled = true,
    pollIntervalSeconds = 5,
    maximumPendingPerPlayer = 16,
    standard = RewardComponentSettings(true, "standard", RewardProvider.VAULT, BigDecimal("1000.00")),
    premium = RewardComponentSettings(true, "premium", RewardProvider.REDIS_ECONOMY, BigDecimal("3.00"), "tokens"),
)

private fun player(name: String): Player = mockk<Player>().also { player ->
    every { player.name } returns name
    every { player.uniqueId } returns UUID.nameUUIDFromBytes(name.toByteArray())
    every { player.isOnline } returns true
}

private fun voteEvent(playerName: String = "Steve"): VoteEvent {
    val reward = VoteRewardBundle(
        listOf(
            VoteRewardComponent("standard", RewardProvider.VAULT, BigDecimal("1000.00")),
            VoteRewardComponent("premium", RewardProvider.REDIS_ECONOMY, BigDecimal("3.00"), "tokens"),
        ),
    )
    return VoteEvent(
        id = UUID.randomUUID(),
        vote = AuthenticatedVote(
            MonitoringSource.MINECRAFT_RATING,
            "vote:test",
            NetworkPlayerName.of(playerName),
            Instant.EPOCH,
        ),
        receivedAt = Instant.EPOCH,
        reward = reward,
        rewardState = RewardState.PENDING,
    )
}

private class RecordingRepository(
    private val pending: List<VoteEvent> = emptyList(),
) : VoteRepository {
    val polledNames = mutableListOf<Set<NetworkPlayerName>>()
    val granted = mutableListOf<UUID>()
    var singlePlayerLookups = 0

    override fun initialize(): CompletableFuture<Unit> = CompletableFuture.completedFuture(Unit)

    override fun record(vote: AuthenticatedVote, reward: VoteRewardBundle?): CompletableFuture<VoteRecordResult> =
        CompletableFuture.failedFuture(UnsupportedOperationException())

    override fun findPending(playerName: NetworkPlayerName, limit: Int): CompletableFuture<List<VoteEvent>> {
        singlePlayerLookups += 1
        return CompletableFuture.completedFuture(pending)
    }

    override fun findPendingForPlayers(
        playerNames: Set<NetworkPlayerName>,
        perPlayerLimit: Int,
    ): CompletableFuture<Map<String, List<VoteEvent>>> {
        polledNames += playerNames
        val requested = playerNames.mapTo(hashSetOf()) { it.value.lowercase(Locale.ROOT) }
        return CompletableFuture.completedFuture(
            pending
                .filter { it.vote.normalizedPlayerName in requested }
                .groupBy { it.vote.normalizedPlayerName },
        )
    }

    override fun markGranted(eventId: UUID, playerId: UUID): CompletableFuture<Boolean> {
        granted += eventId
        return CompletableFuture.completedFuture(false)
    }

    override fun markRecovery(eventId: UUID, playerId: UUID?, failureCode: String): CompletableFuture<Boolean> =
        CompletableFuture.completedFuture(true)
}

private class RecordingLedger(
    private val alreadyConsumed: Set<UUID> = emptySet(),
) : OneTimeUseLedger {
    val claimed = mutableListOf<OneTimeUseClaimRequest>()
    val committed = mutableListOf<OneTimeUseClaim>()

    override fun claim(request: OneTimeUseClaimRequest): CompletableFuture<OneTimeUseClaimResult> {
        claimed += request
        val result = if (request.identity.useId in alreadyConsumed) {
            OneTimeUseClaimResult.AlreadyConsumed
        } else {
            OneTimeUseClaimResult.Acquired(OneTimeUseClaim.acquired(request, newlyCreated = true))
        }
        return CompletableFuture.completedFuture(result)
    }

    override fun commit(claim: OneTimeUseClaim): CompletableFuture<OneTimeUseCommitResult> {
        committed += claim
        return CompletableFuture.completedFuture(OneTimeUseCommitResult.COMMITTED)
    }

    override fun release(claim: OneTimeUseClaim): CompletableFuture<OneTimeUseReleaseResult> =
        CompletableFuture.completedFuture(OneTimeUseReleaseResult.RELEASED)

    override fun abandon(claim: OneTimeUseClaim): CompletableFuture<OneTimeUseAbandonResult> =
        CompletableFuture.completedFuture(OneTimeUseAbandonResult.RETAINED_FOR_RECOVERY)
}
