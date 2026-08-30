package ru.ruscrafting.votes.text

import net.kyori.adventure.text.Component
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import ru.arc.config.ConfigManager
import ru.arc.text.ConfigLocaleCatalog
import ru.arc.text.LocaleRequirements
import ru.arc.text.LocalizedMiniMessage
import java.nio.file.Path

class VoteLocale(
    dataRoot: Path,
    private val defaultLocale: () -> String,
    private val useClientLocale: () -> Boolean,
) {
    private val renderer = LocalizedMiniMessage(
        catalogs = mapOf(
            "ru" to ConfigLocaleCatalog(ConfigManager.of(dataRoot, "lang/ru.yml")),
            "en" to ConfigLocaleCatalog(ConfigManager.of(dataRoot, "lang/en.yml")),
        ),
        defaultLocale = defaultLocale,
    )

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

    fun validate() {
        renderer.validate(
            LocaleRequirements(
                scalarPaths = setOf(
                    "prefix",
                    "commands.open-hint",
                    "commands.reward-note",
                    "commands.no-permission",
                    "commands.help",
                    "commands.state-enabled",
                    "commands.state-disabled",
                    "reward.granted",
                ),
                listPaths = setOf(
                    "commands.vote-list",
                    "commands.status",
                    "commands.counters",
                ),
            ),
        )
    }

    private fun localeTag(audience: CommandSender?): String =
        if (useClientLocale() && audience is Player) audience.locale().toLanguageTag() else defaultLocale()
}
