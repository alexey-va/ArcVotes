package ru.ruscrafting.votes.command

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import ru.arc.config.ConfigManager
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.TestTaskScheduler
import ru.ruscrafting.votes.callback.VoteIngressService
import ru.ruscrafting.votes.config.ArcVotesSettings
import ru.ruscrafting.votes.config.MonitoringSource
import ru.ruscrafting.votes.domain.AuthenticatedVote
import ru.ruscrafting.votes.domain.RewardProvider
import ru.ruscrafting.votes.domain.RewardState
import ru.ruscrafting.votes.domain.VoteEvent
import ru.ruscrafting.votes.domain.VoteRewardBundle
import ru.ruscrafting.votes.domain.VoteRewardComponent
import ru.ruscrafting.votes.live.VoteLiveConfiguration
import ru.ruscrafting.votes.live.VoteLiveState
import ru.ruscrafting.votes.storage.VoteHistoryPage
import ru.ruscrafting.votes.storage.VoteHistoryLookup
import ru.ruscrafting.votes.storage.VoteHistoryPageLookup
import ru.ruscrafting.votes.status.VoteDailyStatusService
import ru.ruscrafting.votes.text.VoteLocale
import java.math.BigDecimal
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.logging.Logger

class VoteCommandTest : StringSpec({
    afterTest { ConfigManager.clear() }

    "vote suggests three monitoring links while GameMonitoring stays hidden" {
        val root = Files.createTempDirectory("arcvotes-command")
        val settings = ArcVotesSettings.load(root) { null }
        val locale = VoteLocale(root, { settings.defaultLocale }, { settings.useClientLocale })
        val sender = mockk<CommandSender>(relaxed = true)
        val command = mockk<Command>(relaxed = true)
        val messages = mutableListOf<Component>()
        every { sender.sendMessage(any<Component>()) } answers { messages += firstArg<Component>() }
        val tasks = LifecycleTaskScope(TestTaskScheduler())

        val ingress = mockk<VoteIngressService>(relaxed = true)
        val live = VoteLiveState(VoteLiveConfiguration(settings, locale, null, ingress, null, false, false))
        val vote = VoteCommand(live::current, tasks, Logger.getAnonymousLogger())
        vote.onCommand(sender, command, "vote", emptyArray()) shouldBe true

        messages shouldHaveSize 6
        val plain = PlainTextComponentSerializer.plainText()
        plain.serialize(messages.first()) shouldBe ""
        plain.serialize(messages.last()) shouldBe ""
        val linkComponents = messages
            .flatMap(Component::descendantsAndSelf)
            .filter { it.clickEvent()?.action() == ClickEvent.Action.OPEN_URL }
        val links = linkComponents
            .mapNotNull(Component::clickEvent)
            .filter { it.action() == ClickEvent.Action.OPEN_URL }
            .toSet()
        links shouldBe settings.presentations
            .filterKeys { it != MonitoringSource.GAME_MONITORING }
            .values
            .map { ClickEvent.openUrl(it.voteUrl.toASCIIString()) }
            .toSet()
        linkComponents.associate { it.clickEvent()!! to it.color()?.value() } shouldBe mapOf(
            ClickEvent.openUrl(settings.presentations.getValue(MonitoringSource.MINECRAFT_RATING).voteUrl.toASCIIString()) to 0xFFC857,
            ClickEvent.openUrl(settings.presentations.getValue(MonitoringSource.HOTMC).voteUrl.toASCIIString()) to 0xFF5F56,
            ClickEvent.openUrl(settings.presentations.getValue(MonitoringSource.MONITORING_MINECRAFT).voteUrl.toASCIIString()) to 0x43D995,
        )
        val rendered = messages.joinToString("\n") { plain.serialize(it) }
        rendered.contains("GameMonitoring") shouldBe false
        rendered.contains("</color>") shouldBe false

        messages.clear()
        every { sender.hasPermission("arcvotes.admin.status") } returns true
        vote.onCommand(sender, command, "vote", arrayOf("status")) shouldBe true
        messages shouldHaveSize 3
        val status = messages.joinToString("\n") { plain.serialize(it) }
        status.contains("Админ · Голосования") shouldBe true
        status.contains("Callback") shouldBe false
        status.contains("ProxyARC") shouldBe false
        status.contains("ВЫКЛ") shouldBe false
        status.contains("MinecraftRating") shouldBe false
    }

    "vote marks monitoring callbacks active under provider rules" {
        val root = Files.createTempDirectory("arcvotes-command-history")
        val settings = ArcVotesSettings.load(root) { null }
        val locale = VoteLocale(root, { settings.defaultLocale }, { settings.useClientLocale })
        val player = mockk<Player>(relaxed = true)
        val command = mockk<Command>(relaxed = true)
        val messages = mutableListOf<Component>()
        every { player.name } returns "Steve"
        every { player.isOnline } returns true
        every { player.sendMessage(any<Component>()) } answers { messages += firstArg<Component>() }
        val scheduler = TestTaskScheduler()
        var requestedFrom: Instant? = null
        var requestedUntil: Instant? = null
        val history = VoteHistoryLookup { _, from, until ->
            requestedFrom = from
            requestedUntil = until
            CompletableFuture.completedFuture(
                mapOf(
                    MonitoringSource.MINECRAFT_RATING to Instant.parse("2026-08-31T11:00:00Z"),
                    MonitoringSource.HOTMC to Instant.parse("2026-08-31T11:00:00Z"),
                ),
            )
        }
        val dailyStatus = VoteDailyStatusService(
            history,
            Clock.fixed(Instant.parse("2026-08-31T12:00:00Z"), ZoneOffset.UTC),
        )
        val live = VoteLiveState(VoteLiveConfiguration(settings, locale, dailyStatus, null, null, false, false))
        val vote = VoteCommand(live::current, LifecycleTaskScope(scheduler), Logger.getAnonymousLogger())

        vote.onCommand(player, command, "vote", emptyArray()) shouldBe true
        messages shouldHaveSize 0
        scheduler.executeImmediate()

        messages shouldHaveSize 6
        requestedFrom shouldBe Instant.parse("2026-08-30T12:00:00Z")
        requestedUntil shouldBe Instant.parse("2026-08-31T12:00:00Z")
        val plain = messages.joinToString("\n") { PlainTextComponentSerializer.plainText().serialize(it) }
        plain.count { it == '✔' } shouldBe 2
        plain.count { it == '◇' } shouldBe 1
    }

    "an in-flight vote command renders one captured configuration generation" {
        val root = Files.createTempDirectory("arcvotes-command-generation")
        val settings = ArcVotesSettings.load(root) { null }
        val locale = VoteLocale(root, { settings.defaultLocale }, { settings.useClientLocale })
        val player = mockk<Player>(relaxed = true)
        val command = mockk<Command>(relaxed = true)
        val messages = mutableListOf<Component>()
        every { player.name } returns "Steve"
        every { player.isOnline } returns true
        every { player.sendMessage(any<Component>()) } answers { messages += firstArg<Component>() }
        val status = CompletableFuture<Map<MonitoringSource, Instant>>()
        val dailyStatus = VoteDailyStatusService(VoteHistoryLookup { _, _, _ -> status })
        val live = VoteLiveState(VoteLiveConfiguration(settings, locale, dailyStatus, null, null, false, false))
        val scheduler = TestTaskScheduler()
        val vote = VoteCommand(live::current, LifecycleTaskScope(scheduler), Logger.getAnonymousLogger())

        vote.onCommand(player, command, "vote", emptyArray()) shouldBe true
        live.publish(
            live.current().copy(
                settings = settings.copy(
                    hotMc = settings.hotMc.copy(
                        presentation = settings.hotMc.presentation.copy(
                            voteUrl = java.net.URI("https://hotmc.ru/servers/reloaded"),
                        ),
                    ),
                ),
            ),
        )
        status.complete(emptyMap())
        scheduler.executeImmediate()

        messages shouldHaveSize 6
        messages
            .flatMap(Component::descendantsAndSelf)
            .mapNotNull(Component::clickEvent)
            .toSet() shouldBe settings.presentations
            .filterKeys { it != MonitoringSource.GAME_MONITORING }
            .values
            .map { ClickEvent.openUrl(it.voteUrl.toASCIIString()) }
            .toSet()
    }

    "admin check uses the bounded provider-specific cache for another player" {
        val root = Files.createTempDirectory("arcvotes-command-check")
        val settings = ArcVotesSettings.load(root) { null }
        val locale = VoteLocale(root, { settings.defaultLocale }, { settings.useClientLocale })
        val sender = mockk<CommandSender>(relaxed = true)
        val command = mockk<Command>(relaxed = true)
        val messages = mutableListOf<Component>()
        every { sender.hasPermission("arcvotes.admin.inspect") } returns true
        every { sender.sendMessage(any<Component>()) } answers { messages += firstArg<Component>() }
        var lookups = 0
        val now = Instant.parse("2026-08-31T12:00:00Z")
        val dailyStatus = VoteDailyStatusService(
            VoteHistoryLookup { _, _, _ ->
                lookups++
                CompletableFuture.completedFuture(
                    mapOf(
                        MonitoringSource.HOTMC to now,
                        MonitoringSource.GAME_MONITORING to now,
                    ),
                )
            },
            Clock.fixed(now, ZoneOffset.UTC),
        )
        val scheduler = TestTaskScheduler()
        val live = VoteLiveState(VoteLiveConfiguration(settings, locale, dailyStatus, null, null, false, false))
        val vote = VoteCommand(live::current, LifecycleTaskScope(scheduler), Logger.getAnonymousLogger())

        repeat(2) {
            vote.onCommand(sender, command, "vote", arrayOf("check", "Alex")) shouldBe true
            scheduler.executeImmediate()
        }

        lookups shouldBe 1
        messages shouldHaveSize 12
        val plain = messages.joinToString("\n") { PlainTextComponentSerializer.plainText().serialize(it) }
        plain.contains("Игрок Alex") shouldBe true
        plain.contains("Активные голоса: 2 из 4") shouldBe true
        plain.contains("GameMonitoring") shouldBe true
        plain.count { it == '✔' } shouldBe 4
        plain.count { it == '◇' } shouldBe 4
    }

    "admin history renders a newest-first page with clickable navigation" {
        val root = Files.createTempDirectory("arcvotes-command-history-page")
        val settings = ArcVotesSettings.load(root) { null }
        val locale = VoteLocale(root, { settings.defaultLocale }, { settings.useClientLocale })
        val sender = mockk<CommandSender>(relaxed = true)
        val command = mockk<Command>(relaxed = true)
        val messages = mutableListOf<Component>()
        every { sender.hasPermission("arcvotes.admin.inspect") } returns true
        every { sender.sendMessage(any<Component>()) } answers { messages += firstArg<Component>() }
        val reward = VoteRewardBundle(
            listOf(VoteRewardComponent("standard", RewardProvider.VAULT, BigDecimal("1000.00"))),
        )
        val entries = listOf(
            VoteEvent(
                UUID.randomUUID(),
                AuthenticatedVote(
                    MonitoringSource.MONITORING_MINECRAFT,
                    "history:2",
                    ru.arc.network.NetworkPlayerName.of("Alex"),
                    Instant.parse("2026-08-31T12:17:00Z"),
                ),
                Instant.parse("2026-08-31T12:17:01Z"),
                reward,
                RewardState.GRANTED,
            ),
            VoteEvent(
                UUID.randomUUID(),
                AuthenticatedVote(
                    MonitoringSource.HOTMC,
                    "history:1",
                    ru.arc.network.NetworkPlayerName.of("Alex"),
                    Instant.parse("2026-08-30T20:10:00Z"),
                ),
                Instant.parse("2026-08-30T20:10:01Z"),
                reward,
                RewardState.PENDING,
            ),
        )
        var requestedPage = -1
        var requestedSize = -1
        val history = VoteHistoryPageLookup { _, page, pageSize ->
            requestedPage = page
            requestedSize = pageSize
            CompletableFuture.completedFuture(VoteHistoryPage(entries, 17))
        }
        val scheduler = TestTaskScheduler()
        val live = VoteLiveState(VoteLiveConfiguration(settings, locale, null, null, null, false, false))
        val vote = VoteCommand(live::current, LifecycleTaskScope(scheduler), Logger.getAnonymousLogger(), history)

        vote.onCommand(sender, command, "vote", arrayOf("history", "Alex", "2")) shouldBe true
        scheduler.executeImmediate()

        requestedPage shouldBe 1
        requestedSize shouldBe 8
        messages shouldHaveSize 5
        val plain = messages.joinToString("\n") { PlainTextComponentSerializer.plainText().serialize(it) }
        plain.contains("История Alex") shouldBe true
        plain.contains("Всего: 17 · страница 2 из 3") shouldBe true
        plain.contains("MonitoringMinecraft · ✔ выдана") shouldBe true
        plain.contains("HotMC · ⌚ ожидает") shouldBe true
        messages.flatMap(Component::descendantsAndSelf).mapNotNull(Component::clickEvent).toSet() shouldBe setOf(
            ClickEvent.runCommand("/vote history Alex 1"),
            ClickEvent.runCommand("/vote history Alex 3"),
        )
    }
})

private fun Component.descendantsAndSelf(): List<Component> =
    listOf(this) + children().flatMap(Component::descendantsAndSelf)
