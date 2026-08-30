package ru.ruscrafting.votes.paper

import net.milkbowl.vault.economy.Economy
import org.bukkit.plugin.java.JavaPlugin
import ru.arc.config.ConfigManager
import ru.arc.core.PaperArcRuntime
import ru.arc.core.Tasks
import ru.arc.logging.ArcLogging
import ru.arc.logging.LoggingConfigSource
import ru.arc.logging.LoggingModuleConfig
import ru.arc.logging.LokiAttachTarget
import ru.arc.logging.LokiInstallSpec
import ru.arc.logging.paper.PaperLoggingPlatform
import ru.arc.observability.RuntimeHealthContribution
import ru.arc.observability.RuntimeHealthState
import ru.arc.observability.StructuredDebugLine
import ru.arc.paper.runtime.PaperPluginRuntime
import ru.arc.sql.SqlRuntime
import ru.arc.sql.onetime.MySqlOneTimeUseLedger
import ru.arc.sql.onetime.MySqlOneTimeUsePartition
import ru.ruscrafting.votes.callback.ArcVoteHttpServer
import ru.ruscrafting.votes.callback.VoteIngressService
import ru.ruscrafting.votes.command.VoteCommand
import ru.ruscrafting.votes.config.ArcVotesSettings
import ru.ruscrafting.votes.domain.RewardProvider
import ru.ruscrafting.votes.reward.VoteRewardService
import ru.ruscrafting.votes.reward.VaultRedisEconomyRewardDepositor
import ru.ruscrafting.votes.storage.MySqlVoteRepository
import ru.ruscrafting.votes.text.VoteLocale
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Level

class ArcVotesPlugin : JavaPlugin() {
    private var lifecycle: PaperPluginRuntime? = null
    private var sqlRuntime: SqlRuntime? = null
    private val sqlReady = AtomicBoolean(false)
    private val httpReady = AtomicBoolean(false)
    private val vaultReady = AtomicBoolean(false)
    private val redisEconomyReady = AtomicBoolean(false)
    private val debug = StructuredDebugLine("ARCVOTES_EVENT")

