package ru.ruscrafting.votes.reward

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
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
import ru.arc.onetime.OneTimeUseScope
import ru.ruscrafting.votes.config.ArcVotesSettings
import ru.ruscrafting.votes.config.MonitoringSource
import ru.ruscrafting.votes.config.RewardComponentSettings
import ru.ruscrafting.votes.config.RewardSettings
import ru.ruscrafting.votes.live.VoteLiveConfiguration
import ru.ruscrafting.votes.live.VoteLiveState
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
        var nano = 0L
        val service = service(server, scheduler, repository, RecordingLedger(), VoteRewardDepositor { _, component ->
            deposited += component.key
            RewardDepositResult.APPLIED
        }, nanoTime = { nano })

        service.start()
        repository.polledNames.single().map { it.value } shouldContainExactly listOf("OnlineSteve")
        repeat(16) { scheduler.executeImmediate() }
        deposited shouldContainExactly listOf("standard", "premium")
        repository.granted shouldContainExactly listOf(event.id)

        nano = 5_000_000_000L
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

    "granted reward keeps the monitoring brand color in the player message" {
        val scheduler = TestTaskScheduler()
        val player = player("Steve")
        val server = mockk<Server>()
        every { server.onlinePlayers } returns mutableListOf(player)
        val event = voteEvent(source = MonitoringSource.HOTMC)
        val repository = RecordingRepository(pending = listOf(event), markGrantedResult = true)
        val messages = mutableListOf<Component>()
        every { player.sendMessage(any<Component>()) } answers { messages += firstArg<Component>() }
        val root = java.nio.file.Files.createTempDirectory("arcvotes-reward-message")
        val fileSettings = ArcVotesSettings.load(root) { null }
        val locale = VoteLocale(root, { fileSettings.defaultLocale }, { fileSettings.useClientLocale })
        val service = service(server, scheduler, repository, RecordingLedger(), VoteRewardDepositor { _, _ ->
            RewardDepositResult.APPLIED
        }, locale)

        service.deliverPending(player)
        repeat(16) { scheduler.executeImmediate() }

        val plain = PlainTextComponentSerializer.plainText()
        messages.flatMap(Component::descendantsAndSelf).any { component ->
            component.color()?.value() == 0xFF5F56 && plain.serialize(component).contains("HotMC")
        } shouldBe true
        val rendered = messages.joinToString("\n") { plain.serialize(it) }
        rendered.contains("+1000 💰 +3 ") shouldBe true
        rendered.contains("· +3") shouldBe false
    }

    "an in-flight event finishes with the generation captured before reload" {
        val scheduler = TestTaskScheduler()
        val player = player("Steve")
        val server = mockk<Server>()
        every { server.onlinePlayers } returns mutableListOf(player)
        val event = voteEvent()
        val repository = RecordingRepository(pending = listOf(event))
        val ledger = DelayedFirstClaimLedger()
        val oldDeposits = mutableListOf<String>()
        val newDeposits = mutableListOf<String>()
        val settings = testSettings()
        val locale = mockk<VoteLocale>(relaxed = true)
        val live = VoteLiveState(
            VoteLiveConfiguration(
                settings = settings,
                locale = locale,
                dailyStatus = null,
                ingress = null,
                rewardDepositor = VoteRewardDepositor { _, component ->
                    oldDeposits += component.key
                    RewardDepositResult.APPLIED
                },
                vaultReady = true,
                redisEconomyReady = true,
            ),
        )
        val service = VoteRewardService(
            server = server,
            tasks = LifecycleTaskScope(scheduler),
            repository = repository,
            ledger = ledger,
            live = live::current,
            logger = Logger.getAnonymousLogger().apply { level = Level.OFF },
            pollTasks = LifecycleTaskScope(scheduler),
        )

        service.deliverPending(player)
        scheduler.executeImmediate()
        ledger.claimed.size shouldBe 1
        live.publish(
            live.current().copy(
                rewardDepositor = VoteRewardDepositor { _, component ->
                    newDeposits += component.key
                    RewardDepositResult.APPLIED
                },
            ),
        )
        ledger.completeFirst()
        repeat(16) { scheduler.executeImmediate() }

        oldDeposits shouldContainExactly listOf("standard", "premium")
        newDeposits shouldBe emptyList()
        repository.granted shouldContainExactly listOf(event.id)
    }

    "a claim recovered after restart on the same backend is quarantined instead of repeated" {
        val scheduler = TestTaskScheduler()
        val player = player("Steve")
        val server = mockk<Server>()
        every { server.onlinePlayers } returns mutableListOf(player)
        val event = voteEvent()
        val repository = RecordingRepository(pending = listOf(event))
        val requests = mutableListOf<OneTimeUseClaimRequest>()
        val ledger = object : OneTimeUseLedger {
            override fun claim(request: OneTimeUseClaimRequest): CompletableFuture<OneTimeUseClaimResult> {
                requests += request
                val result = if (request.scope == OneTimeUseScope.parse("spawn")) {
                    OneTimeUseClaimResult.Acquired(OneTimeUseClaim.acquired(request, newlyCreated = false))
                } else {
                    OneTimeUseClaimResult.Busy
                }
                return CompletableFuture.completedFuture(result)
            }

            override fun abandon(claim: OneTimeUseClaim): CompletableFuture<OneTimeUseAbandonResult> =
                CompletableFuture.completedFuture(OneTimeUseAbandonResult.RETAINED_FOR_RECOVERY)

            override fun commit(claim: OneTimeUseClaim): CompletableFuture<OneTimeUseCommitResult> =
                CompletableFuture.failedFuture(AssertionError("recovered claim must not be committed"))

            override fun release(claim: OneTimeUseClaim): CompletableFuture<OneTimeUseReleaseResult> =
                CompletableFuture.failedFuture(AssertionError("recovered claim must not be released"))
        }
        val service = service(server, scheduler, repository, ledger, VoteRewardDepositor { _, _ ->
            throw AssertionError("recovered claim must not repeat the reward effect")
        })

        service.deliverPending(player)
        repeat(12) { scheduler.executeImmediate() }

        requests.single().scope shouldBe OneTimeUseScope.parse("spawn")
        repository.recoveries shouldContainExactly listOf(Triple(event.id, player.uniqueId, "claim_recovered"))
        repository.granted shouldBe emptyList()
    }
})

