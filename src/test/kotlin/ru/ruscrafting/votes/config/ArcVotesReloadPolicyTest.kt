package ru.ruscrafting.votes.config

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import ru.arc.sql.SqlConnectionConfig
import java.net.InetAddress
import java.nio.file.Files

class ArcVotesReloadPolicyTest : StringSpec({
    "returns deterministic restart labels for server, mysql, and enabled http changes" {
        val root = Files.createTempDirectory("arcvotes-reload-policy")
        val current = ArcVotesSettings.load(root) { null }
        val candidate = current.copy(
            serverId = "other",
            sql = SqlConnectionConfig("mysql", database = "minecraft", username = "minecraft", password = "secret"),
            minecraftRating = current.minecraftRating.copy(
                enabled = true,
                secret = SecretValue.of("reload-policy-secret"),
            ),
            http = current.http.copy(
                enabled = true,
                port = current.http.port + 1,
                workerThreads = current.http.workerThreads + 1,
                queueCapacity = current.http.queueCapacity + 1,
                bindAddress = InetAddress.getByName("::1"),
            ),
        )

        restartRequiredFields(current, candidate) shouldBe listOf(
            "server-id",
            "mysql",
        )
        restartRequiredFields(candidate, candidate.copy(
            http = candidate.http.copy(
                bindAddress = InetAddress.getByName("127.0.0.1"),
                port = candidate.http.port + 1,
                workerThreads = candidate.http.workerThreads + 1,
                queueCapacity = candidate.http.queueCapacity + 1,
            ),
        )) shouldBe listOf(
            "http.bind-address",
            "http.port",
            "http.worker-threads",
            "http.queue-capacity",
        )
    }

    "allows http enablement and structural changes while either side is disabled" {
        val root = Files.createTempDirectory("arcvotes-reload-http")
        val current = ArcVotesSettings.load(root) { null }
        val disabled = current.copy(
            sql = SqlConnectionConfig("mysql", database = "minecraft", username = "minecraft", password = "secret"),
            minecraftRating = current.minecraftRating.copy(
                enabled = true,
                secret = SecretValue.of("reload-policy-secret"),
            ),
        )
        val enabled = disabled.copy(http = disabled.http.copy(enabled = true, port = 12345))
        restartRequiredFields(disabled, enabled) shouldBe emptyList()
        restartRequiredFields(enabled, enabled.copy(http = enabled.http.copy(enabled = false, port = 12346))) shouldBe emptyList()
    }
})
