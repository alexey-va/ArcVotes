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
import org.bukkit.entity.Player
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.whenCompleteSync
import ru.arc.network.NetworkPlayerName
import ru.ruscrafting.votes.callback.VoteIngressService
import ru.ruscrafting.votes.config.ArcVotesSettings
import ru.ruscrafting.votes.config.MonitoringSource
import ru.ruscrafting.votes.status.VoteDailyStatusService
import ru.ruscrafting.votes.text.VoteLocale
import java.util.logging.Level
import java.util.logging.Logger

class VoteCommand(
    private val settings: ArcVotesSettings,
    private val locale: VoteLocale,
    private val ingress: VoteIngressService?,
    private val tasks: LifecycleTaskScope,
    private val dailyStatus: VoteDailyStatusService?,
    private val logger: Logger,
) : CommandExecutor, TabCompleter {
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (args.isEmpty()) {
            showVoteList(sender)
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
        locale.site(source, sender, label)
        .decorate(TextDecoration.UNDERLINED)
        .clickEvent(ClickEvent.openUrl(url))
        .hoverEvent(
            HoverEvent.showText(
                locale.render("commands.open-hint", sender, mapOf("site" to locale.site(source, sender, label))),
            ),
        )

    private fun showVoteList(sender: CommandSender) {
        if (sender !is Player || dailyStatus == null) {
            renderVoteList(sender, emptySet())
            return
        }
        dailyStatus.find(NetworkPlayerName.of(sender.name))
            .whenCompleteSync(tasks) { votedSources, failure ->
                if (!sender.isOnline) return@whenCompleteSync
                if (failure != null) {
                    logger.log(Level.WARNING, "Could not load today's vote status for /vote", failure)
                    renderVoteList(sender, emptySet())
                } else {
                    renderVoteList(sender, votedSources.orEmpty())
                }
            }
    }

    private fun renderVoteList(sender: CommandSender, votedSources: Set<MonitoringSource>) {
        val links = settings.presentations.mapValues { (source, presentation) ->
            link(source, sender, presentation.displayName, presentation.voteUrl.toASCIIString())
        }
        val values = mapOf(
            "minecraft_rating" to links.getValue(MonitoringSource.MINECRAFT_RATING),
            "minecraft_rating_state" to voteState(sender, MonitoringSource.MINECRAFT_RATING in votedSources),
            "hotmc" to links.getValue(MonitoringSource.HOTMC),
            "hotmc_state" to voteState(sender, MonitoringSource.HOTMC in votedSources),
            "monitoring_minecraft" to links.getValue(MonitoringSource.MONITORING_MINECRAFT),
            "monitoring_minecraft_state" to voteState(sender, MonitoringSource.MONITORING_MINECRAFT in votedSources),
            "game_monitoring" to links.getValue(MonitoringSource.GAME_MONITORING),
            "game_monitoring_state" to voteState(sender, MonitoringSource.GAME_MONITORING in votedSources),
        )
        locale.renderLines("commands.vote-list", sender, values).forEach(sender::sendMessage)
        if (settings.reward.enabled) sender.sendMessage(locale.render("commands.reward-note", sender))
    }

    private fun voteState(sender: CommandSender, voted: Boolean): Component = locale.render(
        if (voted) "commands.vote-state-voted" else "commands.vote-state-open",
        sender,
    )

    private fun state(sender: CommandSender, enabled: Boolean): Component = locale.render(
        if (enabled) "commands.state-enabled" else "commands.state-disabled",
        sender,
    )

    private fun counter(value: Long): Component = Component.text(value, TextColor.color(0xFFF4E3))

}
