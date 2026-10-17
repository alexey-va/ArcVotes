package ru.ruscrafting.votes.reload

import ru.arc.core.BoundedAsyncExecutor
import ru.arc.core.BudgetedTaskQueue
import ru.ruscrafting.votes.callback.ArcVoteHttpServer
import ru.ruscrafting.votes.callback.ArcVoteHttpState
import ru.ruscrafting.votes.callback.VoteIngressCounters
import ru.ruscrafting.votes.callback.VoteIngressService
import ru.ruscrafting.votes.config.ArcVotesSettings
import ru.ruscrafting.votes.config.restartRequiredFields
import ru.ruscrafting.votes.domain.VoteEvent
import ru.ruscrafting.votes.live.VoteLiveConfiguration
import ru.ruscrafting.votes.live.VoteLiveState
import ru.ruscrafting.votes.reward.VoteRewardDepositor
import ru.ruscrafting.votes.reward.VoteRewardService
import ru.ruscrafting.votes.status.VoteDailyStatusService
import ru.ruscrafting.votes.storage.VoteHistoryLookup
import ru.ruscrafting.votes.storage.VoteRepository
import ru.ruscrafting.votes.text.VoteLocale
import ru.ruscrafting.votes.paper.VoteMenuConfiguration
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Level
import java.util.logging.Logger

data class VoteRewardRuntime(
    val depositor: VoteRewardDepositor?,
    val vaultReady: Boolean,
    val redisEconomyReady: Boolean,
)

fun interface VoteRewardRuntimeFactory {
    fun create(settings: ArcVotesSettings): VoteRewardRuntime
}