private fun service(
    server: Server,
    scheduler: TestTaskScheduler,
    repository: RecordingRepository,
    ledger: OneTimeUseLedger,
    depositor: VoteRewardDepositor,
    locale: VoteLocale = mockk(relaxed = true),
    nanoTime: () -> Long = System::nanoTime,
): VoteRewardService {
    val settings = testSettings()
    val live = VoteLiveState(
        VoteLiveConfiguration(
            settings = settings,
            locale = locale,
            dailyStatus = null,
            ingress = null,
            rewardDepositor = depositor,
            vaultReady = true,
            redisEconomyReady = true,
        ),
    )
    return VoteRewardService(
        server = server,
        tasks = LifecycleTaskScope(scheduler),
        repository = repository,
        ledger = ledger,
        live = live::current,
        logger = Logger.getAnonymousLogger().apply { level = Level.OFF },
        pollTasks = LifecycleTaskScope(scheduler),
        nanoTime = nanoTime,
    )
}

private fun testSettings(): ArcVotesSettings {
    val settings = mockk<ArcVotesSettings>()
    every { settings.serverId } returns "spawn"
    every { settings.reward } returns rewardSettings()
    val root = java.nio.file.Files.createTempDirectory("arcvotes-reward-settings")
    every { settings.presentations } returns ArcVotesSettings.load(root) { null }.presentations
    return settings
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

private fun voteEvent(
    playerName: String = "Steve",
    source: MonitoringSource = MonitoringSource.MINECRAFT_RATING,
): VoteEvent {
    val reward = VoteRewardBundle(
        listOf(
            VoteRewardComponent("standard", RewardProvider.VAULT, BigDecimal("1000.00")),
            VoteRewardComponent("premium", RewardProvider.REDIS_ECONOMY, BigDecimal("3.00"), "tokens"),
        ),
    )
    return VoteEvent(
        id = UUID.randomUUID(),
        vote = AuthenticatedVote(
            source,
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
    private val markGrantedResult: Boolean = false,
) : VoteRepository {
    val polledNames = mutableListOf<Set<NetworkPlayerName>>()
    val granted = mutableListOf<UUID>()
    val recoveries = mutableListOf<Triple<UUID, UUID?, String>>()
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
        return CompletableFuture.completedFuture(markGrantedResult)
    }

    override fun markRecovery(eventId: UUID, playerId: UUID?, failureCode: String): CompletableFuture<Boolean> {
        recoveries += Triple(eventId, playerId, failureCode)
        return CompletableFuture.completedFuture(true)
    }
}

private fun Component.descendantsAndSelf(): List<Component> =
    listOf(this) + children().flatMap(Component::descendantsAndSelf)

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

private class DelayedFirstClaimLedger : OneTimeUseLedger {
    val claimed = mutableListOf<OneTimeUseClaimRequest>()
    private val first = CompletableFuture<OneTimeUseClaimResult>()

    override fun claim(request: OneTimeUseClaimRequest): CompletableFuture<OneTimeUseClaimResult> {
        claimed += request
        if (claimed.size == 1) return first
        return CompletableFuture.completedFuture(
            OneTimeUseClaimResult.Acquired(OneTimeUseClaim.acquired(request, newlyCreated = true)),
        )
    }

    fun completeFirst() {
        val request = claimed.single()
        first.complete(OneTimeUseClaimResult.Acquired(OneTimeUseClaim.acquired(request, newlyCreated = true)))
    }

    override fun commit(claim: OneTimeUseClaim): CompletableFuture<OneTimeUseCommitResult> =
        CompletableFuture.completedFuture(OneTimeUseCommitResult.COMMITTED)

    override fun release(claim: OneTimeUseClaim): CompletableFuture<OneTimeUseReleaseResult> =
        CompletableFuture.completedFuture(OneTimeUseReleaseResult.RELEASED)

    override fun abandon(claim: OneTimeUseClaim): CompletableFuture<OneTimeUseAbandonResult> =
        CompletableFuture.completedFuture(OneTimeUseAbandonResult.RETAINED_FOR_RECOVERY)
}
