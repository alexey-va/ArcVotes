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
import ru.arc.menu.MenuCatalogRepository
import ru.arc.paper.menu.PaperMenuService
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.votes.config.ArcVotesSettings
import ru.ruscrafting.votes.config.MonitoringSource
import ru.ruscrafting.votes.config.VoteMenuSchema
import ru.ruscrafting.votes.live.VoteLiveConfiguration
import ru.ruscrafting.votes.live.VoteLiveState
import ru.ruscrafting.votes.storage.VoteSiteHistory
import ru.ruscrafting.votes.storage.VoteSiteHistoryLookup
import ru.ruscrafting.votes.text.VoteLocale
import java.nio.file.Files
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.logging.Logger
import kotlin.io.path.readText
import kotlin.io.path.writeText

class VoteMenuMockBukkitTest : StringSpec({
    afterTest { ConfigManager.clear() }

    "vote menu opens immediately, then renders bounded per-site history with nonitalic components" {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("ArcVotesMenuTest")
            val player = paper.addPlayer("VoteTester")
            val root = Files.createTempDirectory("arcvotes-menu")
            val settings = ArcVotesSettings.load(root) { null }
            val menuConfiguration = VoteMenuConfiguration.load(root)
            val locale = VoteLocale(root, { settings.defaultLocale }, { settings.useClientLocale })
            val live = VoteLiveState(VoteLiveConfiguration(settings, locale, null, null, null, false, false))
            val result = CompletableFuture<Map<MonitoringSource, VoteSiteHistory>>()
            var requestedLimit = -1
            val lookup = VoteSiteHistoryLookup { _, limit ->
                requestedLimit = limit
                result
            }
            val tasks = LifecycleTaskScope(BukkitTaskScheduler(plugin))
            val menus = PaperMenuService(plugin, MenuCatalogRepository(menuConfiguration.catalog), BukkitTaskScheduler(plugin))
            val menu = VoteMenu(live::current, tasks, Logger.getAnonymousLogger(), lookup, menus, { menuConfiguration })

            menu.open(player)

            val loading = player.openInventory.topInventory
            loading.size shouldBe 27
            requestedLimit shouldBe 5
            siteSlots(menuConfiguration).values.forEach { slot ->
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
            inventory.size shouldBe loading.size
            inventory.getItem(siteSlots(menuConfiguration).getValue(MonitoringSource.MINECRAFT_RATING))?.type shouldBe
                Material.GOLD_INGOT
            inventory.getItem(siteSlots(menuConfiguration).getValue(MonitoringSource.HOTMC))?.type shouldBe Material.REDSTONE
            inventory.getItem(siteSlots(menuConfiguration).getValue(MonitoringSource.MONITORING_MINECRAFT))?.type shouldBe
                Material.EMERALD
            inventory.assertVisibleComponentsAreNonItalic()
            val ratingLore = inventory.getItem(siteSlots(menuConfiguration).getValue(MonitoringSource.MINECRAFT_RATING)).plainLore()
            ratingLore.joinToString("\n") shouldContain "Всего голосов: 42"
            ratingLore.joinToString("\n") shouldContain "31.08.2026 в 15:17"
            ratingLore.joinToString("\n") shouldContain "30.08.2026 в 23:10"
            inventory.getItem(siteSlots(menuConfiguration).getValue(MonitoringSource.HOTMC)).plainLore()
                .joinToString("\n") shouldContain "Вы ещё не голосовали на этом сайте."
            menus.close()
            tasks.close()
        }
    }

    "vote menu owns its inventory and turns a site click into one clickable chat link" {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("ArcVotesMenuClickTest")
            val player = paper.addPlayer("VoteClicker")
            val root = Files.createTempDirectory("arcvotes-menu-click")
            val settings = ArcVotesSettings.load(root) { null }
            val menuConfiguration = VoteMenuConfiguration.load(root)
            val locale = VoteLocale(root, { settings.defaultLocale }, { settings.useClientLocale })
            val live = VoteLiveState(VoteLiveConfiguration(settings, locale, null, null, null, false, false))
            val tasks = LifecycleTaskScope(BukkitTaskScheduler(plugin))
            val menus = PaperMenuService(plugin, MenuCatalogRepository(menuConfiguration.catalog), BukkitTaskScheduler(plugin))
            val menu = VoteMenu(
                live::current,
                tasks,
                Logger.getAnonymousLogger(),
                VoteSiteHistoryLookup { _, _ -> CompletableFuture.completedFuture(emptyMap()) },
                menus,
                { menuConfiguration },
            )
            menu.open(player)
            paper.performTicks(1)

            click(paper, player, 0).isCancelled shouldBe true
            click(paper, player, siteSlots(menuConfiguration).getValue(MonitoringSource.HOTMC)).isCancelled shouldBe true
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
            menus.close()
            tasks.close()
        }
    }

    "a completed history lookup cannot repaint a menu the player already left" {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("ArcVotesMenuStaleTest")
            val player = paper.addPlayer("VoteStale")
            val root = Files.createTempDirectory("arcvotes-menu-stale")
            val settings = ArcVotesSettings.load(root) { null }
            val menuConfiguration = VoteMenuConfiguration.load(root)
            val locale = VoteLocale(root, { settings.defaultLocale }, { settings.useClientLocale })
            val live = VoteLiveState(VoteLiveConfiguration(settings, locale, null, null, null, false, false))
            val result = CompletableFuture<Map<MonitoringSource, VoteSiteHistory>>()
            val tasks = LifecycleTaskScope(BukkitTaskScheduler(plugin))
            val menus = PaperMenuService(plugin, MenuCatalogRepository(menuConfiguration.catalog), BukkitTaskScheduler(plugin))
            val menu = VoteMenu(
                live::current,
                tasks,
                Logger.getAnonymousLogger(),
                VoteSiteHistoryLookup { _, _ -> result },
                menus,
                { menuConfiguration },
            )
            menu.open(player)
            val unrelated = paper.server.createInventory(null, 9, Component.text("Safe destination"))
            player.openInventory(unrelated)

            result.complete(emptyMap())
            paper.performTicks(1)

            player.openInventory.topInventory shouldBe unrelated
            menus.close()
            tasks.close()
        }
    }

    "layout background and lore changes apply without code changes" {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("ArcVotesConfiguredMenuTest")
            val player = paper.addPlayer("VoteLayout")
            val root = Files.createTempDirectory("arcvotes-menu-layout")
            ArcVotesSettings.load(root) { null }
            root.resolve("config.yml").writeText(
                root.resolve("config.yml").readText()
                    .replace("hotmc: { slot: 13, template: hotmc }", "hotmc: { slot: 10, template: hotmc }")
                    .replace("material: GRAY_STAINED_GLASS_PANE", "material: BLUE_STAINED_GLASS_PANE")
                    .replace("        - '<action>'", "        - '<action>'\n        - '<action>'"),
            )
            val settings = ArcVotesSettings.loadFresh(root) { null }
            val menuConfiguration = VoteMenuConfiguration.loadFresh(root)
            val locale = VoteLocale(root, { settings.defaultLocale }, { settings.useClientLocale })
            val live = VoteLiveState(VoteLiveConfiguration(settings, locale, null, null, null, false, false))
            val tasks = LifecycleTaskScope(BukkitTaskScheduler(plugin))
            val menus = PaperMenuService(plugin, MenuCatalogRepository(menuConfiguration.catalog), BukkitTaskScheduler(plugin))
            val menu = VoteMenu(live::current, tasks, Logger.getAnonymousLogger(), null, menus, { menuConfiguration })

            menu.open(player)

            player.openInventory.topInventory.getItem(13)?.type shouldBe Material.BLUE_STAINED_GLASS_PANE
            player.openInventory.topInventory.getItem(10)?.type shouldBe Material.REDSTONE
            player.openInventory.topInventory.getItem(10).plainLore().count { "Нажмите" in it } shouldBe 2
            click(paper, player, 13).isCancelled shouldBe true
            player.nextComponentMessage() shouldBe null
            click(paper, player, 10).isCancelled shouldBe true
            PlainTextComponentSerializer.plainText().serialize(requireNotNull(player.nextComponentMessage())) shouldContain "HotMC"
            menus.close()
            tasks.close()
        }
    }
})

private fun siteSlots(configuration: VoteMenuConfiguration): Map<MonitoringSource, Int> =
    VoteMenuSchema.elements.mapValues { (_, element) ->
        configuration.catalog.require(VoteMenuSchema.menuId).slot(element).index
    }

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
