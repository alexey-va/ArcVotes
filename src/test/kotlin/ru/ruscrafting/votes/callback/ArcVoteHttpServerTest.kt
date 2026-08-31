package ru.ruscrafting.votes.callback

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import ru.arc.network.NetworkPlayerName
import ru.ruscrafting.votes.config.ArcVotesSettings
import ru.ruscrafting.votes.domain.AuthenticatedVote
import ru.ruscrafting.votes.domain.VoteEvent
import ru.ruscrafting.votes.domain.VoteRecordResult
import ru.ruscrafting.votes.domain.VoteRewardBundle
import ru.ruscrafting.votes.storage.VoteRepository
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicReference
import java.util.logging.Logger

class ArcVoteHttpServerTest : StringSpec({
    "one listener observes a newly published enabled state without rebinding" {
        val settings = ArcVotesSettings.load(Files.createTempDirectory("arcvotes-http-state")) { null }
        val port = ServerSocket(0).use { it.localPort }
        val http = settings.http.copy(port = port, enabled = false)
        val ingress = VoteIngressService(settings, UnusedVoteRepository, Logger.getAnonymousLogger())
        val state = AtomicReference(ArcVoteHttpState(http, null))
        val server = ArcVoteHttpServer(http, state::get, Logger.getAnonymousLogger())
        server.start()
        try {
            callback(port).also { response ->
                response.statusCode() shouldBe 503
                response.body() shouldBe "service_disabled"
            }

            state.set(ArcVoteHttpState(http.copy(enabled = true), ingress))
            callback(port).also { response ->
                response.statusCode() shouldBe 503
                response.body() shouldBe "source_disabled"
            }
        } finally {
            server.close()
        }
    }
})

private fun callback(port: Int): HttpResponse<String> = HttpClient.newHttpClient().send(
    HttpRequest.newBuilder(URI("http://127.0.0.1:$port${VoteIngressService.HOTMC_PATH}"))
        .header("Content-Type", "application/x-www-form-urlencoded")
        .POST(HttpRequest.BodyPublishers.noBody())
        .build(),
    HttpResponse.BodyHandlers.ofString(),
)

private object UnusedVoteRepository : VoteRepository {
    override fun initialize(): CompletableFuture<Unit> = CompletableFuture.completedFuture(Unit)

    override fun record(vote: AuthenticatedVote, reward: VoteRewardBundle?): CompletableFuture<VoteRecordResult> =
        CompletableFuture.failedFuture(AssertionError("disabled source must not persist"))

    override fun findPending(playerName: NetworkPlayerName, limit: Int): CompletableFuture<List<VoteEvent>> =
        CompletableFuture.failedFuture(AssertionError("disabled source must not query rewards"))

    override fun findPendingForPlayers(
        playerNames: Set<NetworkPlayerName>,
        perPlayerLimit: Int,
    ): CompletableFuture<Map<String, List<VoteEvent>>> =
        CompletableFuture.failedFuture(AssertionError("disabled source must not query rewards"))

    override fun markGranted(eventId: UUID, playerId: UUID): CompletableFuture<Boolean> =
        CompletableFuture.failedFuture(AssertionError("disabled source must not mutate rewards"))

    override fun markRecovery(eventId: UUID, playerId: UUID?, failureCode: String): CompletableFuture<Boolean> =
        CompletableFuture.failedFuture(AssertionError("disabled source must not mutate rewards"))
}