    override fun onEnable() {
        saveDefaultConfig()
        saveResourceIfMissing("lang/ru.yml")
        saveResourceIfMissing("lang/en.yml")
        PaperArcRuntime.installScheduling(this)
        try {
            mergeBundledDefaults()
            val settings = ArcVotesSettings.load(dataPath)
            val locale = VoteLocale(dataPath, { settings.defaultLocale }, { settings.useClientLocale }).also { it.validate() }
            installLogging(settings)

            val runtime = PaperPluginRuntime(this, "arc-votes").also {
                lifecycle = it
                it.start("version" to pluginMeta.version, "server" to settings.serverId)
            }
            val repository = settings.sql?.let { sqlConfig ->
                val sql = runtime.own(SqlRuntime.create(sqlConfig, "arc-votes-${settings.serverId}")).also { sqlRuntime = it }
                MySqlVoteRepository(sql).also { repository ->
                    repository.initialize().get(STARTUP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    sqlReady.set(true)
                }
            }

            val rewardService = if (settings.reward.enabled) {
                val storage = requireNotNull(repository) { "Vote reward storage is unavailable" }
                val reward = requireNotNull(settings.reward.bundle)
                val requiresVault = reward.components.any { it.provider == RewardProvider.VAULT }
                val economy = server.servicesManager.getRegistration(Economy::class.java)?.provider
                require(!requiresVault || economy != null) { "Vault economy service is required for standard vote rewards" }
                vaultReady.set(true)
                val redisEconomyClassLoader = if (reward.components.any { it.provider == RewardProvider.REDIS_ECONOMY }) {
                    requireNotNull(server.pluginManager.getPlugin("RedisEconomy")) {
                        "RedisEconomy plugin is required for premium vote rewards"
                    }.javaClass.classLoader
                } else null
                val depositor = VaultRedisEconomyRewardDepositor.create(
                    vault = economy,
                    redisEconomyClassLoader = redisEconomyClassLoader,
                    reward = reward,
                )
                redisEconomyReady.set(true)
                val sql = requireNotNull(sqlRuntime)
                val ledger = runtime.own(
                    MySqlOneTimeUseLedger.attach(
                        runtime = sql,
                        runtimeName = "arc-votes-${settings.serverId}",
                        partition = MySqlOneTimeUsePartition("vote_reward"),
                    ),
                )
                VoteRewardService(server, runtime.tasks, storage, ledger, depositor, settings, locale, logger).also {
                    server.pluginManager.registerEvents(it, this)
                    it.start()
                }
            } else null

            val ingress = if (settings.http.enabled) {
                val storage = requireNotNull(repository) { "Callback storage is unavailable" }
                VoteIngressService(settings, storage, logger) { event -> rewardService?.onDurableEvent(event) }.also { service ->
                    val http = runtime.own(ArcVoteHttpServer(settings.http, service, logger))
                    http.start()
                    httpReady.set(true)
                }
            } else null

            val voteCommand = VoteCommand(settings, locale, ingress)
            requireNotNull(getCommand("vote")).apply {
                setExecutor(voteCommand)
                tabCompleter = voteCommand
            }
            installHealth(runtime, settings)
            runtime.ready(
                "server" to settings.serverId,
                "http" to settings.http.enabled,
                "mysql" to (settings.sql != null),
                "rewards" to settings.reward.enabled,
                "sources" to settings.enabledSources.size,
            )
            runtime.reportHealthEvery(HEALTH_REPORT_TICKS)
            logger.info(
                debug.line(
                    "state" to "ready",
                    "server" to settings.serverId,
                    "http" to settings.http.enabled,
                    "rewards" to settings.reward.enabled,
                    "sources" to settings.enabledSources.size,
                ),
            )
        } catch (failure: Throwable) {
            runCatching { lifecycle?.health?.markDown(); lifecycle?.emitHealth() }
            logger.log(Level.SEVERE, "ArcVotes failed closed during startup", failure)
            server.pluginManager.disablePlugin(this)
        }
    }

    override fun onDisable() {
        runCatching { lifecycle?.close() }
            .onFailure { logger.log(Level.WARNING, "Could not close every ArcVotes resource", it) }
        lifecycle = null
        sqlRuntime = null
        sqlReady.set(false)
        httpReady.set(false)
        vaultReady.set(false)
        redisEconomyReady.set(false)
        ConfigManager.clear()
        Tasks.reset()
    }

    private fun installHealth(runtime: PaperPluginRuntime, settings: ArcVotesSettings) {
        runtime.registerHealth("votes") {
            val mysql = settings.sql == null || sqlReady.get()
            val http = !settings.http.enabled || httpReady.get()
            val vault = !settings.reward.enabled || vaultReady.get()
            val redisEconomy = !settings.reward.enabled || redisEconomyReady.get()
            RuntimeHealthContribution(
                state = if (mysql && http && vault && redisEconomy) RuntimeHealthState.UP else RuntimeHealthState.DOWN,
                schemas = mapOf("votes" to 3),
                dependencies = mapOf(
                    "mysql" to mysql,
                    "callback_http" to http,
                    "vault" to vault,
                    "redis_economy" to redisEconomy,
                ),
            )
        }
    }

    private fun installLogging(settings: ArcVotesSettings) {
        val path = ConfigManager.moduleYamlPath(dataPath, LoggingModuleConfig.RESOURCE)
        val existed = Files.isRegularFile(path)
        val logging = ConfigManager.ofModule(dataPath, LoggingModuleConfig.RESOURCE)
        if (!existed) {
            logging.setString("labels.service_name", "arc-votes-${settings.serverId}")
            logging.setString("labels.job", "paper")
            logging.saveStrict()
        }
        ArcLogging.install(
            platform = PaperLoggingPlatform("ArcVotes", "ArcVotes"),
            configSource = object : LoggingConfigSource {
                override fun config() = logging
                override fun configVersion(): Int = ConfigManager.getVersion()
            },
            loki = LokiInstallSpec(
                dataFolder = dataPath,
                target = LokiAttachTarget.LOGGER_PREFIX,
                loggerPrefix = "ru.ruscrafting.votes",
                appenderName = "ArcVotesLokiAppender",
            ),
        )
    }

    private fun saveResourceIfMissing(path: String) {
        if (!Files.isRegularFile(dataPath.resolve(path))) saveResource(path, false)
    }

    private fun mergeBundledDefaults() {
        listOf("config.yml", "lang/ru.yml", "lang/en.yml").forEach { resource ->
            ConfigManager.of(dataPath, resource).mergeMissingFromBundled(resource)
        }
    }

    private companion object {
        const val STARTUP_TIMEOUT_SECONDS = 30L
        const val HEALTH_REPORT_TICKS = 1_200L
    }
}
