package ru.ruscrafting.votes.command

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import ru.ruscrafting.votes.callback.VoteIngressService
import ru.ruscrafting.votes.config.ArcVotesSettings
import ru.ruscrafting.votes.config.MonitoringSource
import ru.ruscrafting.votes.text.VoteLocale

class VoteCommand(
    private val settings: ArcVotesSettings,
    private val locale: VoteLocale,
    private val ingress: VoteIngressService?,
) : CommandExecutor, TabCompleter {
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (args.isEmpty()) {
            val links = settings.presentations.mapValues { (source, presentation) ->
                link(source, sender, presentation.displayName, presentation.voteUrl.toASCIIString())
            }
            val values = mapOf(
                "minecraft_rating" to links.getValue(MonitoringSource.MINECRAFT_RATING),
                "hotmc" to links.getValue(MonitoringSource.HOTMC),
                "monitoring_minecraft" to links.getValue(MonitoringSource.MONITORING_MINECRAFT),
                "game_monitoring" to links.getValue(MonitoringSource.GAME_MONITORING),
            )
            locale.renderLines("commands.vote-list", sender, values).forEach(sender::sendMessage)
            if (settings.reward.enabled) sender.sendMessage(locale.render("commands.reward-note", sender))
            return true
        }
        if (args.size == 1 && args[0].equals("status", ignoreCase = true)) {
            if (!sender.hasPermission("arcvotes.admin.status")) {
                sender.sendMessage(locale.render("commands.no-permission", sender))
                return true
            }
            val values = mapOf(
                "http" to state(sender, settings.http.enabled),
                "mysql" to state(sender, settings.sql != null),
                "reward" to state(sender, settings.reward.enabled),
                "minecraft_rating_status" to state(sender, MonitoringSource.MINECRAFT_RATING in settings.enabledSources),
                "hotmc_status" to state(sender, MonitoringSource.HOTMC in settings.enabledSources),
                "monitoring_minecraft_status" to state(
                    sender,
                    MonitoringSource.MONITORING_MINECRAFT in settings.enabledSources,
                ),
                "game_monitoring_status" to state(sender, MonitoringSource.GAME_MONITORING in settings.enabledSources),
            )
            locale.renderLines("commands.status", sender, values).forEach(sender::sendMessage)
            ingress?.snapshot()?.let { snapshot ->
                locale.renderLines(
                    "commands.counters",
                    sender,
                    mapOf(
                        "accepted" to counter(snapshot.accepted),
                        "duplicates" to counter(snapshot.duplicates),
                        "rejected" to counter(snapshot.rejected),
                        "upstream_failures" to counter(snapshot.upstreamFailures),
                    ),
                ).forEach(sender::sendMessage)
            }
            return true
        }
        sender.sendMessage(locale.render("commands.help", sender))
        return true
    }

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        alias: String,
        args: Array<out String>,
    ): List<String> = if (args.size == 1 && sender.hasPermission("arcvotes.admin.status") &&
        "status".startsWith(args[0], ignoreCase = true)
    ) listOf("status") else emptyList()

    private fun link(source: MonitoringSource, sender: CommandSender, label: String, url: String): Component =
        Component.text(label)
        .color(sourceColor(source))
        .decorate(TextDecoration.UNDERLINED)
        .clickEvent(ClickEvent.openUrl(url))
        .hoverEvent(
            HoverEvent.showText(
                locale.render("commands.open-hint", sender, mapOf("site" to locale.text(label))),
            ),
        )

    private fun sourceColor(source: MonitoringSource): TextColor = when (source) {
        MonitoringSource.MINECRAFT_RATING -> TextColor.color(0xE7BD72)
        MonitoringSource.HOTMC -> TextColor.color(0xE58B7F)
        MonitoringSource.MONITORING_MINECRAFT -> TextColor.color(0x82C7AE)
        MonitoringSource.GAME_MONITORING -> TextColor.color(0xB29BD4)
    }

    private fun state(sender: CommandSender, enabled: Boolean): Component = locale.render(
        if (enabled) "commands.state-enabled" else "commands.state-disabled",
        sender,
    )

    private fun counter(value: Long): Component = Component.text(value, TextColor.color(0xFFF4E3))
}
