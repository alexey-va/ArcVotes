package ru.ruscrafting.votes.storage

import ru.arc.network.NetworkPlayerName
import ru.arc.sql.MySqlMigrator
import ru.arc.sql.SqlRuntime
import ru.ruscrafting.votes.config.MonitoringSource
import ru.ruscrafting.votes.domain.AuthenticatedVote
import ru.ruscrafting.votes.domain.RewardProvider
import ru.ruscrafting.votes.domain.RewardState
import ru.ruscrafting.votes.domain.VoteEvent
import ru.ruscrafting.votes.domain.VoteRecordResult
import ru.ruscrafting.votes.domain.VoteRewardBundle
import ru.ruscrafting.votes.domain.VoteRewardComponent
import java.math.BigDecimal
import java.nio.ByteBuffer
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Timestamp
import java.sql.Types
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CompletableFuture

interface VoteRepository {
    fun initialize(): CompletableFuture<Unit>
    fun record(vote: AuthenticatedVote, reward: VoteRewardBundle?): CompletableFuture<VoteRecordResult>
    fun findPending(playerName: NetworkPlayerName, limit: Int = 16): CompletableFuture<List<VoteEvent>>
    fun findPendingForPlayers(
        playerNames: Set<NetworkPlayerName>,
        perPlayerLimit: Int = 16,
    ): CompletableFuture<Map<String, List<VoteEvent>>>
    fun markGranted(eventId: UUID, playerId: UUID): CompletableFuture<Boolean>
    fun markRecovery(eventId: UUID, playerId: UUID?, failureCode: String): CompletableFuture<Boolean>
}

fun interface VoteHistoryLookup {
    /** Returns callback sources recorded for this player inside one bounded calendar-day window. */
    fun findVotedSources(
        playerName: NetworkPlayerName,
        fromInclusive: Instant,
        untilExclusive: Instant,
    ): CompletableFuture<Set<MonitoringSource>>
}

