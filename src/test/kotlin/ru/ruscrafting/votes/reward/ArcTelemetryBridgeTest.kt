package ru.ruscrafting.votes.reward

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.votes.domain.RewardProvider
import ru.ruscrafting.votes.domain.VoteRewardComponent
import java.math.BigDecimal
import java.net.URLClassLoader
import java.util.UUID

class ArcTelemetryBridgeTest : StringSpec({
    "optional ARC telemetry bridges are no-ops when ARC is unavailable" {
        MockBukkitTestRuntime.open().use {
            val playerId = UUID.randomUUID()
            ArcProductTelemetryBridge.rewardClaimed(playerId, "vote:test") shouldBe false
            ArcAuditRewardBridge.mark(
                playerId,
                VoteRewardComponent("standard", RewardProvider.VAULT, BigDecimal("1.00")),
                "reward:test",
            ) shouldBe null
            ArcAuditRewardBridge.cancel(playerId, "audit:test")
        }
    }

    "does not load the optional ARC API when ARC is absent" {
        MockBukkitTestRuntime.open().use { runtime ->
            runtime.server.pluginManager.isPluginEnabled("ARC") shouldBe false
            val auditName = "ru.ruscrafting.votes.reward.ArcAuditRewardBridge"
            val productName = "ru.ruscrafting.votes.reward.ArcProductTelemetryBridge"
            val targetNames = setOf(auditName, productName)
            val source = ArcAuditRewardBridge::class.java.protectionDomain.codeSource.location
            var apiLoads = 0
            object : URLClassLoader(arrayOf(source), ArcAuditRewardBridge::class.java.classLoader) {
                override fun loadClass(name: String, resolve: Boolean): Class<*> {
                    if (name.startsWith("ru.arc.paper.api.")) {
                        apiLoads++
                        throw ClassNotFoundException(name)
                    }
                    if (targetNames.any(name::startsWith)) {
                        return (findLoadedClass(name) ?: findClass(name)).also {
                            if (resolve) resolveClass(it)
                        }
                    }
                    return super.loadClass(name, resolve)
                }
            }.use { isolated ->
                val playerId = UUID.randomUUID()
                val auditType = isolated.loadClass(auditName)
                val audit = auditType.getField("INSTANCE").get(null)
                auditType.getMethod("mark", UUID::class.java, VoteRewardComponent::class.java, String::class.java)
                    .invoke(
                        audit,
                        playerId,
                        VoteRewardComponent("standard", RewardProvider.VAULT, BigDecimal("1.00")),
                        "reward:test",
                    ) shouldBe null
                auditType.getMethod("cancel", UUID::class.java, String::class.java)
                    .invoke(audit, playerId, "audit:test")

                val productType = isolated.loadClass(productName)
                val product = productType.getField("INSTANCE").get(null)
                productType.getMethod("rewardClaimed", UUID::class.java, String::class.java)
                    .invoke(product, playerId, "vote:test") shouldBe false

                apiLoads shouldBe 0
            }
        }
    }
})
