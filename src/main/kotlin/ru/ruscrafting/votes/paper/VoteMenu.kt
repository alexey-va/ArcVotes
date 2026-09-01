package ru.ruscrafting.votes.paper

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.whenCompleteSync
import ru.arc.network.NetworkPlayerName
import ru.ruscrafting.votes.command.VoteMenuOpener
import ru.ruscrafting.votes.config.GuiItemSpec
import ru.ruscrafting.votes.config.MonitoringSource
import ru.ruscrafting.votes.config.PUBLIC_MONITORING_SOURCES
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
    private val clock: Clock = Clock.systemUTC(),
) : VoteMenuOpener, Listener {
    private val windows = VoteWindowPolicy()
    private val warnedVisuals = ConcurrentHashMap.newKeySet<String>()

    override fun open(player: Player) {
        val runtime = live()
        val holder = Holder(player.uniqueId, runtime)
        val inventory = player.server.createInventory(
            holder,
            INVENTORY_SIZE,
            nonItalic(runtime.locale.render("gui.title", player)),
        )
        holder.backing = inventory
        renderLoading(player, holder)
        player.openInventory(inventory)

        val lookup = history
        if (lookup == null) {
            renderSites(player, holder, emptyMap(), historyAvailable = false)
            return
        }
        lookup.findSiteHistory(
            NetworkPlayerName.of(player.name),
            runtime.settings.gui.historyEntriesPerSite,
        ).whenCompleteSync(tasks) { result, failure ->
            if (!holder.isCurrent(player)) return@whenCompleteSync
            if (failure != null) {
                logger.log(Level.WARNING, "Could not load vote history for the player menu", failure)
                renderSites(player, holder, emptyMap(), historyAvailable = false)
            } else {
                renderSites(player, holder, result.orEmpty(), historyAvailable = true)
            }
        }
    }

    @EventHandler
    fun onClick(event: InventoryClickEvent) {
        val holder = event.view.topInventory.holder as? Holder ?: return
        event.isCancelled = true
        if (event.clickedInventory !== event.view.topInventory) return
        val player = event.whoClicked as? Player ?: return
        if (holder.playerId != player.uniqueId || !holder.ready) return
        val source = SOURCES_BY_SLOT[event.rawSlot] ?: return
        sendLink(player, holder.runtime, source)
    }

    @EventHandler
    fun onDrag(event: InventoryDragEvent) {
        if (event.view.topInventory.holder is Holder && event.rawSlots.any { it < INVENTORY_SIZE }) {
            event.isCancelled = true
        }
    }

    private fun renderLoading(player: Player, holder: Holder) {
        fillBackground(holder.backing, holder.runtime)
        SITE_SLOTS.forEach { (source, slot) ->
            val site = holder.runtime.settings.presentations.getValue(source)
            holder.backing.setItem(
                slot,
                item(
                    spec = GuiItemSpec(Material.CLOCK.name),
                    fallback = Material.CLOCK,
                    role = "loading",
                    name = holder.runtime.locale.render(
                        "gui.loading-name",
                        player,
                        mapOf("site" to holder.runtime.locale.site(source, player, site.displayName)),
                    ),
                    lore = holder.runtime.locale.renderLines("gui.loading-lore", player),
                ),
            )
        }
    }

    private fun renderSites(
        player: Player,
        holder: Holder,
        histories: Map<MonitoringSource, VoteSiteHistory>,
        historyAvailable: Boolean,
    ) {
        fillBackground(holder.backing, holder.runtime)
        SITE_SLOTS.forEach { (source, slot) ->
            holder.backing.setItem(
                slot,
                siteItem(player, holder.runtime, source, histories[source], historyAvailable),
            )
        }
        holder.ready = true
    }

    private fun siteItem(
        player: Player,
        runtime: VoteLiveConfiguration,
        source: MonitoringSource,
        history: VoteSiteHistory?,
        historyAvailable: Boolean,
    ): ItemStack {
        val presentation = runtime.settings.presentations.getValue(source)
        val lore = buildList {
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
        }
        val fallback = when (source) {
            MonitoringSource.MINECRAFT_RATING -> Material.GOLD_INGOT
            MonitoringSource.HOTMC -> Material.REDSTONE
            MonitoringSource.MONITORING_MINECRAFT -> Material.EMERALD
            MonitoringSource.GAME_MONITORING -> error("GameMonitoring is not a public menu source")
        }
        return item(
            spec = runtime.settings.gui.sites.getValue(source),
            fallback = fallback,
            role = "site.${source.configKey}",
            name = runtime.locale.site(source, player, presentation.displayName),
            lore = lore,
        )
    }

    private fun fillBackground(inventory: Inventory, runtime: VoteLiveConfiguration) {
        val filler = item(
            spec = runtime.settings.gui.background,
            fallback = Material.GRAY_STAINED_GLASS_PANE,
            role = "background",
            name = Component.empty(),
            lore = emptyList(),
        )
        repeat(inventory.size) { inventory.setItem(it, filler) }
    }

    private fun item(
        spec: GuiItemSpec,
        fallback: Material,
        role: String,
        name: Component,
        lore: List<Component>,
    ): ItemStack {
        val material = Material.matchMaterial(spec.material)?.takeIf(Material::isItem) ?: run {
            if (warnedVisuals.add("$role:${spec.material}")) {
                logger.warning("ArcVotes GUI visual '$role' uses unknown material '${spec.material}'; using ${fallback.name}")
            }
            fallback
        }
        return ItemStack.of(material).also { stack ->
            stack.editMeta { meta ->
                meta.displayName(nonItalic(name))
                meta.lore(lore.map(::nonItalic))
                if (spec.customModelData > 0) {
                    val model = meta.customModelDataComponent
                    model.floats = listOf(spec.customModelData.toFloat())
                    meta.setCustomModelDataComponent(model)
                }
            }
        }
    }

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

    private fun nonItalic(component: Component): Component =
        component.decoration(TextDecoration.ITALIC, TextDecoration.State.FALSE)

    private class Holder(
        val playerId: java.util.UUID,
        val runtime: VoteLiveConfiguration,
    ) : InventoryHolder {
        lateinit var backing: Inventory
        var ready: Boolean = false

        override fun getInventory(): Inventory = backing

        fun isCurrent(player: Player): Boolean =
            player.isOnline && player.uniqueId == playerId && player.openInventory.topInventory === backing
    }

    companion object {
        const val INVENTORY_SIZE = 27
        val SITE_SLOTS: Map<MonitoringSource, Int> = linkedMapOf(
            MonitoringSource.MINECRAFT_RATING to 11,
            MonitoringSource.HOTMC to 13,
            MonitoringSource.MONITORING_MINECRAFT to 15,
        )
        private val SOURCES_BY_SLOT = SITE_SLOTS.entries.associate { (source, slot) -> slot to source }
        private val HISTORY_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy 'в' HH:mm")

        init {
            check(SITE_SLOTS.keys == PUBLIC_MONITORING_SOURCES)
        }
    }
}
