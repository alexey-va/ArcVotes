package ru.ruscrafting.votes.storage

import ru.arc.network.NetworkPlayerName
import ru.arc.sql.MySqlMigrator
import ru.arc.sql.SqlRuntime
import ru.ruscrafting.votes.config.MonitoringSource
import ru.ruscrafting.votes.domain.AuthenticatedVote
import ru.ruscrafting.votes.domain.RewardState
import ru.ruscrafting.votes.domain.VoteEvent
import ru.ruscrafting.votes.domain.VoteRecordResult
import java.math.BigDecimal
import java.nio.ByteBuffer
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Timestamp
import java.sql.Types
import java.time.Clock
import java.time.Instant
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CompletableFuture

interface VoteRepository {
    fun initialize(): CompletableFuture<Unit>
    fun record(vote: AuthenticatedVote, rewardAmount: BigDecimal?): CompletableFuture<VoteRecordResult>
    fun findPending(playerName: NetworkPlayerName, limit: Int = 16): CompletableFuture<List<VoteEvent>>
    fun markGranted(eventId: UUID, playerId: UUID): CompletableFuture<Boolean>
    fun markRecovery(eventId: UUID, playerId: UUID?, failureCode: String): CompletableFuture<Boolean>
}

class MySqlVoteRepository(
    private val runtime: SqlRuntime,
    private val clock: Clock = Clock.systemUTC(),
) : VoteRepository {
    override fun initialize(): CompletableFuture<Unit> = runtime.executor
        .submit { MySqlMigrator(runtime.dataSource, MIGRATION_NAMESPACE).migrate(VoteMigrations.ALL) }
        .thenApply { Unit }

    override fun record(vote: AuthenticatedVote, rewardAmount: BigDecimal?): CompletableFuture<VoteRecordResult> =
        runtime.executor.transaction { connection ->
            val event = VoteEvent(
                id = UUID.randomUUID(),
                vote = vote,
                receivedAt = clock.instant(),
                rewardAmount = rewardAmount,
                rewardState = if (rewardAmount == null) RewardState.NONE else RewardState.PENDING,
            )
            val inserted = try {
                connection.prepareStatement(
                    """
                    INSERT INTO `arc_votes_events`
                        (`event_uuid`, `source`, `external_id`, `player_name`, `player_name_normalized`,
                         `occurred_at`, `received_at`, `reward_amount`, `reward_state`)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent(),
                ).use { statement ->
                    statement.setBytes(1, event.id.bytes())
                    statement.setString(2, vote.source.configKey)
                    statement.setString(3, vote.externalId)
                    statement.setString(4, vote.playerName.value)
                    statement.setString(5, vote.normalizedPlayerName)
                    statement.setTimestamp(6, Timestamp.from(vote.occurredAt))
                    statement.setTimestamp(7, Timestamp.from(event.receivedAt))
                    if (rewardAmount == null) statement.setNull(8, Types.DECIMAL) else statement.setBigDecimal(8, rewardAmount)
                    statement.setString(9, event.rewardState.name)
                    statement.executeUpdate()
                }
                true
            } catch (failure: SQLException) {
                if (failure.errorCode != MYSQL_DUPLICATE_KEY || failure.sqlState != SQL_STATE_INTEGRITY_CONSTRAINT) {
                    throw failure
                }
                false
            }
            if (inserted) VoteRecordResult.Inserted(event)
            else {
                val existing = loadByExternalId(connection, vote.source, vote.externalId)
                    ?: error("Vote duplicate disappeared inside transaction")
                if (existing.vote.normalizedPlayerName == vote.normalizedPlayerName) {
                    VoteRecordResult.Duplicate(existing)
                } else {
                    VoteRecordResult.IdentityConflict
                }
            }
        }

    override fun findPending(playerName: NetworkPlayerName, limit: Int): CompletableFuture<List<VoteEvent>> {
        require(limit in 1..64) { "Pending vote limit must be between 1 and 64" }
        val normalized = playerName.value.lowercase(Locale.ROOT)
        return runtime.executor.read { connection ->
            connection.prepareStatement(
                """
                SELECT `event_uuid`, `source`, `external_id`, `player_name`, `occurred_at`, `received_at`,
                       `reward_amount`, `reward_state`, `player_uuid`
                FROM `arc_votes_events`
                WHERE `player_name_normalized` = ? AND `reward_state` = 'PENDING'
                ORDER BY `received_at` ASC
                LIMIT ?
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, normalized)
                statement.setInt(2, limit)
                statement.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.event()) } }
            }
        }
    }

    override fun markGranted(eventId: UUID, playerId: UUID): CompletableFuture<Boolean> = runtime.executor.write { connection ->
        connection.prepareStatement(
            """
            UPDATE `arc_votes_events`
            SET `reward_state` = 'GRANTED', `player_uuid` = ?, `rewarded_at` = ?, `failure_code` = NULL
            WHERE `event_uuid` = ? AND `reward_state` = 'PENDING'
            """.trimIndent(),
        ).use { statement ->
            statement.setBytes(1, playerId.bytes())
            statement.setTimestamp(2, Timestamp.from(clock.instant()))
            statement.setBytes(3, eventId.bytes())
            statement.executeUpdate() == 1
        }
    }

    override fun markRecovery(eventId: UUID, playerId: UUID?, failureCode: String): CompletableFuture<Boolean> {
        require(failureCode.matches(Regex("[a-z0-9_.-]{1,64}"))) { "Recovery failure code is unsafe" }
        return runtime.executor.write { connection ->
            connection.prepareStatement(
                """
                UPDATE `arc_votes_events`
                SET `reward_state` = 'RECOVERY', `player_uuid` = ?, `failure_code` = ?
                WHERE `event_uuid` = ? AND `reward_state` = 'PENDING'
                """.trimIndent(),
            ).use { statement ->
                if (playerId == null) statement.setNull(1, Types.BINARY) else statement.setBytes(1, playerId.bytes())
                statement.setString(2, failureCode)
                statement.setBytes(3, eventId.bytes())
                statement.executeUpdate() == 1
            }
        }
    }

    private fun loadByExternalId(
        connection: Connection,
        source: MonitoringSource,
        externalId: String,
    ): VoteEvent? = connection.prepareStatement(
        """
        SELECT `event_uuid`, `source`, `external_id`, `player_name`, `occurred_at`, `received_at`,
               `reward_amount`, `reward_state`, `player_uuid`
        FROM `arc_votes_events`
        WHERE `source` = ? AND `external_id` = ?
        """.trimIndent(),
    ).use { statement ->
        statement.setString(1, source.configKey)
        statement.setString(2, externalId)
        statement.executeQuery().use { rows -> if (rows.next()) rows.event() else null }
    }

    private fun ResultSet.event(): VoteEvent {
        val sourceKey = getString("source")
        val source = MonitoringSource.entries.singleOrNull { it.configKey == sourceKey }
            ?: error("Unknown stored vote source")
        val name = NetworkPlayerName.of(getString("player_name"))
        val playerBytes = getBytes("player_uuid")
        return VoteEvent(
            id = getBytes("event_uuid").uuid(),
            vote = AuthenticatedVote(
                source = source,
                externalId = getString("external_id"),
                playerName = name,
                occurredAt = getTimestamp("occurred_at").toInstant(),
            ),
            receivedAt = getTimestamp("received_at").toInstant(),
            rewardAmount = getBigDecimal("reward_amount"),
            rewardState = RewardState.valueOf(getString("reward_state")),
            playerId = playerBytes?.uuid(),
        )
    }

    private companion object {
        const val MIGRATION_NAMESPACE = "arc_votes"
        const val MYSQL_DUPLICATE_KEY = 1062
        const val SQL_STATE_INTEGRITY_CONSTRAINT = "23000"
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
