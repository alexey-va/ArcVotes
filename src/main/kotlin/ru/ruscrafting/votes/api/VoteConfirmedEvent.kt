package ru.ruscrafting.votes.api

import org.bukkit.event.Event
import org.bukkit.event.HandlerList
import java.util.UUID

/**
 * Emitted after an authenticated vote has been durably accepted by ArcVotes.
 *
 * The player id is supplied only after an exact online-name lookup or from a
 * UUID previously persisted by that same lookup, so consumers do not have to
 * turn an untrusted callback nickname into an identity.
 */
class VoteConfirmedEvent(
    val eventId: String,
    val playerId: UUID,
) : Event(false) {
    init {
        require(eventId.matches(EVENT_ID)) { "Invalid vote event id" }
    }

    override fun getHandlers(): HandlerList = HANDLERS

    companion object {
        private val EVENT_ID = Regex("[a-f0-9-]{36}")
        private val HANDLERS = HandlerList()

        @JvmStatic
        fun getHandlerList(): HandlerList = HANDLERS
    }
}
