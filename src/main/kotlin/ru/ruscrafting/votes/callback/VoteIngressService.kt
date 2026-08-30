package ru.ruscrafting.votes.callback

import ru.arc.observability.StructuredDebugLine
import ru.ruscrafting.votes.config.ArcVotesSettings
import ru.ruscrafting.votes.config.MonitoringSource
import ru.ruscrafting.votes.domain.VoteEvent
import ru.ruscrafting.votes.domain.VoteRecordResult
import ru.ruscrafting.votes.storage.VoteRepository
import java.sql.SQLException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.atomic.AtomicLong
import java.util.logging.Level
import java.util.logging.Logger

data class VoteIngressSnapshot(
    val accepted: Long,
    val duplicates: Long,
    val rejected: Long,
    val upstreamFailures: Long,
)

class VoteIngressService(
    settings: ArcVotesSettings,
    private val repository: VoteRepository,
    private val logger: Logger,
    private val onDurableEvent: (VoteEvent) -> Unit = {},
) {
    private data class Route(val source: MonitoringSource, val adapter: VoteCallbackAdapter?)

    private val debug = StructuredDebugLine("ARCVOTES_CALLBACK")
    private val reward = settings.reward.bundle
    private val routes: Map<String, Route> = mapOf(
        MINECRAFT_RATING_PATH to Route(
            MonitoringSource.MINECRAFT_RATING,
            settings.minecraftRating.takeIf { it.enabled }?.let {
                SignedFormVoteAdapter(it, SignedFormVoteAdapter.MINECRAFT_RATING)
            },
        ),
        HOTMC_PATH to Route(
            MonitoringSource.HOTMC,
            settings.hotMc.takeIf { it.enabled }?.let {
                SignedFormVoteAdapter(it, SignedFormVoteAdapter.HOTMC)
            },
        ),
        MONITORING_MINECRAFT_PATH to Route(
            MonitoringSource.MONITORING_MINECRAFT,
            settings.monitoringMinecraft.takeIf { it.enabled }?.let(::MonitoringMinecraftAdapter),
        ),
        GAME_MONITORING_PATH to Route(
            MonitoringSource.GAME_MONITORING,
            settings.gameMonitoring.takeIf { it.enabled }?.let {
                GameMonitoringAdapter(it, HttpGameMonitoringVoteLookup())
            },
        ),
    )
    private val accepted = AtomicLong()
    private val duplicates = AtomicLong()
    private val rejected = AtomicLong()
    private val upstreamFailures = AtomicLong()

    fun handle(request: CallbackRequest): CompletableFuture<CallbackResponse> {
        val route = routes[request.path] ?: return completed(CallbackResponse.error(404, "not_found"))
        val adapter = route.adapter ?: return completed(CallbackResponse.error(503, "source_disabled"))
        val authentication = try {
            adapter.authenticate(request)
        } catch (failure: Exception) {
            CompletableFuture.failedFuture(failure)
        }
        return authentication.thenCompose { result ->
            when (result) {
                is CallbackAuthenticationResult.Accepted -> repository.record(result.vote, reward).thenApply { recorded ->
                    when (recorded) {
                        is VoteRecordResult.Inserted -> {
                            accepted.incrementAndGet()
                            safelyNotify(recorded.event)
                            logger.info(debug.line("source" to route.source.configKey, "outcome" to "accepted"))
                            adapter.successResponse
                        }
                        is VoteRecordResult.Duplicate -> {
                            duplicates.incrementAndGet()
                            safelyNotify(recorded.event)
                            logger.info(debug.line("source" to route.source.configKey, "outcome" to "duplicate"))
                            adapter.successResponse
                        }
                        VoteRecordResult.IdentityConflict -> {
                            rejected.incrementAndGet()
                            logger.warning(debug.line("source" to route.source.configKey, "outcome" to "identity_conflict"))
                            CallbackResponse.error(409, "identity_conflict")
                        }
                    }
                }
                CallbackAuthenticationResult.TestAcknowledged,
                CallbackAuthenticationResult.Ignored,
                -> completed(adapter.successResponse)
            }
        }.handle { response, failure ->
            if (failure == null) response else failureResponse(route.source, unwrap(failure))
        }
    }

    fun snapshot(): VoteIngressSnapshot = VoteIngressSnapshot(
        accepted = accepted.get(),
        duplicates = duplicates.get(),
        rejected = rejected.get(),
        upstreamFailures = upstreamFailures.get(),
    )

    private fun safelyNotify(event: VoteEvent) {
        runCatching { onDurableEvent(event) }
            .onFailure { failure -> logger.log(Level.WARNING, "Could not schedule durable vote delivery", failure) }
    }

    private fun failureResponse(source: MonitoringSource, failure: Throwable): CallbackResponse = when (failure) {
        is CallbackRejected -> {
            rejected.incrementAndGet()
            logger.warning(debug.line("source" to source.configKey, "outcome" to "rejected", "code" to failure.safeCode))
            CallbackResponse.error(failure.status, failure.safeCode)
        }
        is CallbackUpstreamFailure -> {
            upstreamFailures.incrementAndGet()
            logger.warning(debug.line("source" to source.configKey, "outcome" to "upstream_failure", "code" to failure.safeCode))
            CallbackResponse.error(503, "upstream_unavailable")
        }
        is java.io.IOException -> {
            upstreamFailures.incrementAndGet()
            logger.warning(debug.line("source" to source.configKey, "outcome" to "upstream_failure", "code" to "transport"))
            CallbackResponse.error(503, "upstream_unavailable")
        }
        is SQLException -> {
            logger.warning(debug.line("source" to source.configKey, "outcome" to "persistence_unavailable"))
            CallbackResponse.error(503, "persistence_unavailable")
        }
        else -> {
            logger.log(Level.SEVERE, debug.line("source" to source.configKey, "outcome" to "internal_failure"), failure)
            CallbackResponse.error(500, "internal_error")
        }
    }

    private fun unwrap(failure: Throwable): Throwable {
        var current = failure
        while ((current is CompletionException || current is java.util.concurrent.ExecutionException) && current.cause != null) {
            current = current.cause!!
        }
        return current
    }

    companion object {
        const val MINECRAFT_RATING_PATH = "/callbacks/minecraft-rating"
        const val HOTMC_PATH = "/callbacks/hotmc"
        const val MONITORING_MINECRAFT_PATH = "/callbacks/monitoring-minecraft"
        const val GAME_MONITORING_PATH = "/callbacks/gamemonitoring"
        val KNOWN_PATHS = setOf(MINECRAFT_RATING_PATH, HOTMC_PATH, MONITORING_MINECRAFT_PATH, GAME_MONITORING_PATH)
    }
}
