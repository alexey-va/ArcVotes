package ru.ruscrafting.votes.config

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import ru.arc.config.ConfigManager
import ru.ruscrafting.votes.text.VoteLocale
import java.nio.file.Files
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