/** Worker preparation and socket ownership with a single native publication boundary. */
class ArcVotesReloadController(
    private val dataRoot: Path,
    private val live: VoteLiveState,
    private val repository: VoteRepository?,
    private val history: VoteHistoryLookup?,
    private val rewardService: VoteRewardService?,
    private val rewardRuntimeFactory: VoteRewardRuntimeFactory,
    private val logger: Logger,
    private val storage: BoundedAsyncExecutor,
    private val nativeWork: BudgetedTaskQueue,
    private val ingressCounters: VoteIngressCounters = VoteIngressCounters(),
    private val onDurableEvent: (VoteEvent) -> Unit = {},
    private val loadMenuCandidate: (() -> VoteMenuConfiguration)? = null,
    private val applyMenuCandidate: (VoteMenuConfiguration) -> Unit = {},
) : ArcVotesReloader, AutoCloseable {
    private val reloadInProgress = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val resources = ConcurrentLinkedDeque<ArcVoteHttpServer>()
    @Volatile private var httpServer: ArcVoteHttpServer? = null
    @Volatile private var pendingHttp: ArcVoteHttpServer? = null

    private class Prepared(
        val before: VoteLiveConfiguration,
        val settings: ArcVotesSettings,
        val locale: VoteLocale,
        val menu: VoteMenuConfiguration?,
        val restartRequired: List<String>,
    )

    /** Bind/start are worker operations; callers publish readiness only after this future succeeds. */
    fun startInitialHttp(): CompletableFuture<Unit> = storage.submit {
        check(!closed.get()) { "ArcVotes reload controller is closed" }
        check(httpServer == null) { "ArcVotes callback HTTP listener is already started" }
        val current = live.current()
        if (current.settings.http.enabled) {
            requireNotNull(current.ingress) { "Callback ingress is unavailable" }
            httpServer = openHttpServer(current.settings)
        }
    }

    override fun reload(): CompletableFuture<ArcVotesReloadResult> {
        if (!reloadInProgress.compareAndSet(false, true)) {
            return CompletableFuture.completedFuture(ArcVotesReloadResult.Busy)
        }
        val current = live.current()
        val attempt = storage.submit {
            check(!closed.get()) { "ArcVotes reload controller is closed" }
            val settings = ArcVotesSettings.loadFresh(dataRoot)
            val locale = VoteLocale.fresh(dataRoot, { settings.defaultLocale }, { settings.useClientLocale })
                .also(VoteLocale::validate)
            Prepared(current, settings, locale, loadMenuCandidate?.invoke(), restartRequiredFields(current.settings, settings))
        }.thenCompose { prepared ->
            if (prepared.restartRequired.isNotEmpty()) {
                CompletableFuture.completedFuture<ArcVotesReloadResult>(ArcVotesReloadResult.RestartRequired(prepared.restartRequired))
            } else {
                nativeWork.call({ !closed.get() }) { rewardRuntimeFactory.create(prepared.settings) }
                    .thenCompose { reward -> prepareGeneration(prepared, reward) }
                    .thenCompose { (candidate, preparedHttp) ->
                        nativeWork.call({ !closed.get() }) {
                            check(live.current() === prepared.before) { "Vote generation changed while reload was prepared" }
                            rewardService?.reconfigurePolling()
                            prepared.menu?.let(applyMenuCandidate)
                            live.publish(candidate)
                            val previous = if (!candidate.settings.http.enabled) httpServer.also { httpServer = null } else null
                            if (preparedHttp != null) { httpServer = preparedHttp; pendingHttp = null }
                            previous
                        }
                    }.thenCompose { previous ->
                        storage.submit {
                            runCatching { previous?.let(::closeHttpServer) }.onFailure {
                                logger.log(Level.WARNING, "Could not close the disabled ArcVotes HTTP listener", it)
                            }
                            logger.info("ArcVotes hot reload applied: http=${prepared.settings.http.enabled}, " +
                                "rewards=${prepared.settings.reward.enabled}, sources=${prepared.settings.enabledSources.size}")
                            ArcVotesReloadResult.Applied as ArcVotesReloadResult
                        }
                    }
            }
        }.handle { result, failure -> result to failure }.thenCompose { (result, failure) ->
            if (failure == null) CompletableFuture.completedFuture(result)
            else storage.submit {
                pendingHttp?.also { pendingHttp = null }?.let(::closeHttpServer)
                logger.log(Level.WARNING, "ArcVotes rejected a hot-reload candidate; the previous generation remains active", failure)
                ArcVotesReloadResult.Failed as ArcVotesReloadResult
            }
        }
        // The command renders only after this owning-thread completion; no sender crosses into storage.
        return attempt.thenCompose { result -> nativeWork.call({ !closed.get() }) { result } }
            .whenComplete { _, _ -> reloadInProgress.set(false) }
    }

    fun isHttpReady(): Boolean = !closed.get() && httpServer != null

    /** Native shutdown seals publication immediately and never waits for socket or executor shutdown. */
    override fun close() { closed.set(true) }

    /** The composition root invokes this in its reserved worker finalizer. */
    fun closePersistence() {
        closed.set(true)
        httpServer = null
        var failure: Throwable? = null
        while (true) {
            val resource = resources.pollFirst() ?: break
            try { resource.close() } catch (error: Throwable) {
                if (failure == null) failure = error else failure.addSuppressed(error)
            }
        }
        failure?.let { throw it }
    }

    private fun prepareGeneration(prepared: Prepared, reward: VoteRewardRuntime): CompletableFuture<Pair<VoteLiveConfiguration, ArcVoteHttpServer?>> =
        storage.submit {
            check(!closed.get()) { "ArcVotes reload controller is closed" }
            val settings = prepared.settings
            val dailyStatus = history?.let {
                VoteDailyStatusService(history = it, cacheTtl = Duration.ofSeconds(settings.status.cacheTtlSeconds),
                    maximumCacheEntries = settings.status.maximumCacheEntries)
            }
            val ingress = repository?.let {
                VoteIngressService(settings, it, logger,
                    onDurableEvent = { event -> rewardService?.onDurableEvent(event); onDurableEvent(event) },
                    counters = ingressCounters)
            }
            val candidate = VoteLiveConfiguration(settings, prepared.locale, dailyStatus, ingress, reward.depositor,
                reward.vaultReady, reward.redisEconomyReady)
            val preparedHttp = if (httpServer == null && settings.http.enabled) {
                requireNotNull(ingress) { "Callback ingress is unavailable" }
                openHttpServer(settings).also { pendingHttp = it }
            } else null
            candidate to preparedHttp
        }

    private fun openHttpServer(settings: ArcVotesSettings): ArcVoteHttpServer =
        ArcVoteHttpServer(settings.http, {
            val current = live.current()
            ArcVoteHttpState(current.settings.http, current.ingress)
        }, logger).also { server ->
            resources.addFirst(server)
            server.start()
        }

    private fun closeHttpServer(server: ArcVoteHttpServer) {
        try { server.close() } finally { resources.remove(server) }
    }
}
