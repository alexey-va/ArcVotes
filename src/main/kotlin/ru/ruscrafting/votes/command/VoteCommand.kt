package ru.ruscrafting.votes.command

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.whenCompleteSync
import ru.arc.network.NetworkPlayerName
import ru.ruscrafting.votes.config.MonitoringSource
import ru.ruscrafting.votes.domain.RewardState
import ru.ruscrafting.votes.domain.VoteEvent
import ru.ruscrafting.votes.live.VoteLiveConfiguration
import ru.ruscrafting.votes.storage.VoteHistoryPageLookup
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.logging.Level
import java.util.logging.Logger

class VoteCommand(
    private val live: () -> VoteLiveConfiguration,
    private val tasks: LifecycleTaskScope,
    private val logger: Logger,
    private val history: VoteHistoryPageLookup? = null,
) : CommandExecutor, TabCompleter {
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        when {
            args.isEmpty() -> showVoteList(sender)
            args.size == 1 && args[0].equals("status", ignoreCase = true) -> showServiceStatus(sender)
            args.size == 2 && args[0].equals("check", ignoreCase = true) -> showPlayerStatus(sender, args[1])
            args.size in 2..3 && args[0].equals("history", ignoreCase = true) -> {
                showPlayerHistory(sender, args[1], args.getOrNull(2))
            }
            else -> showHelp(sender)
        }
        return true
    }

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        alias: String,
        args: Array<out String>,
    ): List<String> = when {
        args.size == 1 -> buildList {
            if (sender.hasPermission(STATUS_PERMISSION)) add("status")
            if (sender.hasPermission(INSPECT_PERMISSION)) addAll(listOf("check", "history"))
        }.filter { it.startsWith(args[0], ignoreCase = true) }

        args.size == 2 && sender.hasPermission(INSPECT_PERMISSION) &&
            args[0].lowercase(Locale.ROOT) in setOf("check", "history") -> sender.server.onlinePlayers
            .map { it.name }
            .filter { it.startsWith(args[1], ignoreCase = true) }
            .sortedWith(String.CASE_INSENSITIVE_ORDER)

        else -> emptyList()
    }

    private fun showServiceStatus(sender: CommandSender) {
        val runtime = live()
        if (!sender.hasPermission(STATUS_PERMISSION)) {
            sender.sendMessage(runtime.locale.render("commands.no-permission", sender))
            return
        }
        val ingress = runtime.ingress
        val localIngressEnabled = runtime.settings.http.enabled && ingress != null
        val values = mapOf(
            "mysql" to state(runtime, sender, runtime.settings.sql != null),
            "reward" to state(
                runtime,
                sender,
                runtime.settings.reward.enabled && runtime.vaultReady && runtime.redisEconomyReady,
            ),
            "callback" to state(runtime, sender, localIngressEnabled),
            "source_count" to runtime.locale.text(runtime.settings.enabledSources.size),
        )
        runtime.locale.renderLines(
            if (localIngressEnabled) "commands.status-ingress" else "commands.status",
            sender,
            values,
        ).forEach(sender::sendMessage)
        ingress?.takeIf { localIngressEnabled }?.snapshot()?.let { snapshot ->
            runtime.locale.renderLines(
                "commands.counters",
                sender,
                mapOf(
                    "accepted" to runtime.locale.text(snapshot.accepted),
                    "duplicates" to runtime.locale.text(snapshot.duplicates),
                    "rejected" to runtime.locale.text(snapshot.rejected),
                    "upstream_failures" to runtime.locale.text(snapshot.upstreamFailures),
                ),
            ).forEach(sender::sendMessage)
        }
    }

    private fun showPlayerStatus(sender: CommandSender, rawPlayerName: String) {
        val runtime = live()
        if (!sender.hasPermission(INSPECT_PERMISSION)) {
            sender.sendMessage(runtime.locale.render("commands.no-permission", sender))
            return
        }
        val playerName = playerName(runtime, sender, rawPlayerName) ?: return
        val dailyStatus = runtime.dailyStatus
        if (dailyStatus == null) {
            sender.sendMessage(runtime.locale.render("commands.storage-unavailable", sender))
            return
        }
        dailyStatus.find(playerName).whenCompleteSync(tasks) { votedSources, failure ->
            if (!sender.isActive()) return@whenCompleteSync
            if (failure != null) {
                logger.log(Level.WARNING, "Could not load admin vote status", failure)
                sender.sendMessage(runtime.locale.render("commands.lookup-failed", sender))
                return@whenCompleteSync
            }
            renderPlayerStatus(sender, playerName, votedSources.orEmpty(), runtime)
        }
    }

    private fun renderPlayerStatus(
        sender: CommandSender,
        playerName: NetworkPlayerName,
        votedSources: Set<MonitoringSource>,
        runtime: VoteLiveConfiguration,
    ) {
        val presentations = runtime.settings.presentations
        val values = mapOf(
            "player" to runtime.locale.text(playerName.value),
            "voted_count" to runtime.locale.text(votedSources.size),
            "total_count" to runtime.locale.text(presentations.size),
            "check_minecraft_rating" to runtime.locale.text(
                presentations.getValue(MonitoringSource.MINECRAFT_RATING).displayName,
            ),
            "minecraft_rating_state" to voteState(
                runtime,
                sender,
                MonitoringSource.MINECRAFT_RATING in votedSources,
            ),
            "check_hotmc" to runtime.locale.text(presentations.getValue(MonitoringSource.HOTMC).displayName),
            "hotmc_state" to voteState(runtime, sender, MonitoringSource.HOTMC in votedSources),
            "check_monitoring_minecraft" to runtime.locale.text(
                presentations.getValue(MonitoringSource.MONITORING_MINECRAFT).displayName,
            ),
            "monitoring_minecraft_state" to voteState(
                runtime,
                sender,
                MonitoringSource.MONITORING_MINECRAFT in votedSources,
            ),
            "check_game_monitoring" to runtime.locale.text(
                presentations.getValue(MonitoringSource.GAME_MONITORING).displayName,
            ),
            "game_monitoring_state" to voteState(runtime, sender, MonitoringSource.GAME_MONITORING in votedSources),
        )
        runtime.locale.renderLines("commands.player-check", sender, values).forEach(sender::sendMessage)
    }

    private fun showPlayerHistory(sender: CommandSender, rawPlayerName: String, rawPage: String?) {
        val runtime = live()
        if (!sender.hasPermission(INSPECT_PERMISSION)) {
            sender.sendMessage(runtime.locale.render("commands.no-permission", sender))
            return
        }
        val playerName = playerName(runtime, sender, rawPlayerName) ?: return
        val page = rawPage?.toIntOrNull() ?: 1
        if (page !in 1..runtime.settings.status.historyMaximumPages) {
            sender.sendMessage(
                runtime.locale.render(
                    "commands.page-invalid",
                    sender,
                    mapOf("maximum" to runtime.locale.text(runtime.settings.status.historyMaximumPages)),
                ),
            )
            return
        }
        val lookup = history
        if (lookup == null) {
            sender.sendMessage(runtime.locale.render("commands.storage-unavailable", sender))
            return
        }
        val pageSize = runtime.settings.status.historyPageSize
        lookup.findHistory(playerName, page - 1, pageSize).whenCompleteSync(tasks) { result, failure ->
            if (!sender.isActive()) return@whenCompleteSync
            if (failure != null) {
                logger.log(Level.WARNING, "Could not load admin vote history", failure)
                sender.sendMessage(runtime.locale.render("commands.lookup-failed", sender))
                return@whenCompleteSync
            }
            val historyPage = requireNotNull(result)
            val uncappedPages = if (historyPage.totalEntries == 0L) {
                1L
            } else {
                ((historyPage.totalEntries - 1L) / pageSize) + 1L
            }
            val totalPages = minOf(runtime.settings.status.historyMaximumPages.toLong(), uncappedPages).toInt()
            if (historyPage.totalEntries == 0L) {
                sender.sendMessage(
                    runtime.locale.render(
                        "commands.history-empty",
                        sender,
                        mapOf("player" to runtime.locale.text(playerName.value)),
                    ),
                )
                return@whenCompleteSync
            }
            if (page > totalPages || historyPage.entries.isEmpty()) {
                sender.sendMessage(
                    runtime.locale.render(
                        "commands.page-not-found",
                        sender,
                        mapOf("pages" to runtime.locale.text(totalPages)),
                    ),
                )
                return@whenCompleteSync
            }
            renderHistory(sender, playerName, page, totalPages, historyPage.totalEntries, historyPage.entries, runtime)
        }
    }

    private fun renderHistory(
        sender: CommandSender,
        playerName: NetworkPlayerName,
        page: Int,
        totalPages: Int,
        totalEntries: Long,
        entries: List<VoteEvent>,
        runtime: VoteLiveConfiguration,
    ) {
        runtime.locale.renderLines(
            "commands.history-header",
            sender,
            mapOf(
                "player" to runtime.locale.text(playerName.value),
                "count" to runtime.locale.text(totalEntries),
                "page" to runtime.locale.text(page),
                "pages" to runtime.locale.text(totalPages),
            ),
        ).forEach(sender::sendMessage)
        val firstEntry = (page - 1) * runtime.settings.status.historyPageSize
        entries.forEachIndexed { index, event ->
            val sourceName = runtime.settings.presentations.getValue(event.vote.source).displayName
            sender.sendMessage(
                runtime.locale.render(
                    "commands.history-row",
                    sender,
                    mapOf(
                        "index" to runtime.locale.text(firstEntry + index + 1),
                        "date" to runtime.locale.text(
                            HISTORY_TIME.format(event.vote.occurredAt.atZone(runtime.settings.status.voteDayZone)),
                        ),
                        "history_site" to runtime.locale.text(sourceName),
                        "history_state" to historyState(runtime, sender, event.rewardState),
                    ),
                ),
            )
        }
        if (totalPages > 1) {
            val previous = historyPageControl(runtime, sender, playerName, page - 1, page > 1, "commands.history-previous")
            val next = historyPageControl(runtime, sender, playerName, page + 1, page < totalPages, "commands.history-next")
            sender.sendMessage(
                runtime.locale.render(
                    "commands.history-navigation",
                    sender,
                    mapOf(
                        "previous" to previous,
                        "next" to next,
                        "page" to runtime.locale.text(page),
                        "pages" to runtime.locale.text(totalPages),
                    ),
                ),
            )
        }
    }

    private fun historyPageControl(
        runtime: VoteLiveConfiguration,
        sender: CommandSender,
        playerName: NetworkPlayerName,
        page: Int,
        enabled: Boolean,
        path: String,
    ): Component {
        val label = runtime.locale.render(if (enabled) path else "$path-disabled", sender)
        if (!enabled) return label
        return label
            .clickEvent(ClickEvent.runCommand("/vote history ${playerName.value} $page"))
            .hoverEvent(
                HoverEvent.showText(
                    runtime.locale.render(
                        "commands.history-page-hint",
                        sender,
                        mapOf("page" to runtime.locale.text(page)),
                    ),
                ),
            )
    }

    private fun showHelp(sender: CommandSender) {
        val runtime = live()
        if (sender.hasPermission(STATUS_PERMISSION) || sender.hasPermission(INSPECT_PERMISSION)) {
            runtime.locale.renderLines("commands.admin-help", sender).forEach(sender::sendMessage)
        } else {
            sender.sendMessage(runtime.locale.render("commands.help", sender))
        }
    }

    private fun link(
        runtime: VoteLiveConfiguration,
        source: MonitoringSource,
        sender: CommandSender,
        label: String,
        url: String,
    ): Component = runtime.locale.site(source, sender, label)
        .decorate(TextDecoration.UNDERLINED)
        .clickEvent(ClickEvent.openUrl(url))
        .hoverEvent(
            HoverEvent.showText(
                runtime.locale.render(
                    "commands.open-hint",
                    sender,
                    mapOf("site" to runtime.locale.site(source, sender, label)),
                ),
            ),
        )

    private fun showVoteList(sender: CommandSender) {
        val runtime = live()
        val dailyStatus = runtime.dailyStatus
        if (sender !is Player || dailyStatus == null) {
            renderVoteList(sender, emptySet(), runtime)
            return
        }
        dailyStatus.find(NetworkPlayerName.of(sender.name))
            .whenCompleteSync(tasks) { votedSources, failure ->
                if (!sender.isOnline) return@whenCompleteSync
                if (failure != null) {
                    logger.log(Level.WARNING, "Could not load recent vote status for /vote", failure)
                    renderVoteList(sender, emptySet(), runtime)
                } else {
                    renderVoteList(sender, votedSources.orEmpty(), runtime)
                }
            }
    }

    private fun renderVoteList(
        sender: CommandSender,
        votedSources: Set<MonitoringSource>,
        runtime: VoteLiveConfiguration,
    ) {
        val links = runtime.settings.presentations.mapValues { (source, presentation) ->
            link(runtime, source, sender, presentation.displayName, presentation.voteUrl.toASCIIString())
        }
        val values = mapOf(
            "minecraft_rating" to links.getValue(MonitoringSource.MINECRAFT_RATING),
            "minecraft_rating_state" to voteState(runtime, sender, MonitoringSource.MINECRAFT_RATING in votedSources),
            "hotmc" to links.getValue(MonitoringSource.HOTMC),
            "hotmc_state" to voteState(runtime, sender, MonitoringSource.HOTMC in votedSources),
            "monitoring_minecraft" to links.getValue(MonitoringSource.MONITORING_MINECRAFT),
            "monitoring_minecraft_state" to voteState(
                runtime,
                sender,
                MonitoringSource.MONITORING_MINECRAFT in votedSources,
            ),
            "game_monitoring" to links.getValue(MonitoringSource.GAME_MONITORING),
            "game_monitoring_state" to voteState(runtime, sender, MonitoringSource.GAME_MONITORING in votedSources),
        )
        runtime.locale.renderLines("commands.public-vote-list", sender, values).forEach(sender::sendMessage)
        if (runtime.settings.reward.enabled) {
            sender.sendMessage(runtime.locale.render("commands.reward-note", sender))
        }
    }

    private fun playerName(
        runtime: VoteLiveConfiguration,
        sender: CommandSender,
        rawPlayerName: String,
    ): NetworkPlayerName? = runCatching { NetworkPlayerName.of(rawPlayerName) }.getOrElse {
        sender.sendMessage(
            runtime.locale.render(
                "commands.player-invalid",
                sender,
                mapOf("player" to runtime.locale.text(rawPlayerName.take(32))),
            ),
        )
        null
    }

    private fun voteState(runtime: VoteLiveConfiguration, sender: CommandSender, voted: Boolean): Component = runtime.locale.render(
        if (voted) "commands.vote-state-voted" else "commands.vote-state-open",
        sender,
    )

    private fun state(runtime: VoteLiveConfiguration, sender: CommandSender, enabled: Boolean): Component = runtime.locale.render(
        if (enabled) "commands.state-enabled" else "commands.state-disabled",
        sender,
    )

    private fun historyState(
        runtime: VoteLiveConfiguration,
        sender: CommandSender,
        rewardState: RewardState,
    ): Component = runtime.locale.render(
        when (rewardState) {
            RewardState.GRANTED -> "commands.history-state-granted"
            RewardState.PENDING -> "commands.history-state-pending"
            RewardState.RECOVERY -> "commands.history-state-recovery"
            RewardState.NONE -> "commands.history-state-none"
        },
        sender,
    )

    private fun CommandSender.isActive(): Boolean = this !is Player || isOnline

    private companion object {
        const val STATUS_PERMISSION = "arcvotes.admin.status"
        const val INSPECT_PERMISSION = "arcvotes.admin.inspect"
        val HISTORY_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM HH:mm")
    }
}
