package ru.ruscrafting.votes.paper

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import ru.arc.paper.testing.MockBukkitTestRuntime

class PaperCompositionTest : StringSpec({
    "Paper composition owns the supported MockBukkit test runtime" {
        MockBukkitTestRuntime.open().use { runtime ->
            runtime.createSimplePlugin("ArcVotesComposition").isEnabled shouldBe true
        }
    }
})
