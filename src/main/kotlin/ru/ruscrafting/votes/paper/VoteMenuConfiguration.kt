package ru.ruscrafting.votes.paper

import ru.arc.config.Config
import ru.arc.config.ConfigManager
import ru.arc.menu.MenuCatalog
import ru.arc.paper.menu.PaperMenuItemTemplate
import ru.arc.paper.menu.PaperMenuConfigurationParser
import ru.ruscrafting.votes.config.VoteMenuSchema
import java.nio.file.Path

data class VoteMenuConfiguration(
    val catalog: MenuCatalog,
    val templates: Map<String, PaperMenuItemTemplate>,
) {
    companion object {
        fun load(dataRoot: Path): VoteMenuConfiguration = parse(ConfigManager.of(dataRoot, "config.yml"))

        fun loadFresh(dataRoot: Path): VoteMenuConfiguration = parse(Config(dataRoot, "config.yml"))

        private fun parse(config: Config): VoteMenuConfiguration {
            val configuration = PaperMenuConfigurationParser.require(
                config,
                "gui.layouts",
                "gui.templates",
                VoteMenuSchema.contracts,
                requiredTemplates = setOf("loading"),
            )
            return VoteMenuConfiguration(configuration.catalog, configuration.templates)
        }
    }
}
