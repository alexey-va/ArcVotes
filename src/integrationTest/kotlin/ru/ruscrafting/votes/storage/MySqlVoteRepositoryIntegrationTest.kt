package ru.ruscrafting.votes.storage

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import ru.arc.network.NetworkPlayerName
import ru.arc.onetime.OneTimeUseAbandonResult
import ru.arc.onetime.OneTimeUseClaimRequest
import ru.arc.onetime.OneTimeUseClaimResult
import ru.arc.onetime.OneTimeUseFingerprint
import ru.arc.onetime.OneTimeUseIdentity
import ru.arc.sql.SqlConnectionConfig
import ru.arc.sql.SqlRuntime
import ru.arc.sql.SqlSslMode
import ru.arc.sql.onetime.MySqlOneTimeUseLedger
import ru.arc.sql.onetime.MySqlOneTimeUsePartition
import ru.arc.testing.containers.MySqlTestService
import ru.arc.testing.containers.MySqlTestSettings
import ru.ruscrafting.votes.config.MonitoringSource
import ru.ruscrafting.votes.domain.AuthenticatedVote
import ru.ruscrafting.votes.domain.RewardProvider
import ru.ruscrafting.votes.domain.VoteRecordResult
import ru.ruscrafting.votes.domain.VoteRewardBundle
import ru.ruscrafting.votes.domain.VoteRewardComponent
import java.math.BigDecimal
import java.nio.ByteBuffer
import java.sql.Connection
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

class MySqlVoteRepositoryIntegrationTest : FreeSpec({
    var mysql: MySqlTestService? = null
    var runtime: SqlRuntime? = null
    lateinit var repository: MySqlVoteRepository

    beforeSpec {
        val mysqlService = MySqlTestService.start(MySqlTestSettings(database = "arc_votes_test"))
        mysql = mysqlService
        val sqlRuntime = SqlRuntime.create(
            SqlConnectionConfig(
                host = mysqlService.endpoint.host,
                port = mysqlService.endpoint.port,
                database = mysqlService.endpoint.database,
                username = mysqlService.endpoint.username,
                password = mysqlService.endpoint.password,
                sslMode = SqlSslMode.DISABLED,
                minimumIdle = 0,
                maximumPoolSize = 2,
            ),
            "arc-votes-test",
        )
        runtime = sqlRuntime
        repository = MySqlVoteRepository(sqlRuntime)
        repository.initialize().join()
    }

    afterSpec {
        runtime?.close()
        mysql?.close()
    }

    "callback retries are durable duplicates and identity conflicts fail closed" {
        val vote = AuthenticatedVote(
            MonitoringSource.HOTMC,
            "event:42",
            NetworkPlayerName.of("Steve"),
            Instant.parse("2026-08-30T12:00:00Z"),
        )
        val reward = VoteRewardBundle(
            listOf(
                VoteRewardComponent("standard", RewardProvider.VAULT, BigDecimal("1000.00")),
                VoteRewardComponent("premium", RewardProvider.REDIS_ECONOMY, BigDecimal("3.00"), "tokens"),
            ),
        )
        val inserted = repository.record(vote, reward).join()
            .shouldBeInstanceOf<VoteRecordResult.Inserted>()
        repository.record(vote, reward).join()
            .shouldBeInstanceOf<VoteRecordResult.Duplicate>().event.id shouldBe inserted.event.id

        val forged = vote.copy(playerName = NetworkPlayerName.of("Alex"))
        repository.record(forged, reward).join() shouldBe VoteRecordResult.IdentityConflict

        val pending = repository.findPending(NetworkPlayerName.of("sTeVe")).join()
        pending shouldHaveSize 1
        pending.single().reward shouldBe reward
        repository.findPendingForPlayers(
            setOf(NetworkPlayerName.of("Steve"), NetworkPlayerName.of("Alex")),
        ).join().getValue("steve") shouldHaveSize 1
        repository.findVotedSources(
            NetworkPlayerName.of("sTeVe"),
            Instant.parse("2026-08-29T21:00:00Z"),
            Instant.parse("2026-08-30T21:00:00Z"),
        ).join() shouldBe setOf(MonitoringSource.HOTMC)
        repository.findVotedSources(
            NetworkPlayerName.of("Steve"),
            Instant.parse("2026-08-30T21:00:00Z"),
            Instant.parse("2026-08-31T21:00:00Z"),
        ).join().shouldBeEmpty()
        repository.markGranted(inserted.event.id, UUID.randomUUID()).join() shouldBe true
        repository.findPending(NetworkPlayerName.of("Steve")).join().shouldBeEmpty()
    }

    "migration 4 repairs retained vote claims without changing committed or foreign rows" {
        val sqlRuntime = requireNotNull(runtime)
        val retained = OneTimeUseIdentity(UUID.randomUUID(), OneTimeUseFingerprint.sha256Fields("retained"))
        val committed = OneTimeUseIdentity(UUID.randomUUID(), OneTimeUseFingerprint.sha256Fields("committed"))
        val foreign = OneTimeUseIdentity(UUID.randomUUID(), OneTimeUseFingerprint.sha256Fields("foreign"))
        val claimantId = UUID.randomUUID()

        sqlRuntime.executor.write { connection ->
            connection.insertOneTimeUse("vote_reward", retained, claimantId, "spawn", "CLAIMED")
            connection.insertOneTimeUse("vote_reward", committed, claimantId, "survival", "COMMITTED")
            connection.insertOneTimeUse("another_purpose", foreign, claimantId, "parkour", "CLAIMED")
            connection.createStatement().use { statement ->
                statement.executeUpdate(VoteMigrations.NETWORK_WIDE_REWARD_CLAIMS.statements.single())
            }
        }.join() shouldBe 1

        val stored = sqlRuntime.executor.read { connection ->
            connection.prepareStatement(
                "SELECT `use_id`, `status`, `claim_scope` FROM `arc_one_time_uses` WHERE `use_id` IN (?, ?, ?)",
            ).use { statement ->
                statement.setBytes(1, retained.useId.bytes())
                statement.setBytes(2, committed.useId.bytes())
                statement.setBytes(3, foreign.useId.bytes())
                statement.executeQuery().use { rows ->
                    buildMap {
                        while (rows.next()) {
                            put(rows.getBytes("use_id").uuid(), rows.getString("status") to rows.getString("claim_scope"))
                        }
                    }
                }
            }
        }.join()
        stored.getValue(retained.useId) shouldBe ("CLAIMED" to null)
        stored.getValue(committed.useId) shouldBe ("COMMITTED" to "survival")
        stored.getValue(foreign.useId) shouldBe ("CLAIMED" to "parkour")

        val ledger = MySqlOneTimeUseLedger.attach(
            sqlRuntime,
            "arc-votes-migration-test",
            MySqlOneTimeUsePartition("vote_reward"),
        )
        try {
            val request = OneTimeUseClaimRequest(retained, retained.useId, claimantId)
            val acquired = ledger.claim(request).join().shouldBeInstanceOf<OneTimeUseClaimResult.Acquired>()
            acquired.claim.newlyCreated shouldBe false
            acquired.claim.scope shouldBe null
            ledger.abandon(acquired.claim).join() shouldBe OneTimeUseAbandonResult.RETAINED_FOR_RECOVERY
        } finally {
            ledger.close()
        }
    }
})

