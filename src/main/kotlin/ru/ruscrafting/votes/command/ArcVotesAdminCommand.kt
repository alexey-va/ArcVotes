package ru.ruscrafting.votes.command

import net.kyori.adventure.text.Component
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import ru.ruscrafting.votes.live.VoteLiveConfiguration
import ru.ruscrafting.votes.reload.ArcVotesReloader
import ru.ruscrafting.votes.reload.ArcVotesReloadResult

class ArcVotesAdminCommand(
    private val live: () -> VoteLiveConfiguration,
    private val reloader: ArcVotesReloader,
) : CommandExecutor, TabCompleter {
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        val runtime = live()
        if (!sender.hasPermission(RELOAD_PERMISSION)) {
            sender.sendMessage(runtime.locale.render("commands.no-permission", sender))
            return true
        }
        if (args.size != 1 || !args[0].equals("reload", ignoreCase = true)) {
            sender.sendMessage(runtime.locale.render("commands.reload-help", sender))
            return true
        }

        when (val result = runCatching(reloader::reload).getOrDefault(ArcVotesReloadResult.Failed)) {
            ArcVotesReloadResult.Applied -> {
                // The reloader publishes before returning Applied, so render with the fresh locale.
                sender.sendMessage(live().locale.render("commands.reload-success", sender))
            }
            ArcVotesReloadResult.Busy -> sender.sendMessage(runtime.locale.render("commands.reload-busy", sender))
            ArcVotesReloadResult.Failed -> sender.sendMessage(runtime.locale.render("commands.reload-failed", sender))
            is ArcVotesReloadResult.RestartRequired -> sender.sendMessage(
                runtime.locale.render(
                    "commands.reload-restart-required",
                    sender,
                    mapOf("fields" to Component.text(safeRestartFields(result.fields))),
                ),
            )
        }
        return true
    }

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        alias: String,
        args: Array<out String>,
    ): List<String> = if (
        args.size == 1 && sender.hasPermission(RELOAD_PERMISSION) && "reload".startsWith(args[0], ignoreCase = true)
    ) {
        listOf("reload")
    } else {
        emptyList()
    }

    private fun safeRestartFields(fields: List<String>): String = fields
        .asSequence()
        .filter(RESTART_FIELD_IDS::contains)
        .distinct()
        .joinToString(", ")
        .takeIf(String::isNotBlank)
        ?.let { ": $it" }
        .orEmpty()

    private companion object {
        const val RELOAD_PERMISSION = "arcvotes.admin.reload"

        val RESTART_FIELD_IDS = setOf(
            "server-id",
            "mysql",
            "http.bind-address",
            "http.port",
            "http.worker-threads",
            "http.queue-capacity",
        )
    }
}
