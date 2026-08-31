package ru.ruscrafting.votes.command

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import ru.arc.config.ConfigManager
import ru.ruscrafting.votes.config.ArcVotesSettings
import ru.ruscrafting.votes.live.VoteLiveConfiguration
import ru.ruscrafting.votes.reload.ArcVotesReloader
import ru.ruscrafting.votes.reload.ArcVotesReloadResult
import ru.ruscrafting.votes.text.VoteLocale
import java.nio.file.Files

class ArcVotesAdminCommandTest : StringSpec({
    afterTest { ConfigManager.clear() }

    "reload maps every safe outcome through the current locale" {
        val root = Files.createTempDirectory("arcvotes-admin-command")
        val settings = ArcVotesSettings.load(root) { null }
        val locale = VoteLocale(root, { settings.defaultLocale }, { settings.useClientLocale })
        val runtime = VoteLiveConfiguration(
            settings = settings,
            locale = locale,
            dailyStatus = null,
            ingress = null,
            rewardDepositor = null,
            vaultReady = false,
            redisEconomyReady = false,
        )
        val sender = mockk<CommandSender>(relaxed = true)
        val command = mockk<Command>(relaxed = true)
        val messages = mutableListOf<Component>()
        every { sender.hasPermission("arcvotes.admin.reload") } returns true
        every { sender.sendMessage(any<Component>()) } answers { messages += firstArg<Component>() }

        fun execute(result: ArcVotesReloadResult): String {
            messages.clear()
            val admin = ArcVotesAdminCommand({ runtime }, ArcVotesReloader { result })
            admin.onCommand(sender, command, "arcvotes", arrayOf("reload")) shouldBe true
            return PlainTextComponentSerializer.plainText().serialize(messages.single())
        }

        execute(ArcVotesReloadResult.Applied) shouldBe "Голоса · Настройки голосований обновлены."
        execute(ArcVotesReloadResult.Busy) shouldBe "Голоса · Обновление уже выполняется."
        execute(ArcVotesReloadResult.Failed) shouldBe "Голоса · Настройки не обновлены."
        execute(ArcVotesReloadResult.RestartRequired(listOf("http.port", "secret-value"))) shouldBe
            "Голоса · Перезапуск нужен: http.port."
    }

    "reload command denies unauthorized callers and exposes no other action" {
        val root = Files.createTempDirectory("arcvotes-admin-help")
        val settings = ArcVotesSettings.load(root) { null }
        val locale = VoteLocale(root, { settings.defaultLocale }, { settings.useClientLocale })
        val runtime = VoteLiveConfiguration(settings, locale, null, null, null, false, false)
        val sender = mockk<CommandSender>(relaxed = true)
        val command = mockk<Command>(relaxed = true)
        val messages = mutableListOf<Component>()
        every { sender.sendMessage(any<Component>()) } answers { messages += firstArg<Component>() }
        every { sender.hasPermission("arcvotes.admin.reload") } returns false
        val admin = ArcVotesAdminCommand({ runtime }, ArcVotesReloader { ArcVotesReloadResult.Applied })

        admin.onCommand(sender, command, "arcvotes", arrayOf("reload")) shouldBe true
        PlainTextComponentSerializer.plainText().serialize(messages.single()) shouldBe "Голоса · Недостаточно прав."
        admin.onTabComplete(sender, command, "arcvotes", arrayOf("")) shouldBe emptyList()

        messages.clear()
        every { sender.hasPermission("arcvotes.admin.reload") } returns true
        admin.onCommand(sender, command, "arcvotes", arrayOf("status")) shouldBe true
        PlainTextComponentSerializer.plainText().serialize(messages.single()) shouldBe
            "Голоса · Использование: /arcvotes reload"
        admin.onTabComplete(sender, command, "arcvotes", arrayOf("re")) shouldBe listOf("reload")
    }
})
