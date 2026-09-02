package ru.ruscrafting.votes.paper

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.entity.Player
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.whenCompleteSync
import ru.arc.network.NetworkPlayerName
import ru.arc.paper.menu.PaperMenuContent
import ru.arc.paper.menu.PaperMenuEntry
import ru.arc.paper.menu.PaperMenuItemFactory
import ru.arc.paper.menu.PaperMenuService
import ru.arc.paper.menu.PaperMenuSession
import ru.ruscrafting.votes.command.VoteMenuOpener
import ru.ruscrafting.votes.config.MonitoringSource
import ru.ruscrafting.votes.config.PUBLIC_MONITORING_SOURCES
import ru.ruscrafting.votes.config.VoteMenuSchema
import ru.ruscrafting.votes.live.VoteLiveConfiguration
import ru.ruscrafting.votes.status.VoteWindowPolicy
import ru.ruscrafting.votes.storage.VoteSiteHistory
import ru.ruscrafting.votes.storage.VoteSiteHistoryLookup
import java.time.Clock
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level
import java.util.logging.Logger

class VoteMenu(
    private val live: () -> VoteLiveConfiguration,
    private val tasks: LifecycleTaskScope,
    private val logger: Logger,
    private val history: VoteSiteHistoryLookup?,
    private val menus: PaperMenuService,
    private val menuConfiguration: () -> VoteMenuConfiguration,
    private val clock: Clock = Clock.systemUTC(),
) : VoteMenuOpener {
    private val windows = VoteWindowPolicy()
    private val warnedVisuals = ConcurrentHashMap.newKeySet<String>()
    private val itemFactory = PaperMenuItemFactory(diagnostics = ::warnVisual)

    override fun open(player: Player) {
        val state = ViewState(
            histories = emptyMap(),
            historyAvailable = history == null,
            ready = history == null,
        )
        lateinit var session: PaperMenuSession
        session = menus.open(player, VoteMenuSchema.menuId) { content(player, state) }

        val lookup = history ?: return
        lookup.findSiteHistory(
            NetworkPlayerName.of(player.name),
            live().settings.gui.historyEntriesPerSite,
        ).whenCompleteSync(tasks) { result, failure ->
            if (!session.isOpen) return@whenCompleteSync
            if (failure != null) {
                logger.log(Level.WARNING, "Could not load vote history for the player menu", failure)
                state.histories = emptyMap()
                state.historyAvailable = false
            } else {
                state.histories = result.orEmpty()
                state.historyAvailable = true
            }
            state.ready = true
            session.refresh()
        }
    }

    private fun content(player: Player, state: ViewState): PaperMenuContent {
        val runtime = live()
        val configuration = menuConfiguration()
        val layout = configuration.catalog.require(VoteMenuSchema.menuId)
        val backgroundTemplate = requireNotNull(layout.backgroundTemplate) { "Vote menu background template is required" }
        return PaperMenuContent(
            title = runtime.locale.render("gui.title", player),
            background = itemFactory.create(
                configuration.templates.getValue(backgroundTemplate.value),
                Component.empty(),
                emptyList(),
            ),
            elements = PUBLIC_MONITORING_SOURCES.associate { source ->
                val element = VoteMenuSchema.elements.getValue(source)
                val item = if (state.ready) {
                    siteItem(player, runtime, configuration, source, state.histories[source], state.historyAvailable)
                } else {
                    val site = runtime.settings.presentations.getValue(source)
                    itemFactory.create(
                        configuration.templates.getValue("loading"),
                        runtime.locale.render(
                            "gui.loading-name",
                            player,
                            mapOf("site" to runtime.locale.site(source, player, site.displayName)),
                        ),
                        runtime.locale.renderLines("gui.loading-lore", player),
                    )
                }
                element to PaperMenuEntry(
                    item = item,
                    enabled = state.ready,
                    onClick = { sendLink(it.player, runtime, source) },
                )
            },
        )
    }

    private fun siteItem(
        player: Player,
        runtime: VoteLiveConfiguration,
        configuration: VoteMenuConfiguration,
        source: MonitoringSource,
        history: VoteSiteHistory?,
        historyAvailable: Boolean,
    ) = itemFactory.create(
        template = configuration.templates.getValue(
            requireNotNull(
                configuration.catalog.require(VoteMenuSchema.menuId)
                    .elements.getValue(VoteMenuSchema.elements.getValue(source)).template,
            ) { "Vote menu element '${source.configKey}' must reference an item template" }.value,
        ),
        name = runtime.locale.site(source, player, runtime.settings.presentations.getValue(source).displayName),
        lore = buildList {
            if (historyAvailable) {
                val latest = history?.recentVotes?.firstOrNull()
                add(
                    runtime.locale.render(
                        if (latest != null && windows.isActive(source, latest, clock.instant())) {
                            "gui.site-status-voted"
                        } else {
                            "gui.site-status-open"
                        },
                        player,
                    ),
                )
                add(
                    runtime.locale.render(
                        "gui.site-total",
                        player,
                        mapOf("total" to runtime.locale.text(history?.totalVotes ?: 0)),
                    ),
                )
                add(runtime.locale.render("gui.spacer", player))
                add(runtime.locale.render("gui.site-history-title", player))
                val recentVotes = history?.recentVotes.orEmpty()
                if (recentVotes.isEmpty()) {
                    add(runtime.locale.render("gui.site-history-empty", player))
                } else {
                    recentVotes.forEach { votedAt ->
                        add(
                            runtime.locale.render(
                                "gui.site-history-row",
                                player,
                                mapOf(
                                    "date" to runtime.locale.text(
                                        HISTORY_TIME.format(votedAt.atZone(runtime.settings.status.voteDayZone)),
                                    ),
                                ),
                            ),
                        )
                    }
                }
            } else {
                add(runtime.locale.render("gui.site-history-unavailable", player))
            }
            add(runtime.locale.render("gui.spacer", player))
            add(runtime.locale.render("gui.site-action", player))
        },
    )

    private fun sendLink(player: Player, runtime: VoteLiveConfiguration, source: MonitoringSource) {
        val presentation = runtime.settings.presentations.getValue(source)
        val site = runtime.locale.site(source, player, presentation.displayName)
            .decorate(TextDecoration.UNDERLINED)
            .clickEvent(ClickEvent.openUrl(presentation.voteUrl.toASCIIString()))
            .hoverEvent(
                HoverEvent.showText(
                    runtime.locale.render(
                        "commands.open-hint",
                        player,
                        mapOf("site" to runtime.locale.site(source, player, presentation.displayName)),
                    ),
                ),
            )
        player.sendMessage(runtime.locale.render("gui.open-message", player, mapOf("site" to site)))
    }

    private fun warnVisual(diagnostic: String) {
        if (warnedVisuals.add(diagnostic)) logger.warning("ArcVotes GUI item fallback: $diagnostic")
    }

    private data class ViewState(
        var histories: Map<MonitoringSource, VoteSiteHistory>,
        var historyAvailable: Boolean,
        var ready: Boolean,
    )

    companion object {
        private val HISTORY_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy 'в' HH:mm")
    }
}
