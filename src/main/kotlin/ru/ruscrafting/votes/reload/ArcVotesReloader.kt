package ru.ruscrafting.votes.reload

/** Applies one already-validated ArcVotes reload attempt. */
fun interface ArcVotesReloader {
    fun reload(): ArcVotesReloadResult
}

/** Safe, player-presentable outcomes of an ArcVotes reload attempt. */
sealed interface ArcVotesReloadResult {
    data object Applied : ArcVotesReloadResult

    data object Busy : ArcVotesReloadResult

    data class RestartRequired(val fields: List<String>) : ArcVotesReloadResult

    data object Failed : ArcVotesReloadResult
}
