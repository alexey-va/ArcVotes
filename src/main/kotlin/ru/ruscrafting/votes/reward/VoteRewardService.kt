package ru.ruscrafting.votes.reward

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.JoinConfiguration
import org.bukkit.Server
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.whenCompleteSync
import ru.arc.network.NetworkPlayerName
import ru.arc.observability.StructuredDebugLine
import ru.arc.onetime.OneTimeUseAbandonResult
import ru.arc.onetime.OneTimeUseClaim
import ru.arc.onetime.OneTimeUseClaimRequest
import ru.arc.onetime.OneTimeUseClaimResult
import ru.arc.onetime.OneTimeUseCommitResult
import ru.arc.onetime.OneTimeUseLedger
import ru.arc.onetime.OneTimeUseReleaseResult
import ru.arc.onetime.OneTimeUseScope
import ru.ruscrafting.votes.domain.RewardState
import ru.ruscrafting.votes.domain.VoteEvent
import ru.ruscrafting.votes.domain.VoteRewardComponent
import ru.ruscrafting.votes.live.VoteLiveConfiguration
import ru.ruscrafting.votes.storage.VoteRepository
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Owns online vote reward reconciliation for one Paper runtime.
 *
 * Bukkit player discovery and provider mutations stay on the primary thread;
 * repository and ledger calls complete asynchronously. One periodic query is
 * bounded to currently online names, and per-player execution is serialized.
 */