private fun Connection.insertOneTimeUse(
    purpose: String,
    identity: OneTimeUseIdentity,
    claimantId: UUID,
    scope: String,
    status: String,
) {
    prepareStatement(
        """
        INSERT INTO `arc_one_time_uses`
            (`purpose`, `use_id`, `fingerprint`, `claimant_id`, `claim_id`, `claim_scope`,
             `status`, `claimed_at`, `committed_at`)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent(),
    ).use { statement ->
        statement.setString(1, purpose)
        statement.setBytes(2, identity.useId.bytes())
        statement.setBytes(3, identity.fingerprint.bytes())
        statement.setBytes(4, claimantId.bytes())
        statement.setBytes(5, identity.useId.bytes())
        statement.setString(6, scope)
        statement.setString(7, status)
        statement.setTimestamp(8, Timestamp.from(Instant.parse("2026-08-30T12:00:00Z")))
        statement.setTimestamp(9, if (status == "COMMITTED") Timestamp.from(Instant.parse("2026-08-30T12:00:01Z")) else null)
        statement.executeUpdate()
    }
}

private fun UUID.bytes(): ByteArray = ByteBuffer.allocate(16)
    .putLong(mostSignificantBits)
    .putLong(leastSignificantBits)
    .array()

private fun ByteArray.uuid(): UUID {
    require(size == 16) { "Invalid UUID byte length" }
    val buffer = ByteBuffer.wrap(this)
    return UUID(buffer.long, buffer.long)
}
