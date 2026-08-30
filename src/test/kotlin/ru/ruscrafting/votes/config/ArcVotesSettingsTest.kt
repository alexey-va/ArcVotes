package ru.ruscrafting.votes.config

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import ru.arc.config.ConfigManager
import ru.ruscrafting.votes.text.VoteLocale
import java.nio.file.Files
import kotlin.io.path.readText

class ArcVotesSettingsTest : StringSpec({
    afterTest { ConfigManager.clear() }

    "bundled first boot is safe link-only mode and locales have exact key parity" {
        val root = Files.createTempDirectory("arcvotes-settings")
        val settings = ArcVotesSettings.load(root) { null }

        settings.http.enabled shouldBe false
        settings.sql shouldBe null
        settings.reward.enabled shouldBe false
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
})
