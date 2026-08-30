package ru.ruscrafting.votes.storage

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import ru.arc.network.NetworkPlayerName
import ru.arc.sql.SqlConnectionConfig
import ru.arc.sql.SqlRuntime
import ru.arc.sql.SqlSslMode
import ru.arc.testing.containers.MySqlTestService
import ru.arc.testing.containers.MySqlTestSettings
import ru.ruscrafting.votes.config.MonitoringSource
import ru.ruscrafting.votes.domain.AuthenticatedVote
import ru.ruscrafting.votes.domain.VoteRecordResult
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

class MySqlVoteRepositoryIntegrationTest : FreeSpec({
    lateinit var mysql: MySqlTestService
    lateinit var runtime: SqlRuntime
    lateinit var repository: MySqlVoteRepository

    beforeSpec {
        mysql = MySqlTestService.start(MySqlTestSettings(database = "arc_votes_test"))
        runtime = SqlRuntime.create(
            SqlConnectionConfig(
                host = mysql.endpoint.host,
                port = mysql.endpoint.port,
                database = mysql.endpoint.database,
                username = mysql.endpoint.username,
                password = mysql.endpoint.password,
                sslMode = SqlSslMode.DISABLED,
                minimumIdle = 0,
                maximumPoolSize = 2,
            ),
            "arc-votes-test",
        )
        repository = MySqlVoteRepository(runtime)
        repository.initialize().join()
    }

    afterSpec {
        runtime.close()
        mysql.close()
    }

    "callback retries are durable duplicates and identity conflicts fail closed" {
        val vote = AuthenticatedVote(
            MonitoringSource.HOTMC,
            "event:42",
            NetworkPlayerName.of("Steve"),
            Instant.parse("2026-08-30T12:00:00Z"),
        )
        val inserted = repository.record(vote, BigDecimal("100.00")).join()
            .shouldBeInstanceOf<VoteRecordResult.Inserted>()
        repository.record(vote, BigDecimal("100.00")).join()
            .shouldBeInstanceOf<VoteRecordResult.Duplicate>().event.id shouldBe inserted.event.id

        val forged = vote.copy(playerName = NetworkPlayerName.of("Alex"))
        repository.record(forged, BigDecimal("100.00")).join() shouldBe VoteRecordResult.IdentityConflict

        repository.findPending(NetworkPlayerName.of("sTeVe")).join() shouldHaveSize 1
        repository.markGranted(inserted.event.id, UUID.randomUUID()).join() shouldBe true
        repository.findPending(NetworkPlayerName.of("Steve")).join().shouldBeEmpty()
    }
})