class MySqlVoteRepository(
    private val runtime: SqlRuntime,
    private val clock: Clock = Clock.systemUTC(),
) : VoteRepository, VoteHistoryLookup {
    override fun initialize(): CompletableFuture<Unit> = runtime.executor
        .submit { MySqlMigrator(runtime.dataSource, MIGRATION_NAMESPACE).migrate(VoteMigrations.ALL) }
        .thenApply { Unit }

    override fun record(vote: AuthenticatedVote, reward: VoteRewardBundle?): CompletableFuture<VoteRecordResult> =
        runtime.executor.transaction { connection ->
            val event = VoteEvent(
                id = UUID.randomUUID(),
                vote = vote,
                receivedAt = clock.instant(),
                reward = reward,
                rewardState = if (reward == null) RewardState.NONE else RewardState.PENDING,
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
                    val standardAmount = reward?.component(STANDARD_COMPONENT_KEY)?.amount
                    if (standardAmount == null) statement.setNull(8, Types.DECIMAL) else statement.setBigDecimal(8, standardAmount)
                    statement.setString(9, event.rewardState.name)
                    statement.executeUpdate()
                }
                reward?.let { insertRewardComponents(connection, event.id, it) }
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
        val normalized = playerName.value.lowercase(Locale.ROOT)
        return findPendingForPlayers(setOf(playerName), limit).thenApply { it[normalized].orEmpty() }
    }

    override fun findPendingForPlayers(
        playerNames: Set<NetworkPlayerName>,
        perPlayerLimit: Int,
    ): CompletableFuture<Map<String, List<VoteEvent>>> {
        require(perPlayerLimit in 1..64) { "Pending vote limit must be between 1 and 64" }
        if (playerNames.isEmpty()) return CompletableFuture.completedFuture(emptyMap())
        val normalizedNames = playerNames.map { it.value.lowercase(Locale.ROOT) }.distinct()
        require(normalizedNames.size <= MAXIMUM_PLAYER_BATCH) { "Pending vote player batch exceeds $MAXIMUM_PLAYER_BATCH" }
        val placeholders = normalizedNames.joinToString(",") { "?" }
        return runtime.executor.read { connection ->
            connection.prepareStatement(
                """
                SELECT `pending`.`event_uuid`, `pending`.`source`, `pending`.`external_id`, `pending`.`player_name`,
                       `pending`.`player_name_normalized`, `pending`.`occurred_at`, `pending`.`received_at`,
                       `pending`.`reward_amount`, `pending`.`reward_state`, `pending`.`player_uuid`,
                       `component`.`component_key`, `component`.`provider`, `component`.`currency_id`,
                       `component`.`amount` AS `component_amount`
                FROM (
                    SELECT `event_uuid`, `source`, `external_id`, `player_name`, `player_name_normalized`,
                           `occurred_at`, `received_at`, `reward_amount`, `reward_state`, `player_uuid`,
                           ROW_NUMBER() OVER (
                               PARTITION BY `player_name_normalized`
                               ORDER BY `received_at` ASC, `event_uuid` ASC
                           ) AS `pending_rank`
                    FROM `arc_votes_events`
                    WHERE `player_name_normalized` IN ($placeholders) AND `reward_state` = 'PENDING'
                ) AS `pending`
                LEFT JOIN `arc_votes_reward_components` AS `component`
                    ON `component`.`event_uuid` = `pending`.`event_uuid`
                WHERE `pending`.`pending_rank` <= ?
                ORDER BY `pending`.`received_at` ASC, `pending`.`event_uuid` ASC,
                         CASE `component`.`component_key` WHEN 'standard' THEN 0 WHEN 'premium' THEN 1 ELSE 2 END ASC
                """.trimIndent(),
            ).use { statement ->
                normalizedNames.forEachIndexed { index, name -> statement.setString(index + 1, name) }
                statement.setInt(normalizedNames.size + 1, perPlayerLimit)
                statement.executeQuery().use { rows ->
                    readEvents(rows).groupBy { it.vote.normalizedPlayerName }
                }
            }
        }
    }

    override fun findVotedSources(
        playerName: NetworkPlayerName,
        fromInclusive: Instant,
        untilExclusive: Instant,
    ): CompletableFuture<Set<MonitoringSource>> {
        require(untilExclusive.isAfter(fromInclusive)) { "Vote history window must be positive" }
        require(Duration.between(fromInclusive, untilExclusive) <= MAXIMUM_HISTORY_WINDOW) {
            "Vote history window must not exceed 26 hours"
        }
        return runtime.executor.read { connection ->
            connection.prepareStatement(
                """
                SELECT DISTINCT `source`
                FROM `arc_votes_events`
                WHERE `player_name_normalized` = ?
                  AND `occurred_at` >= ?
                  AND `occurred_at` < ?
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, playerName.value.lowercase(Locale.ROOT))
                statement.setTimestamp(2, Timestamp.from(fromInclusive))
                statement.setTimestamp(3, Timestamp.from(untilExclusive))
                statement.executeQuery().use { rows ->
                    buildSet {
                        while (rows.next()) {
                            val sourceKey = rows.getString("source")
                            add(
                                MonitoringSource.entries.singleOrNull { it.configKey == sourceKey }
                                    ?: error("Unknown stored vote source"),
                            )
                        }
                    }
                }
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

    private fun insertRewardComponents(connection: Connection, eventId: UUID, reward: VoteRewardBundle) {
        connection.prepareStatement(
            """
            INSERT INTO `arc_votes_reward_components`
                (`event_uuid`, `component_key`, `provider`, `currency_id`, `amount`)
            VALUES (?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            reward.components.forEach { component ->
                statement.setBytes(1, eventId.bytes())
                statement.setString(2, component.key)
                statement.setString(3, component.provider.storageKey)
                if (component.currencyId == null) statement.setNull(4, Types.VARCHAR) else statement.setString(4, component.currencyId)
                statement.setBigDecimal(5, component.amount)
                statement.addBatch()
            }
            statement.executeBatch()
        }
    }

    private fun loadByExternalId(
        connection: Connection,
        source: MonitoringSource,
        externalId: String,
    ): VoteEvent? = connection.prepareStatement(
        """
        SELECT `event`.`event_uuid`, `event`.`source`, `event`.`external_id`, `event`.`player_name`,
               `event`.`player_name_normalized`, `event`.`occurred_at`, `event`.`received_at`,
               `event`.`reward_amount`, `event`.`reward_state`, `event`.`player_uuid`,
               `component`.`component_key`, `component`.`provider`, `component`.`currency_id`,
               `component`.`amount` AS `component_amount`
        FROM `arc_votes_events` AS `event`
        LEFT JOIN `arc_votes_reward_components` AS `component`
            ON `component`.`event_uuid` = `event`.`event_uuid`
        WHERE `event`.`source` = ? AND `event`.`external_id` = ?
        ORDER BY CASE `component`.`component_key` WHEN 'standard' THEN 0 WHEN 'premium' THEN 1 ELSE 2 END ASC
        """.trimIndent(),
    ).use { statement ->
        statement.setString(1, source.configKey)
        statement.setString(2, externalId)
        statement.executeQuery().use { rows -> readEvents(rows).singleOrNull() }
    }

    private fun readEvents(rows: ResultSet): List<VoteEvent> {
        val events = linkedMapOf<UUID, StoredVote>()
        while (rows.next()) {
            val eventId = rows.getBytes("event_uuid").uuid()
            val stored = events.getOrPut(eventId) { rows.storedVote(eventId) }
            rows.getString("component_key")?.let { key ->
                val providerKey = rows.getString("provider")
                val provider = RewardProvider.entries.singleOrNull { it.storageKey == providerKey }
                    ?: error("Unknown stored reward provider")
                stored.components += VoteRewardComponent(
                    key = key,
                    provider = provider,
                    currencyId = rows.getString("currency_id"),
                    amount = rows.getBigDecimal("component_amount"),
                )
            }
        }
        return events.values.map(StoredVote::event)
    }

    private fun ResultSet.storedVote(eventId: UUID): StoredVote {
        val sourceKey = getString("source")
        val source = MonitoringSource.entries.singleOrNull { it.configKey == sourceKey }
            ?: error("Unknown stored vote source")
        return StoredVote(
            id = eventId,
            vote = AuthenticatedVote(
                source = source,
                externalId = getString("external_id"),
                playerName = NetworkPlayerName.of(getString("player_name")),
                occurredAt = getTimestamp("occurred_at").toInstant(),
            ),
            receivedAt = getTimestamp("received_at").toInstant(),
            legacyRewardAmount = getBigDecimal("reward_amount"),
            rewardState = RewardState.valueOf(getString("reward_state")),
            playerId = getBytes("player_uuid")?.uuid(),
        )
    }

    private data class StoredVote(
        val id: UUID,
        val vote: AuthenticatedVote,
        val receivedAt: Instant,
        val legacyRewardAmount: BigDecimal?,
        val rewardState: RewardState,
        val playerId: UUID?,
        val components: MutableList<VoteRewardComponent> = mutableListOf(),
    ) {
        fun event(): VoteEvent {
            val storedComponents = components.toList().ifEmpty {
                listOfNotNull(
                    legacyRewardAmount?.let {
                        VoteRewardComponent(
                            key = STANDARD_COMPONENT_KEY,
                            provider = RewardProvider.VAULT,
                            amount = it,
                            legacyIdentity = true,
                        )
                    },
                )
            }
            return VoteEvent(
                id = id,
                vote = vote,
                receivedAt = receivedAt,
                reward = storedComponents.takeIf { it.isNotEmpty() }?.let(::VoteRewardBundle),
                rewardState = rewardState,
                playerId = playerId,
            )
        }
    }

    private companion object {
        const val MIGRATION_NAMESPACE = "arc_votes"
        const val STANDARD_COMPONENT_KEY = "standard"
        const val MAXIMUM_PLAYER_BATCH = 500
        const val MYSQL_DUPLICATE_KEY = 1062
        const val SQL_STATE_INTEGRITY_CONSTRAINT = "23000"
        val MAXIMUM_HISTORY_WINDOW: Duration = Duration.ofHours(26)
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
