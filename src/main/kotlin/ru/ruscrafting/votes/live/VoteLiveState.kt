package ru.ruscrafting.votes.live

import ru.ruscrafting.votes.callback.VoteIngressService
import ru.ruscrafting.votes.config.ArcVotesSettings
import ru.ruscrafting.votes.reward.VoteRewardDepositor
import ru.ruscrafting.votes.status.VoteDailyStatusService
import ru.ruscrafting.votes.text.VoteLocale
import java.util.concurrent.atomic.AtomicReference

/** One immutable generation of every reloadable ArcVotes value. */
data class VoteLiveConfiguration(
    val settings: ArcVotesSettings,
    val locale: VoteLocale,
    val dailyStatus: VoteDailyStatusService?,
    val ingress: VoteIngressService?,
    val rewardDepositor: VoteRewardDepositor?,
    val vaultReady: Boolean,
    val redisEconomyReady: Boolean,
)

/** Publishes a validated generation in one write; readers never observe mixed settings. */
class VoteLiveState(initial: VoteLiveConfiguration) {
    private val current = AtomicReference(initial)

    fun current(): VoteLiveConfiguration = current.get()

    fun publish(candidate: VoteLiveConfiguration): VoteLiveConfiguration = current.getAndSet(candidate)
}
