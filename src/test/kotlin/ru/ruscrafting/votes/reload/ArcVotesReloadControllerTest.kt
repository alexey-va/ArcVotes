package ru.ruscrafting.votes.reload

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import ru.arc.config.ConfigManager
import ru.ruscrafting.votes.config.ArcVotesSettings
import ru.ruscrafting.votes.live.VoteLiveConfiguration
import ru.ruscrafting.votes.live.VoteLiveState
import ru.ruscrafting.votes.text.VoteLocale
import java.nio.file.Files
import java.util.logging.Logger
import kotlin.io.path.readText
import kotlin.io.path.writeText

class ArcVotesReloadControllerTest : StringSpec({
    afterTest { ConfigManager.clear() }

    "applies a fresh live configuration and publishes one new generation" {
        val root = Files.createTempDirectory("arcvotes-reload-applied")
        val live = initialLive(root)
        val before = live.current()
        root.resolve("config.yml").writeText(
            root.resolve("config.yml").readText()
                .replace("default: ru", "default: en")
                .replace("cache-ttl-seconds: 30", "cache-ttl-seconds: 90"),
        )
        val controller = controller(root, live)

        controller.reload() shouldBe ArcVotesReloadResult.Applied
        (live.current() !== before) shouldBe true
        live.current().settings.defaultLocale shouldBe "en"
        live.current().settings.status.cacheTtlSeconds shouldBe 90L
    }

    "rejects invalid fresh configuration without replacing the live generation" {
        val root = Files.createTempDirectory("arcvotes-reload-failed")
        val live = initialLive(root)
        val before = live.current()
        root.resolve("config.yml").writeText(
            root.resolve("config.yml").readText().replace("default: ru", "default: xx"),
        )
        val controller = controller(root, live)

        controller.reload() shouldBe ArcVotesReloadResult.Failed
        live.current() shouldBe before
    }

    "requires restart for a server id change and does not publish it" {
        val root = Files.createTempDirectory("arcvotes-reload-restart")
        val live = initialLive(root)
        val before = live.current()
        root.resolve("config.yml").writeText(
            root.resolve("config.yml").readText().replace("server-id: survival", "server-id: spawn"),
        )
        val controller = controller(root, live)

        val result = controller.reload().shouldBeInstanceOf<ArcVotesReloadResult.RestartRequired>()
        result.fields shouldBe listOf("server-id")
        live.current() shouldBe before
    }
})

private fun initialLive(root: java.nio.file.Path): VoteLiveState {
    val settings = ArcVotesSettings.load(root) { null }
    val locale = VoteLocale(root, { settings.defaultLocale }, { settings.useClientLocale }).also { it.validate() }
    return VoteLiveState(
        VoteLiveConfiguration(
            settings = settings,
            locale = locale,
            dailyStatus = null,
            ingress = null,
            rewardDepositor = null,
            vaultReady = true,
            redisEconomyReady = true,
        ),
    )
}

private fun controller(root: java.nio.file.Path, live: VoteLiveState): ArcVotesReloadController = ArcVotesReloadController(
    dataRoot = root,
    live = live,
    repository = null,
    history = null,
    rewardService = null,
    rewardRuntimeFactory = VoteRewardRuntimeFactory { VoteRewardRuntime(null, true, true) },
    logger = Logger.getLogger("ArcVotesReloadControllerTest"),
)
