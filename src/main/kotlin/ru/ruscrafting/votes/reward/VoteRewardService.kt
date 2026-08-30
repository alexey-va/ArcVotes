package ru.ruscrafting.votes.reward

import net.milkbowl.vault.economy.Economy
import org.bukkit.Server
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.whenCompleteSync
import ru.arc.observability.StructuredDebugLine
import ru.arc.onetime.OneTimeUseAbandonResult
import ru.arc.onetime.OneTimeUseClaim
import ru.arc.onetime.OneTimeUseClaimRequest
import ru.arc.onetime.OneTimeUseClaimResult
import ru.arc.onetime.OneTimeUseCommitResult
import ru.arc.onetime.OneTimeUseLedger
import ru.arc.onetime.OneTimeUseReleaseResult
import ru.arc.onetime.OneTimeUseScope
import ru.ruscrafting.votes.config.ArcVotesSettings
import ru.ruscrafting.votes.domain.RewardState
import ru.ruscrafting.votes.domain.VoteEvent
import ru.ruscrafting.votes.storage.VoteRepository
import ru.ruscrafting.votes.text.VoteLocale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level
import java.util.logging.Logger

class VoteRewardService(
    private val server: Server,
    private val tasks: LifecycleTaskScope,
    private val repository: VoteRepository,
    private val ledger: OneTimeUseLedger,
    private val economy: Economy,
    private val settings: ArcVotesSettings,
    private val locale: VoteLocale,
    private val logger: Logger,
) : Listener {
    private val activePlayers = ConcurrentHashMap.newKeySet<UUID>()
    private val scope = OneTimeUseScope.parse(settings.serverId)
    private val debug = StructuredDebugLine("ARCVOTES_REWARD")

    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        deliverPending(event.player)
    }

    fun onDurableEvent(event: VoteEvent) {
        if (event.rewardState != RewardState.PENDING) return
        tasks.runSync {
            server.onlinePlayers.firstOrNull { it.name.equals(event.vote.playerName.value, ignoreCase = true) }
                ?.let(::deliverPending)
        }
    }

    fun deliverPending(player: Player) {
        if (!settings.reward.enabled || !activePlayers.add(player.uniqueId)) return
        repository.findPending(ru.arc.network.NetworkPlayerName.of(player.name), MAXIMUM_PENDING_BATCH)
            .whenCompleteSync(tasks) { events, failure ->
                if (failure != null) {
                    logger.log(Level.WARNING, "Could not load pending vote rewards", failure)
                    finish(player.uniqueId)
                } else {
                    deliverNext(player, events.orEmpty(), 0)
                }
            }
    }

    private fun deliverNext(player: Player, events: List<VoteEvent>, index: Int) {
        if (index >= events.size || !player.isOnline) {
            finish(player.uniqueId)
            return
        }
        val event = events[index]
        val request = OneTimeUseClaimRequest(
            identity = event.oneTimeUseIdentity(),
            claimId = event.id,
            claimantId = player.uniqueId,
            scope = scope,
        )
        ledger.claim(request).whenCompleteSync(tasks) { result, failure ->
            if (failure != null) {
                logger.log(Level.WARNING, debug.line("source" to event.vote.source.configKey, "outcome" to "claim_unknown"), failure)
                deliverNext(player, events, index + 1)
                return@whenCompleteSync
            }
            when (result) {
                is OneTimeUseClaimResult.Acquired -> {
                    if (result.claim.newlyCreated) applyReward(player, event, result.claim, events, index)
                    else abandonForRecovery(player, event, result.claim, "claim_recovered", events, index)
                }
                OneTimeUseClaimResult.AlreadyConsumed -> markGranted(player, event, notify = false, events, index)
                OneTimeUseClaimResult.Busy -> deliverNext(player, events, index + 1)
                OneTimeUseClaimResult.IdentityConflict,
                OneTimeUseClaimResult.Missing,
                -> markRecovery(player, event, "claim_conflict", events, index)
                null -> markRecovery(player, event, "claim_missing_result", events, index)
            }
        }
    }

    private fun applyReward(
        player: Player,
        event: VoteEvent,
        claim: OneTimeUseClaim,
        events: List<VoteEvent>,
        index: Int,
    ) {
        if (!player.isOnline) {
            releaseAndContinue(player, event, claim, events, index)
            return
        }
        val amount = requireNotNull(event.rewardAmount)
        val response = try {
            economy.depositPlayer(player, amount.toDouble())
        } catch (failure: Throwable) {
            logger.log(Level.SEVERE, debug.line("source" to event.vote.source.configKey, "outcome" to "effect_unknown"), failure)
            abandonForRecovery(player, event, claim, "effect_unknown", events, index)
            return
        }
        if (!response.transactionSuccess()) {
            logger.warning(debug.line("source" to event.vote.source.configKey, "outcome" to "provider_rejected"))
            releaseAndContinue(player, event, claim, events, index)
            return
        }
        ledger.commit(claim).whenCompleteSync(tasks) { committed, failure ->
            if (failure != null || committed !in setOf(OneTimeUseCommitResult.COMMITTED, OneTimeUseCommitResult.ALREADY_COMMITTED)) {
                logger.log(
                    Level.SEVERE,
                    debug.line("source" to event.vote.source.configKey, "outcome" to "commit_unknown"),
                    failure,
                )
                markRecovery(player, event, "commit_unknown", events, index)
            } else {
                markGranted(player, event, notify = true, events, index)
            }
        }
    }

    private fun releaseAndContinue(
        player: Player,
        event: VoteEvent,
        claim: OneTimeUseClaim,
        events: List<VoteEvent>,
        index: Int,
    ) {
        ledger.release(claim).whenCompleteSync(tasks) { released, failure ->
            if (failure == null && released in setOf(OneTimeUseReleaseResult.RELEASED, OneTimeUseReleaseResult.ALREADY_RELEASED)) {
                deliverNext(player, events, index + 1)
            } else {
                markRecovery(player, event, "release_unknown", events, index)
            }
        }
    }

    private fun abandonForRecovery(
        player: Player,
        event: VoteEvent,
        claim: OneTimeUseClaim,
        failureCode: String,
        events: List<VoteEvent>,
        index: Int,
    ) {
        ledger.abandon(claim).whenCompleteSync(tasks) { abandoned, failure ->
            if (failure == null && abandoned == OneTimeUseAbandonResult.ALREADY_COMMITTED) {
                markGranted(player, event, notify = false, events, index)
            } else {
                markRecovery(player, event, failureCode, events, index)
            }
        }
    }

    private fun markGranted(
        player: Player,
        event: VoteEvent,
        notify: Boolean,
        events: List<VoteEvent>,
        index: Int,
    ) {
        repository.markGranted(event.id, player.uniqueId).whenCompleteSync(tasks) { updated, failure ->
            if (failure != null) {
                logger.log(Level.SEVERE, debug.line("source" to event.vote.source.configKey, "outcome" to "state_unknown"), failure)
            } else if (updated == true && notify && player.isOnline) {
                val presentation = settings.presentations.getValue(event.vote.source)
                player.sendMessage(
                    locale.render(
                        "reward.granted",
                        player,
                        mapOf(
                            "site" to locale.text(presentation.displayName),
                            "amount" to locale.text(requireNotNull(event.rewardAmount).stripTrailingZeros().toPlainString()),
                            "currency" to locale.text(settings.reward.currencyLabel),
                        ),
                    ),
                )
            }
            deliverNext(player, events, index + 1)
        }
    }

    private fun markRecovery(
        player: Player,
        event: VoteEvent,
        failureCode: String,
        events: List<VoteEvent>,
        index: Int,
    ) {
        repository.markRecovery(event.id, player.uniqueId, failureCode).whenCompleteSync(tasks) { _, failure ->
            if (failure != null) logger.log(Level.SEVERE, "Could not retain vote reward for recovery", failure)
            logger.warning(debug.line("source" to event.vote.source.configKey, "outcome" to "recovery", "code" to failureCode))
            deliverNext(player, events, index + 1)
        }
    }

    private fun finish(playerId: UUID) {
        activePlayers.remove(playerId)
    }

    private companion object {
        const val MAXIMUM_PENDING_BATCH = 64
    }
}