class VoteRewardService(
    private val server: Server,
    private val tasks: LifecycleTaskScope,
    private val repository: VoteRepository,
    private val ledger: OneTimeUseLedger,
    private val live: () -> VoteLiveConfiguration,
    private val logger: Logger,
    private val pollTasks: LifecycleTaskScope = LifecycleTaskScope(),
    private val nanoTime: () -> Long = System::nanoTime,
    private val onConfirmedEvent: (VoteEvent, UUID) -> Unit = { _, _ -> },
) : Listener, AutoCloseable {
    private val activePlayers = ConcurrentHashMap.newKeySet<UUID>()
    private val pollInFlight = AtomicBoolean(false)
    private val started = AtomicBoolean(false)
    private val nextPollAtNanos = AtomicLong(0L)
    private var nextPlayerOffset = 0
    private val scope = OneTimeUseScope.parse(live().settings.serverId)
    private val debug = StructuredDebugLine("ARCVOTES_REWARD")

    fun start() {
        check(started.compareAndSet(false, true)) { "Vote reward service is already started" }
        schedulePolling()
        pollTick()
    }

    /** Applies a new interval without replacing the timer or invalidating in-flight callbacks. */
    fun reconfigurePolling() {
        check(started.get()) { "Vote reward service is not started" }
        nextPollAtNanos.set(0L)
    }

    override fun close() {
        pollTasks.close()
    }

    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        deliverPending(event.player)
    }

    fun onDurableEvent(event: VoteEvent) {
        val runtime = live()
        runtime.dailyStatus?.observe(event)
        if (event.rewardState != RewardState.PENDING) return
        if (!runtime.settings.reward.enabled) return
        tasks.runSync {
            server.onlinePlayers.firstOrNull { it.name.equals(event.vote.playerName.value, ignoreCase = true) }
                ?.let(::deliverPending)
        }
    }

    internal fun pollOnlinePlayers() {
        val runtime = live()
        val reward = runtime.settings.reward
        if (!reward.enabled || !pollInFlight.compareAndSet(false, true)) return
        val players = server.onlinePlayers.toList()
        if (players.isEmpty()) {
            nextPlayerOffset = 0
            pollInFlight.set(false)
            return
        }
        val start = nextPlayerOffset % players.size
        val batchSize = minOf(reward.maximumOnlinePlayerBatch, players.size)
        val online = (0 until batchSize).associate { offset ->
            val player = players[(start + offset) % players.size]
            player.name.lowercase(Locale.ROOT) to player
        }
        nextPlayerOffset = (start + batchSize) % players.size
        val names = online.values.mapTo(linkedSetOf()) { NetworkPlayerName.of(it.name) }
        val lookup = try {
            repository.findPendingForPlayers(names, reward.maximumPendingPerPlayer)
        } catch (failure: Throwable) {
            pollInFlight.set(false)
            logger.log(Level.WARNING, "Could not start pending vote reward poll", failure)
            return
        }
        lookup
            .whenCompleteSync(tasks) { pending, failure ->
                pollInFlight.set(false)
                if (failure != null) {
                    logger.log(Level.WARNING, "Could not poll pending vote rewards", failure)
                    return@whenCompleteSync
                }
                pending.orEmpty().forEach { (normalizedName, events) ->
                    online[normalizedName]?.let { player -> deliverLoaded(player, events) }
                }
            }
    }

    fun deliverPending(player: Player) {
        val reward = live().settings.reward
        if (!reward.enabled || !activePlayers.add(player.uniqueId)) return
        val lookup = try {
            repository.findPending(NetworkPlayerName.of(player.name), reward.maximumPendingPerPlayer)
        } catch (failure: Throwable) {
            activePlayers.remove(player.uniqueId)
            logger.log(Level.WARNING, "Could not start loading pending vote rewards", failure)
            return
        }
        lookup
            .whenCompleteSync(tasks) { events, failure ->
                if (failure != null) {
                    logger.log(Level.WARNING, "Could not load pending vote rewards", failure)
                    finish(player.uniqueId)
                } else {
                    deliverNextEvent(player, events.orEmpty(), 0)
                }
            }
    }

    private fun deliverLoaded(player: Player, events: List<VoteEvent>) {
        if (events.isEmpty() || !activePlayers.add(player.uniqueId)) return
        deliverNextEvent(player, events, 0)
    }

    private fun deliverNextEvent(player: Player, events: List<VoteEvent>, eventIndex: Int) {
        if (eventIndex >= events.size || !player.isOnline) {
            finish(player.uniqueId)
            return
        }
        val runtime = live()
        if (!runtime.settings.reward.enabled) {
            finish(player.uniqueId)
            return
        }
        val event = events[eventIndex]
        runCatching { onConfirmedEvent(event, player.uniqueId) }
            .onFailure { failure -> logger.log(Level.WARNING, "Could not publish confirmed vote", failure) }
        runtime.dailyStatus?.observe(event)
        val components = requireNotNull(event.reward) { "Pending vote has no reward bundle" }.components
        deliverComponent(
            player, event, components, componentIndex = 0, anyApplied = false, events, eventIndex, runtime,
            onCompleted = { ArcProductTelemetryBridge.rewardClaimed(player.uniqueId, "vote:${event.id}") },
        )
    }

    private fun deliverComponent(
        player: Player,
        event: VoteEvent,
        components: List<VoteRewardComponent>,
        componentIndex: Int,
        anyApplied: Boolean,
        events: List<VoteEvent>,
        eventIndex: Int,
        runtime: VoteLiveConfiguration,
        onCompleted: (() -> Unit)? = null,
    ) {
        if (componentIndex >= components.size) {
            markGranted(player, event, notify = anyApplied, events, eventIndex, runtime, onCompleted)
            return
        }
        if (!player.isOnline) {
            deliverNextEvent(player, events, events.size)
            return
        }
        val component = components[componentIndex]
        val identity = event.oneTimeUseIdentity(component)
        val request = OneTimeUseClaimRequest(
            identity = identity,
            claimId = identity.useId,
            claimantId = player.uniqueId,
            scope = scope,
        )
        ledger.claim(request).whenCompleteSync(tasks) { result, failure ->
            if (failure != null) {
                logger.log(
                    Level.WARNING,
                    debug.line("source" to event.vote.source.configKey, "component" to component.key, "outcome" to "claim_unknown"),
                    failure,
                )
                deliverNextEvent(player, events, eventIndex + 1)
                return@whenCompleteSync
            }
            when (result) {
                is OneTimeUseClaimResult.Acquired -> {
                    if (result.claim.newlyCreated) {
                        applyComponent(
                            player,
                            event,
                            components,
                            componentIndex,
                            anyApplied,
                            result.claim,
                            events,
                            eventIndex,
                            runtime,
                            onCompleted,
                        )
                    } else {
                        abandonForRecovery(
                            player,
                            event,
                            component,
                            result.claim,
                            "claim_recovered",
                            events,
                            eventIndex,
                        )
                    }
                }
                OneTimeUseClaimResult.AlreadyConsumed ->
                    deliverComponent(
                        player,
                        event,
                        components,
                        componentIndex + 1,
                        anyApplied,
                        events,
                        eventIndex,
                        runtime,
                        onCompleted,
                    )
                OneTimeUseClaimResult.Busy -> deliverNextEvent(player, events, eventIndex + 1)
                OneTimeUseClaimResult.IdentityConflict,
                OneTimeUseClaimResult.Missing,
                -> markRecovery(player, event, component, "claim_conflict", events, eventIndex)
                null -> markRecovery(player, event, component, "claim_missing_result", events, eventIndex)
            }
        }
    }

    private fun applyComponent(
        player: Player,
        event: VoteEvent,
        components: List<VoteRewardComponent>,
        componentIndex: Int,
        anyApplied: Boolean,
        claim: OneTimeUseClaim,
        events: List<VoteEvent>,
        eventIndex: Int,
        runtime: VoteLiveConfiguration,
        onCompleted: (() -> Unit)? = null,
    ) {
        val component = components[componentIndex]
        if (!player.isOnline) {
            releaseAndContinue(player, event, component, claim, events, eventIndex)
            return
        }
        val auditToken =
            ArcAuditRewardBridge.mark(
                player.uniqueId,
                component,
                event.oneTimeUseIdentity(component).useId.toString(),
            )
        val result = try {
            requireNotNull(runtime.rewardDepositor) { "Vote reward provider is unavailable" }.deposit(player, component)
        } catch (failure: Throwable) {
            ArcAuditRewardBridge.cancel(player.uniqueId, auditToken)
            logger.log(
                Level.SEVERE,
                debug.line("source" to event.vote.source.configKey, "component" to component.key, "outcome" to "effect_unknown"),
                failure,
            )
            abandonForRecovery(player, event, component, claim, "effect_unknown", events, eventIndex)
            return
        }
        if (result != RewardDepositResult.APPLIED) {
            ArcAuditRewardBridge.cancel(player.uniqueId, auditToken)
            logger.warning(
                debug.line("source" to event.vote.source.configKey, "component" to component.key, "outcome" to "provider_rejected"),
            )
            releaseAndContinue(player, event, component, claim, events, eventIndex)
            return
        }
        ledger.commit(claim).whenCompleteSync(tasks) { committed, failure ->
            if (failure != null || committed !in setOf(OneTimeUseCommitResult.COMMITTED, OneTimeUseCommitResult.ALREADY_COMMITTED)) {
                logger.log(
                    Level.SEVERE,
                    debug.line("source" to event.vote.source.configKey, "component" to component.key, "outcome" to "commit_unknown"),
                    failure,
                )
                abandonForRecovery(player, event, component, claim, "commit_unknown", events, eventIndex)
            } else {
                deliverComponent(
                    player,
                    event,
                    components,
                    componentIndex + 1,
                    anyApplied = true,
                    events,
                    eventIndex,
                    runtime,
                    onCompleted,
                )
            }
        }
    }

    private fun releaseAndContinue(
        player: Player,
        event: VoteEvent,
        component: VoteRewardComponent,
        claim: OneTimeUseClaim,
        events: List<VoteEvent>,
        eventIndex: Int,
    ) {
        ledger.release(claim).whenCompleteSync(tasks) { released, failure ->
            if (failure == null && released in setOf(OneTimeUseReleaseResult.RELEASED, OneTimeUseReleaseResult.ALREADY_RELEASED)) {
                deliverNextEvent(player, events, eventIndex + 1)
            } else {
                markRecovery(player, event, component, "release_unknown", events, eventIndex)
            }
        }
    }

    private fun abandonForRecovery(
        player: Player,
        event: VoteEvent,
        component: VoteRewardComponent,
        claim: OneTimeUseClaim,
        failureCode: String,
        events: List<VoteEvent>,
        eventIndex: Int,
    ) {
        ledger.abandon(claim).whenCompleteSync(tasks) { abandoned, failure ->
            if (failure == null && abandoned == OneTimeUseAbandonResult.ALREADY_COMMITTED) {
                deliverNextEvent(player, events, eventIndex + 1)
            } else {
                markRecovery(player, event, component, failureCode, events, eventIndex)
            }
        }
    }

    private fun markGranted(
        player: Player,
        event: VoteEvent,
        notify: Boolean,
        events: List<VoteEvent>,
        eventIndex: Int,
        runtime: VoteLiveConfiguration,
        onCompleted: (() -> Unit)? = null,
    ) {
        repository.markGranted(event.id, player.uniqueId).whenCompleteSync(tasks) { updated, failure ->
            if (failure != null) {
                logger.log(Level.SEVERE, debug.line("source" to event.vote.source.configKey, "outcome" to "state_unknown"), failure)
            } else if (updated == true) {
                onCompleted?.invoke()
                if (notify && player.isOnline) {
                    runCatching { sendRewardMessage(player, event, runtime) }
                        .onFailure { displayFailure -> logger.log(Level.WARNING, "Vote reward was granted but its message could not be shown", displayFailure) }
                }
            }
            deliverNextEvent(player, events, eventIndex + 1)
        }
    }

    private fun sendRewardMessage(player: Player, event: VoteEvent, runtime: VoteLiveConfiguration) {
        val presentation = runtime.settings.presentations.getValue(event.vote.source)
        val rewardComponents = requireNotNull(event.reward).components.map { component ->
            runtime.locale.render(
                "reward.component-${component.key}",
                player,
                mapOf("amount" to runtime.locale.text(component.amount.stripTrailingZeros().toPlainString())),
            )
        }
        val rewards = Component.join(
            JoinConfiguration.separator(runtime.locale.render("reward.component-spacing", player)),
            rewardComponents,
        )
        runtime.locale.renderLines(
            "reward.granted",
            player,
            mapOf(
                "site" to runtime.locale.site(event.vote.source, player, presentation.displayName),
                "rewards" to rewards,
            ),
        ).forEach(player::sendMessage)
    }

    private fun markRecovery(
        player: Player,
        event: VoteEvent,
        component: VoteRewardComponent,
        failureCode: String,
        events: List<VoteEvent>,
        eventIndex: Int,
    ) {
        repository.markRecovery(event.id, player.uniqueId, failureCode).whenCompleteSync(tasks) { _, failure ->
            if (failure != null) logger.log(Level.SEVERE, "Could not retain vote reward for recovery", failure)
            logger.warning(
                debug.line(
                    "source" to event.vote.source.configKey,
                    "component" to component.key,
                    "outcome" to "recovery",
                    "code" to failureCode,
                ),
            )
            deliverNextEvent(player, events, eventIndex + 1)
        }
    }

    private fun finish(playerId: UUID) {
        activePlayers.remove(playerId)
    }

    private fun schedulePolling() {
        checkNotNull(pollTasks.runTimer(POLL_TICK_TICKS, POLL_TICK_TICKS, ::pollTick)) {
            "Vote reward poll scope is not active"
        }
    }

    private fun pollTick() {
        val now = nanoTime()
        while (true) {
            val current = nextPollAtNanos.get()
            if (current > now) return
            val interval = TimeUnit.SECONDS.toNanos(live().settings.reward.pollIntervalSeconds)
            if (nextPollAtNanos.compareAndSet(current, now + interval)) {
                pollOnlinePlayers()
                return
            }
        }
    }

    private companion object {
        const val TICKS_PER_SECOND = 20L
        const val POLL_TICK_TICKS = TICKS_PER_SECOND
    }
}

