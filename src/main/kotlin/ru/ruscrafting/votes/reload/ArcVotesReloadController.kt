package ru.ruscrafting.votes.reload

import ru.ruscrafting.votes.callback.ArcVoteHttpServer
import ru.ruscrafting.votes.callback.ArcVoteHttpState
import ru.ruscrafting.votes.callback.VoteIngressCounters
import ru.ruscrafting.votes.callback.VoteIngressService
import ru.ruscrafting.votes.config.ArcVotesSettings
import ru.ruscrafting.votes.config.restartRequiredFields
import ru.ruscrafting.votes.live.VoteLiveConfiguration
import ru.ruscrafting.votes.live.VoteLiveState
import ru.ruscrafting.votes.reward.VoteRewardDepositor
import ru.ruscrafting.votes.reward.VoteRewardService
import ru.ruscrafting.votes.status.VoteDailyStatusService
import ru.ruscrafting.votes.storage.VoteHistoryLookup
import ru.ruscrafting.votes.storage.VoteRepository
import ru.ruscrafting.votes.text.VoteLocale
import java.nio.file.Path
import java.time.Duration
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

/**
 * Builds an isolated, fully validated generation and publishes it in one write.
 * The stable listener, reward task scope, ingress counters and HTTP socket are
 * deliberately not recreated when only live settings change.
 */
class ArcVotesReloadController(
    private val dataRoot: Path,
    private val live: VoteLiveState,
    private val repository: VoteRepository?,
    private val history: VoteHistoryLookup?,
    private val rewardService: VoteRewardService?,
    private val rewardRuntimeFactory: VoteRewardRuntimeFactory,
    private val logger: Logger,
    private val ingressCounters: VoteIngressCounters = VoteIngressCounters(),
) : ArcVotesReloader, AutoCloseable {
    private val monitor = Any()
    private val reloadInProgress = AtomicBoolean(false)
    private var httpServer: ArcVoteHttpServer? = null
    private var closed = false

    /** Starts the initial listener after the first live generation is published. */
    fun startInitialHttp() = synchronized(monitor) {
        check(!closed) { "ArcVotes reload controller is closed" }
        check(httpServer == null) { "ArcVotes callback HTTP listener is already started" }
        val current = live.current()
        if (current.settings.http.enabled) {
            requireNotNull(current.ingress) { "Callback ingress is unavailable" }
            httpServer = createHttpServer(current.settings).also(ArcVoteHttpServer::start)
        }
    }

    override fun reload(): ArcVotesReloadResult {
        if (!reloadInProgress.compareAndSet(false, true)) return ArcVotesReloadResult.Busy
        return try {
            synchronized(monitor) {
                if (closed) ArcVotesReloadResult.Failed else applyFreshGeneration()
            }
        } catch (failure: Throwable) {
            logger.log(Level.WARNING, "ArcVotes rejected a hot-reload candidate; the previous generation remains active", failure)
            ArcVotesReloadResult.Failed
        } finally {
            reloadInProgress.set(false)
        }
    }

    fun isHttpReady(): Boolean = synchronized(monitor) { !closed && httpServer != null }

    override fun close() {
        val previous = synchronized(monitor) {
            if (closed) return
            closed = true
            httpServer.also { httpServer = null }
        }
        previous?.close()
    }

    private fun applyFreshGeneration(): ArcVotesReloadResult {
        val current = live.current()
        val settings = ArcVotesSettings.loadFresh(dataRoot)
        val locale = VoteLocale.fresh(
            dataRoot,
            defaultLocale = { settings.defaultLocale },
            useClientLocale = { settings.useClientLocale },
        ).also(VoteLocale::validate)
        val restartRequired = restartRequiredFields(current.settings, settings)
        if (restartRequired.isNotEmpty()) return ArcVotesReloadResult.RestartRequired(restartRequired)

        val rewardRuntime = rewardRuntimeFactory.create(settings)
        val dailyStatus = history?.let {
            VoteDailyStatusService(
                history = it,
                cacheTtl = Duration.ofSeconds(settings.status.cacheTtlSeconds),
                voteDayZone = settings.status.voteDayZone,
                maximumCacheEntries = settings.status.maximumCacheEntries,
            )
        }
        val ingress = repository?.let {
            VoteIngressService(
                settings = settings,
                repository = it,
                logger = logger,
                onDurableEvent = { event -> rewardService?.onDurableEvent(event) },
                counters = ingressCounters,
            )
        }
        val candidate = VoteLiveConfiguration(
            settings = settings,
            locale = locale,
            dailyStatus = dailyStatus,
            ingress = ingress,
            rewardDepositor = rewardRuntime.depositor,
            vaultReady = rewardRuntime.vaultReady,
            redisEconomyReady = rewardRuntime.redisEconomyReady,
        )

        var preparedHttp: ArcVoteHttpServer? = null
        try {
            if (httpServer == null && settings.http.enabled) {
                requireNotNull(ingress) { "Callback ingress is unavailable" }
                preparedHttp = createHttpServer(settings).also(ArcVoteHttpServer::start)
            }
            rewardService?.reconfigurePolling()
            live.publish(candidate)

            if (preparedHttp != null) {
                httpServer = preparedHttp
                preparedHttp = null
            } else if (!settings.http.enabled) {
                val previous = httpServer
                httpServer = null
                runCatching { previous?.close() }
                    .onFailure { logger.log(Level.WARNING, "Could not close the disabled ArcVotes HTTP listener", it) }
            }
            runCatching {
                logger.info(
                    "ArcVotes hot reload applied: http=${settings.http.enabled}, rewards=${settings.reward.enabled}, " +
                        "sources=${settings.enabledSources.size}",
                )
            }
            return ArcVotesReloadResult.Applied
        } finally {
            preparedHttp?.close()
        }
    }

    private fun createHttpServer(settings: ArcVotesSettings): ArcVoteHttpServer = ArcVoteHttpServer(
        initialSettings = settings.http,
        currentState = {
            val current = live.current()
            ArcVoteHttpState(current.settings.http, current.ingress)
        },
        logger = logger,
    )
}
