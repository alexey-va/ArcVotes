package ru.ruscrafting.votes.storage

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

class VoteMigrationsTest : StringSpec({
    "migration 4 repairs only retained vote reward claims" {
        VoteMigrations.ALL.map { it.version } shouldContainExactly listOf(1, 2, 3, 4)
        VoteMigrations.NETWORK_WIDE_REWARD_CLAIMS.statements.single()
            .replace(Regex("\\s+"), " ")
            .trim() shouldBe
            "UPDATE `arc_one_time_uses` SET `claim_scope` = NULL " +
            "WHERE `purpose` = 'vote_reward' AND `status` = 'CLAIMED' AND `claim_scope` IS NOT NULL"
    }
})
