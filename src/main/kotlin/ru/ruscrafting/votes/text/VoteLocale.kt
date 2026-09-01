package ru.ruscrafting.votes.text

import net.kyori.adventure.text.Component
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import ru.arc.config.Config
import ru.arc.config.ConfigManager
import ru.arc.text.ConfigLocaleCatalog
import ru.arc.text.LocaleRequirements
import ru.arc.text.LocalizedMiniMessage
import ru.ruscrafting.votes.config.MonitoringSource
import java.nio.file.Path

class VoteLocale private constructor(
    dataRoot: Path,
    private val defaultLocale: () -> String,
    private val useClientLocale: () -> Boolean,
    config: (String) -> Config,
) {
    private val renderer = LocalizedMiniMessage(
        catalogs = mapOf(
            "ru" to ConfigLocaleCatalog(config("lang/ru.yml")),
            "en" to ConfigLocaleCatalog(config("lang/en.yml")),
        ),
        defaultLocale = defaultLocale,
    )

    constructor(
        dataRoot: Path,
        defaultLocale: () -> String,
        useClientLocale: () -> Boolean,
    ) : this(dataRoot, defaultLocale, useClientLocale, { path -> ConfigManager.of(dataRoot, path) })

    fun render(
        path: String,
        audience: CommandSender? = null,
        values: Map<String, Component> = emptyMap(),
    ): Component = renderer.render(path, localeTag(audience), values)

    fun renderLines(
        path: String,
        audience: CommandSender? = null,
        values: Map<String, Component> = emptyMap(),
    ): List<Component> = renderer.renderLines(path, localeTag(audience), values)

    fun text(value: Any?): Component = renderer.literal(value)

    fun site(source: MonitoringSource, audience: CommandSender?, displayName: String): Component = render(
        "sites.${source.configKey}",
        audience,
        mapOf("name" to text(displayName)),
    )

    fun validate() {
        renderer.validate(
            LocaleRequirements(
                scalarPaths = setOf(
                    "prefix",
                    "sites.minecraft-rating",
                    "sites.hotmc",
                    "sites.monitoring-minecraft",
                    "sites.game-monitoring",
                    "commands.open-hint",
                    "commands.reward-note",
                    "commands.vote-state-voted",
                    "commands.vote-state-open",
                    "commands.no-permission",
                    "commands.help",
                    "commands.state-enabled",
                    "commands.state-disabled",
                    "commands.history-row",
                    "commands.history-state-granted",
                    "commands.history-state-pending",
                    "commands.history-state-recovery",
                    "commands.history-state-none",
                    "commands.history-previous",
                    "commands.history-previous-disabled",
                    "commands.history-next",
                    "commands.history-next-disabled",
                    "commands.history-navigation",
                    "commands.history-page-hint",
                    "commands.history-empty",
                    "commands.player-invalid",
                    "commands.storage-unavailable",
                    "commands.lookup-failed",
                    "commands.page-invalid",
                    "commands.page-not-found",
                    "commands.reload-success",
                    "commands.reload-failed",
                    "commands.reload-restart-required",
                    "commands.reload-busy",
                    "commands.reload-help",
                    "reward.component-standard",
                    "reward.component-premium",
                    "reward.component-spacing",
                ),
                listPaths = setOf(
                    "commands.vote-suggestions",
                    "commands.admin-help",
                    "commands.status",
                    "commands.status-ingress",
                    "commands.counters",
                    "commands.player-check",
                    "commands.history-header",
                    "reward.granted",
                ),
            ),
        )
    }

    private fun localeTag(audience: CommandSender?): String =
        if (useClientLocale() && audience is Player) audience.locale().toLanguageTag() else defaultLocale()

    companion object {
        fun mergeDefaults(dataRoot: Path) {
            listOf("ru", "en").forEach { language ->
                val resource = "lang/$language.yml"
                ConfigManager.of(dataRoot, resource).mergeMissingFromBundled(resource)
            }
        }

        /** Reads isolated Config instances so a rejected reload cannot mutate the live renderer. */
        fun fresh(
            dataRoot: Path,
            defaultLocale: () -> String,
            useClientLocale: () -> Boolean,
        ): VoteLocale = VoteLocale(dataRoot, defaultLocale, useClientLocale, { path -> Config(dataRoot, path) })
    }
}
