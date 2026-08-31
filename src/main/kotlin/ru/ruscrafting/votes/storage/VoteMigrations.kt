package ru.ruscrafting.votes.storage

import ru.arc.sql.SqlMigration
import ru.arc.sql.onetime.MySqlOneTimeUseLedger

object VoteMigrations {
    val EVENTS = SqlMigration(
        version = 1,
        description = "create durable vote events",
        statements = listOf(
            """
            CREATE TABLE IF NOT EXISTS `arc_votes_events` (
                `event_uuid` BINARY(16) NOT NULL,
                `source` VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
                `external_id` VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
                `player_name` VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
                `player_name_normalized` VARCHAR(32) CHARACTER SET ascii COLLATE ascii_general_ci NOT NULL,
                `occurred_at` TIMESTAMP(3) NOT NULL,
                `received_at` TIMESTAMP(3) NOT NULL,
                `reward_amount` DECIMAL(20,2) NULL,
                `reward_state` VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
                `player_uuid` BINARY(16) NULL,
                `rewarded_at` TIMESTAMP(3) NULL,
                `failure_code` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
                PRIMARY KEY (`event_uuid`),
                UNIQUE KEY `arc_votes_source_external_uq` (`source`, `external_id`),
                KEY `arc_votes_player_pending_idx` (`player_name_normalized`, `reward_state`, `received_at`),
                CONSTRAINT `arc_votes_reward_state_chk`
                    CHECK (`reward_state` IN ('NONE', 'PENDING', 'GRANTED', 'RECOVERY'))
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """.trimIndent(),
        ),
    )

    val REWARD_COMPONENTS = SqlMigration(
        version = 3,
        description = "create configurable vote reward components",
        statements = listOf(
            """
            CREATE TABLE IF NOT EXISTS `arc_votes_reward_components` (
                `event_uuid` BINARY(16) NOT NULL,
                `component_key` VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
                `provider` VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
                `currency_id` VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL,
                `amount` DECIMAL(20,2) NOT NULL,
                PRIMARY KEY (`event_uuid`, `component_key`),
                CONSTRAINT `arc_votes_reward_component_event_fk`
                    FOREIGN KEY (`event_uuid`) REFERENCES `arc_votes_events` (`event_uuid`) ON DELETE CASCADE,
                CONSTRAINT `arc_votes_reward_provider_chk`
                    CHECK (`provider` IN ('vault', 'redis_economy')),
                CONSTRAINT `arc_votes_reward_amount_chk`
                    CHECK (`amount` > 0 AND `amount` <= 1000000.00)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """.trimIndent(),
        ),
    )

    val NETWORK_WIDE_REWARD_CLAIMS = SqlMigration(
        version = 4,
        description = "make pending vote reward claims network-wide",
        statements = listOf(
            """
            UPDATE `arc_one_time_uses`
            SET `claim_scope` = NULL
            WHERE `purpose` = 'vote_reward'
              AND `status` = 'CLAIMED'
              AND `claim_scope` IS NOT NULL
            """.trimIndent(),
        ),
    )

    val ALL = listOf(
        EVENTS,
        MySqlOneTimeUseLedger.createTableMigration(version = 2),
        REWARD_COMPONENTS,
        NETWORK_WIDE_REWARD_CLAIMS,
    )
}
