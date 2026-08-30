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
import ru.arc.config.ConfigManager
import ru.ruscrafting.votes.config.ArcVotesSettings
import ru.ruscrafting.votes.text.VoteLocale
import java.nio.file.Files

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

        val vote = VoteCommand(settings, locale, null)
        vote.onCommand(sender, command, "vote", emptyArray()) shouldBe true

        messages shouldHaveSize 7
        val plain = PlainTextComponentSerializer.plainText()
        plain.serialize(messages.first()) shouldBe ""
        plain.serialize(messages.last()) shouldBe ""
        val links = messages
            .flatMap(Component::descendantsAndSelf)
            .mapNotNull(Component::clickEvent)
            .filter { it.action() == ClickEvent.Action.OPEN_URL }
            .toSet()
        links shouldBe settings.presentations.values.map { ClickEvent.openUrl(it.voteUrl.toASCIIString()) }.toSet()

        messages.clear()
        every { sender.hasPermission("arcvotes.admin.status") } returns true
        vote.onCommand(sender, command, "vote", arrayOf("status")) shouldBe true
        messages shouldHaveSize 9
    }
})

private fun Component.descendantsAndSelf(): List<Component> =
    listOf(this) + children().flatMap(Component::descendantsAndSelf)
