package ru.ruscrafting.votes.callback

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class VoteIngressCountersTest : StringSpec({
    "counters retain totals across ingress generations" {
        val counters = VoteIngressCounters()
        counters.accepted()
        counters.duplicate()
        counters.rejected()
        counters.upstreamFailure()

        val nextGeneration = counters.snapshot()
        nextGeneration shouldBe VoteIngressSnapshot(1, 1, 1, 1)

        counters.accepted()
        counters.rejected()
        counters.snapshot() shouldBe VoteIngressSnapshot(2, 1, 2, 1)
    }
})
