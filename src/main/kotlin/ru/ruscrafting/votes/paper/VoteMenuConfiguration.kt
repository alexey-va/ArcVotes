package ru.ruscrafting.votes.paper

import ru.arc.config.Config
import ru.arc.config.ConfigManager
import ru.arc.menu.MenuCatalog
import ru.arc.menu.MenuLayoutParser
import ru.arc.paper.menu.PaperMenuItemTemplate
import ru.arc.paper.menu.PaperMenuItemTemplateParser
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
            val catalog = MenuLayoutParser.require(config, "gui.layouts", VoteMenuSchema.contracts)
            val templates = PaperMenuItemTemplateParser.require(config, "gui.templates")
            val referenced = catalog.layouts.values.flatMap { layout ->
                listOfNotNull(layout.backgroundTemplate?.value) +
                    layout.elements.values.mapNotNull { it.template?.value }
            }.toSet()
            val missing = referenced - templates.keys
            require(missing.isEmpty()) { "GUI layouts reference missing templates: ${missing.sorted()}" }
            require("loading" in templates) { "GUI template 'loading' is required" }
            return VoteMenuConfiguration(catalog, templates)
        }
    }
}
