package ru.ruscrafting.votes.paper

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryAction
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.inventory.InventoryType
import org.bukkit.inventory.ItemStack
import ru.arc.config.ConfigManager
import ru.arc.core.BukkitTaskScheduler
import ru.arc.core.LifecycleTaskScope
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.votes.config.ArcVotesSettings
import ru.ruscrafting.votes.config.MonitoringSource
import ru.ruscrafting.votes.live.VoteLiveConfiguration
import ru.ruscrafting.votes.live.VoteLiveState
import ru.ruscrafting.votes.storage.VoteSiteHistory
import ru.ruscrafting.votes.storage.VoteSiteHistoryLookup
import ru.ruscrafting.votes.text.VoteLocale
import java.nio.file.Files
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.logging.Logger

class VoteMenuMockBukkitTest : StringSpec({
    afterTest { ConfigManager.clear() }

    "vote menu opens immediately, then renders bounded per-site history with nonitalic components" {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("ArcVotesMenuTest")
            val player = paper.addPlayer("VoteTester")
            val root = Files.createTempDirectory("arcvotes-menu")
            val settings = ArcVotesSettings.load(root) { null }
            val locale = VoteLocale(root, { settings.defaultLocale }, { settings.useClientLocale })
            val live = VoteLiveState(VoteLiveConfiguration(settings, locale, null, null, null, false, false))
            val result = CompletableFuture<Map<MonitoringSource, VoteSiteHistory>>()
            var requestedLimit = -1
            val lookup = VoteSiteHistoryLookup { _, limit ->
                requestedLimit = limit
                result
            }
            val tasks = LifecycleTaskScope(BukkitTaskScheduler(plugin))
            val menu = VoteMenu(live::current, tasks, Logger.getAnonymousLogger(), lookup)
            paper.server.pluginManager.registerEvents(menu, plugin)

            menu.open(player)

            val loading = player.openInventory.topInventory
            loading.size shouldBe 27
            requestedLimit shouldBe 5
            VoteMenu.SITE_SLOTS.values.forEach { slot ->
                loading.getItem(slot)?.type shouldBe Material.CLOCK
            }
            loading.assertVisibleComponentsAreNonItalic()

            result.complete(
                mapOf(
                    MonitoringSource.MINECRAFT_RATING to VoteSiteHistory(
                        totalVotes = 42,
                        recentVotes = listOf(
                            Instant.parse("2026-08-31T12:17:00Z"),
                            Instant.parse("2026-08-30T20:10:00Z"),
                        ),
                    ),
                    MonitoringSource.HOTMC to VoteSiteHistory(totalVotes = 0, recentVotes = emptyList()),
                    MonitoringSource.MONITORING_MINECRAFT to VoteSiteHistory(
                        totalVotes = 7,
                        recentVotes = listOf(Instant.parse("2026-08-29T08:05:00Z")),
                    ),
                ),
            )
            paper.performTicks(1)

            val inventory = player.openInventory.topInventory
            inventory shouldBe loading
            inventory.getItem(VoteMenu.SITE_SLOTS.getValue(MonitoringSource.MINECRAFT_RATING))?.type shouldBe
                Material.GOLD_INGOT
            inventory.getItem(VoteMenu.SITE_SLOTS.getValue(MonitoringSource.HOTMC))?.type shouldBe Material.REDSTONE
            inventory.getItem(VoteMenu.SITE_SLOTS.getValue(MonitoringSource.MONITORING_MINECRAFT))?.type shouldBe
                Material.EMERALD
            inventory.assertVisibleComponentsAreNonItalic()
            val ratingLore = inventory.getItem(VoteMenu.SITE_SLOTS.getValue(MonitoringSource.MINECRAFT_RATING)).plainLore()
            ratingLore.joinToString("\n") shouldContain "Всего голосов: 42"
            ratingLore.joinToString("\n") shouldContain "31.08.2026 в 15:17"
            ratingLore.joinToString("\n") shouldContain "30.08.2026 в 23:10"
            inventory.getItem(VoteMenu.SITE_SLOTS.getValue(MonitoringSource.HOTMC)).plainLore()
                .joinToString("\n") shouldContain "Вы ещё не голосовали на этом сайте."
            tasks.close()
        }
    }

    "vote menu owns its inventory and turns a site click into one clickable chat link" {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("ArcVotesMenuClickTest")
            val player = paper.addPlayer("VoteClicker")
            val root = Files.createTempDirectory("arcvotes-menu-click")
            val settings = ArcVotesSettings.load(root) { null }
            val locale = VoteLocale(root, { settings.defaultLocale }, { settings.useClientLocale })
            val live = VoteLiveState(VoteLiveConfiguration(settings, locale, null, null, null, false, false))
            val tasks = LifecycleTaskScope(BukkitTaskScheduler(plugin))
            val menu = VoteMenu(
                live::current,
                tasks,
                Logger.getAnonymousLogger(),
                VoteSiteHistoryLookup { _, _ -> CompletableFuture.completedFuture(emptyMap()) },
            )
            paper.server.pluginManager.registerEvents(menu, plugin)
            menu.open(player)
            paper.performTicks(1)

            click(paper, player, 0).isCancelled shouldBe true
            click(paper, player, VoteMenu.SITE_SLOTS.getValue(MonitoringSource.HOTMC)).isCancelled shouldBe true
            val message = requireNotNull(player.nextComponentMessage())
            PlainTextComponentSerializer.plainText().serialize(message) shouldContain "HotMC"
            message.descendantsAndSelf().mapNotNull(Component::clickEvent) shouldBe listOf(
                ClickEvent.openUrl(settings.presentations.getValue(MonitoringSource.HOTMC).voteUrl.toASCIIString()),
            )

            paper.callEvent(
                InventoryDragEvent(
                    player.openInventory,
                    ItemStack(Material.DIAMOND),
                    ItemStack(Material.AIR),
                    true,
                    mapOf(0 to ItemStack(Material.DIAMOND)),
                ),
            ).isCancelled shouldBe true

            val unrelated = paper.server.createInventory(null, 9, Component.text("Unrelated"))
            player.openInventory(unrelated)
            click(paper, player, 0).isCancelled shouldBe false
            tasks.close()
        }
    }

    "a completed history lookup cannot repaint a menu the player already left" {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("ArcVotesMenuStaleTest")
            val player = paper.addPlayer("VoteStale")
            val root = Files.createTempDirectory("arcvotes-menu-stale")
            val settings = ArcVotesSettings.load(root) { null }
            val locale = VoteLocale(root, { settings.defaultLocale }, { settings.useClientLocale })
            val live = VoteLiveState(VoteLiveConfiguration(settings, locale, null, null, null, false, false))
            val result = CompletableFuture<Map<MonitoringSource, VoteSiteHistory>>()
            val tasks = LifecycleTaskScope(BukkitTaskScheduler(plugin))
            val menu = VoteMenu(
                live::current,
                tasks,
                Logger.getAnonymousLogger(),
                VoteSiteHistoryLookup { _, _ -> result },
            )
            paper.server.pluginManager.registerEvents(menu, plugin)
            menu.open(player)
            val unrelated = paper.server.createInventory(null, 9, Component.text("Safe destination"))
            player.openInventory(unrelated)

            result.complete(emptyMap())
            paper.performTicks(1)

            player.openInventory.topInventory shouldBe unrelated
            tasks.close()
        }
    }
})

private fun click(
    paper: MockBukkitTestRuntime,
    player: Player,
    slot: Int,
): InventoryClickEvent = paper.callEvent(
    InventoryClickEvent(
        player.openInventory,
        InventoryType.SlotType.CONTAINER,
        slot,
        ClickType.LEFT,
        InventoryAction.PICKUP_ALL,
    ),
)

private fun org.bukkit.inventory.Inventory.assertVisibleComponentsAreNonItalic() {
    contents.filterNotNull().forEach { item ->
        val meta = item.itemMeta
        meta.displayName()?.decoration(TextDecoration.ITALIC) shouldBe TextDecoration.State.FALSE
        meta.lore().orEmpty().forEach { line ->
            line.decoration(TextDecoration.ITALIC) shouldBe TextDecoration.State.FALSE
        }
    }
}

private fun ItemStack?.plainLore(): List<String> = this?.itemMeta?.lore().orEmpty().map {
    PlainTextComponentSerializer.plainText().serialize(it)
}

private fun Component.descendantsAndSelf(): List<Component> =
    listOf(this) + children().flatMap(Component::descendantsAndSelf)
