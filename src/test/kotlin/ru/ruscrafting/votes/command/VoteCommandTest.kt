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
import ru.ruscrafting.votes.config.ArcVotesSettings
import ru.ruscrafting.votes.config.MonitoringSource
import ru.ruscrafting.votes.storage.VoteHistoryLookup
import ru.ruscrafting.votes.status.VoteDailyStatusService
import ru.ruscrafting.votes.text.VoteLocale
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CompletableFuture
import java.util.logging.Logger

class VoteCommandTest : StringSpec({
    afterTest { ConfigManager.clear() }

    "vote renders four real monitoring links and status as separate lines" {
        val root = Files.createTempDirectory("arcvotes-command")
        val settings = ArcVotesSettings.load(root) { null }
        val locale = VoteLocale(root, { settings.defaultLocale }, { settings.useClientLocale })
        val sender = mockk<CommandSender>(relaxed = true)
        val command = mockk<Command>(relaxed = true)
        val messages = mutableListOf<Component>()
        every { sender.sendMessage(any<Component>()) } answers { messages += firstArg<Component>() }
        val tasks = LifecycleTaskScope(TestTaskScheduler())

        val vote = VoteCommand(settings, locale, null, tasks, null, Logger.getAnonymousLogger())
        vote.onCommand(sender, command, "vote", emptyArray()) shouldBe true

        messages shouldHaveSize 7
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
        links shouldBe settings.presentations.values.map { ClickEvent.openUrl(it.voteUrl.toASCIIString()) }.toSet()
        linkComponents.associate { it.clickEvent()!! to it.color()?.value() } shouldBe mapOf(
            ClickEvent.openUrl(settings.presentations.getValue(MonitoringSource.MINECRAFT_RATING).voteUrl.toASCIIString()) to 0xFFC857,
            ClickEvent.openUrl(settings.presentations.getValue(MonitoringSource.HOTMC).voteUrl.toASCIIString()) to 0xFF5F56,
            ClickEvent.openUrl(settings.presentations.getValue(MonitoringSource.MONITORING_MINECRAFT).voteUrl.toASCIIString()) to 0x43D995,
            ClickEvent.openUrl(settings.presentations.getValue(MonitoringSource.GAME_MONITORING).voteUrl.toASCIIString()) to 0xB784FF,
        )

        messages.clear()
        every { sender.hasPermission("arcvotes.admin.status") } returns true
        vote.onCommand(sender, command, "vote", arrayOf("status")) shouldBe true
        messages shouldHaveSize 9
    }

    "vote marks monitoring callbacks received during the current Moscow day" {
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
            CompletableFuture.completedFuture(setOf(MonitoringSource.MINECRAFT_RATING, MonitoringSource.HOTMC))
        }
        val dailyStatus = VoteDailyStatusService(
            history,
            Clock.fixed(Instant.parse("2026-08-31T12:00:00Z"), ZoneOffset.UTC),
        )
        val vote = VoteCommand(
            settings,
            locale,
            null,
            LifecycleTaskScope(scheduler),
            dailyStatus,
            Logger.getAnonymousLogger(),
        )

        vote.onCommand(player, command, "vote", emptyArray()) shouldBe true
        messages shouldHaveSize 0
        scheduler.executeImmediate()

        messages shouldHaveSize 7
        requestedFrom shouldBe Instant.parse("2026-08-30T21:00:00Z")
        requestedUntil shouldBe Instant.parse("2026-08-31T21:00:00Z")
        val plain = messages.joinToString("\n") { PlainTextComponentSerializer.plainText().serialize(it) }
        plain.count { it == '✔' } shouldBe 2
        plain.count { it == '◇' } shouldBe 2
    }
})

private fun Component.descendantsAndSelf(): List<Component> =
    listOf(this) + children().flatMap(Component::descendantsAndSelf)