private object ArcAuditRewardBridge {
    private val markMethod = lazy {
        Class.forName("ru.arc.audit.ExternalEconomyAuditBridge").getMethod(
            "markExternalReward",
            UUID::class.java,
            String::class.java,
            String::class.java,
            Double::class.javaPrimitiveType,
            String::class.java,
            String::class.java,
        )
    }
    private val cancelMethod = lazy {
        Class.forName("ru.arc.audit.ExternalEconomyAuditBridge").getMethod("cancel", UUID::class.java, String::class.java)
    }

    fun mark(playerId: UUID, component: VoteRewardComponent, rewardId: String): String? = runCatching {
        markMethod.value.invoke(
            null,
            playerId,
            "voting",
            "vote_reward",
            component.amount.toDouble(),
            if (component.provider == ru.ruscrafting.votes.domain.RewardProvider.VAULT) "vault" else component.currencyId,
            rewardId,
        ) as String?
    }.getOrNull()

    fun cancel(playerId: UUID, token: String?) {
        if (token == null) return
        runCatching { cancelMethod.value.invoke(null, playerId, token) }
    }
}

private object ArcProductTelemetryBridge {
    private val recordMethod = lazy {
        Class.forName("ru.arc.metrics.ExternalProductTelemetryBridge").getMethod(
            "recordEvent", UUID::class.java, String::class.java, String::class.java, String::class.java,
        )
    }

    fun rewardClaimed(playerId: UUID, operationId: String): Boolean = runCatching {
        recordMethod.value.invoke(null, playerId, "arcvotes", "vote_reward_claimed", operationId) as Boolean
    }.getOrDefault(false)
}
