package ru.ruscrafting.votes.config

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import ru.arc.config.ConfigManager
import ru.ruscrafting.votes.text.VoteLocale
import java.nio.file.Files
import java.net.URI
import java.time.ZoneId
import kotlin.io.path.readText
import kotlin.io.path.writeText

class ArcVotesSettingsTest : StringSpec({
    afterTest { ConfigManager.clear() }

    "bundled first boot is safe link-only mode and locales have exact key parity" {
        val root = Files.createTempDirectory("arcvotes-settings")
        val settings = ArcVotesSettings.load(root) { null }

        settings.http.enabled shouldBe false
        settings.sql shouldBe null
        settings.reward.enabled shouldBe false
        settings.reward.pollIntervalSeconds shouldBe 5L
        settings.reward.standard.amount.compareTo(java.math.BigDecimal("1000")) shouldBe 0
        settings.reward.premium.amount.compareTo(java.math.BigDecimal("3")) shouldBe 0
        settings.reward.premium.currencyId shouldBe "tokens"
        settings.reward.maximumOnlinePlayerBatch shouldBe 500
        settings.http.maximumHeaderCount shouldBe 32
        settings.http.maximumHeaderBytes shouldBe 8192
        settings.http.maximumHeaderValueBytes shouldBe 4096
        settings.gameMonitoring.connectTimeoutMs shouldBe 3000L
        settings.gameMonitoring.requestTimeoutMs shouldBe 4000L
        settings.gameMonitoring.maximumResponseBytes shouldBe 32768
        settings.gameMonitoring.apiBaseUrl shouldBe URI("https://api.gamemonitoring.ru/votes/")
        settings.status.voteDayZone shouldBe ZoneId.of("Europe/Moscow")
        settings.status.cacheTtlSeconds shouldBe 30L
        settings.status.maximumCacheEntries shouldBe 2048
        settings.enabledSources shouldBe emptySet()
        VoteLocale(root, { settings.defaultLocale }, { settings.useClientLocale }).validate()
    }

    "consumer contract never opts into the metrics exporter" {
        val project = java.nio.file.Path.of(System.getProperty("arcvotes.projectDir"))
        val manifest = project.resolve("arc-core-consumer.toml").readText()
        val build = project.resolve("build.gradle.kts").readText()

        manifest.contains("\"metrics\"") shouldBe false
        build.contains("arc-core-metrics") shouldBe false
        build.contains("arc-core-sql") shouldBe true
    }

    "source allowlists reject malformed IP literals before startup" {
        shouldThrow<IllegalArgumentException> {
            NetworkSourcePolicy(enforceIpAllowlist = false, allowedIps = setOf(":::"))
        }
    }

    "fresh load reads disk changes without replacing the cached config" {
        val root = Files.createTempDirectory("arcvotes-fresh-settings")
        val initial = ArcVotesSettings.load(root) { null }
        root.resolve("config.yml").writeText(root.resolve("config.yml").readText().replace("cache-ttl-seconds: 30", "cache-ttl-seconds: 90"))

        initial.status.cacheTtlSeconds shouldBe 30L
        ArcVotesSettings.loadFresh(root) { null }.status.cacheTtlSeconds shouldBe 90L
        ArcVotesSettings.load(root) { null }.status.cacheTtlSeconds shouldBe 30L
    }

    "new limits reject unsafe values" {
        shouldThrow<IllegalArgumentException> { StatusSettings(cacheTtlSeconds = 0) }
        shouldThrow<IllegalArgumentException> {
            HttpSettings(
                enabled = false,
                bindAddress = java.net.InetAddress.getLoopbackAddress(),
                port = 1,
                workerThreads = 1,
                queueCapacity = 8,
                maximumBodyBytes = 1024,
                persistenceTimeoutMs = 500,
                trustSingleForwardedClientIp = true,
                maximumHeaderCount = 8,
                maximumHeaderBytes = 1024,
                maximumHeaderValueBytes = 2048,
            )
        }
        shouldThrow<IllegalArgumentException> {
            GameMonitoringSettings(
                enabled = false,
                presentation = SourcePresentation("GameMonitoring", URI("https://example.test/vote")),
                webhookToken = null,
                expectedEntityType = "server",
                expectedEntityId = "1",
                network = NetworkSourcePolicy(false, emptySet()),
                apiBaseUrl = URI("https://evil.example/votes/"),
            )
        }
        shouldThrow<IllegalArgumentException> {
            GameMonitoringSettings(
                enabled = false,
                presentation = SourcePresentation("GameMonitoring", URI("https://example.test/vote")),
                webhookToken = null,
                expectedEntityType = "server",
                expectedEntityId = "1",
                network = NetworkSourcePolicy(false, emptySet()),
                apiBaseUrl = URI("https://user@api.gamemonitoring.ru/votes/"),
            )
        }
    }

    "legacy reward amount is preserved without silently enabling premium rewards" {
        val root = Files.createTempDirectory("arcvotes-legacy-settings")
        root.resolve("config.yml").writeText(
            """
            mysql:
              enabled: true
            reward:
              enabled: true
              amount: 100.00
              currency-label: монет
            """.trimIndent(),
        )
        ArcVotesSettings.mergeDefaults(root)

        val settings = ArcVotesSettings.load(root) { "test-secret" }

        settings.reward.standard.amount.compareTo(java.math.BigDecimal("100.00")) shouldBe 0
        settings.reward.premium.enabled shouldBe false
    }

    "malformed legacy reward amount fails before bundled rewards are merged" {
        val root = Files.createTempDirectory("arcvotes-malformed-legacy-settings")
        root.resolve("config.yml").writeText(
            """
            reward:
              enabled: true
              amount: nope
            """.trimIndent(),
        )

        shouldThrow<IllegalArgumentException> {
            ArcVotesSettings.mergeDefaults(root)
        }.message shouldBe "Legacy reward.amount must be a decimal"
    }

    "missing legacy reward amount fails before bundled rewards are merged" {
        val root = Files.createTempDirectory("arcvotes-missing-legacy-settings")
        root.resolve("config.yml").writeText(
            """
            reward:
              enabled: true
              currency-label: монет
            """.trimIndent(),
        )

        shouldThrow<IllegalArgumentException> {
            ArcVotesSettings.mergeDefaults(root)
        }.message shouldBe "Legacy reward.amount is required"
    }
})
