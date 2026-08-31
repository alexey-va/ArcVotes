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
import ru.ruscrafting.votes.callback.VoteIngressCounters
import ru.ruscrafting.votes.callback.VoteIngressService
import ru.ruscrafting.votes.command.ArcVotesAdminCommand
import ru.ruscrafting.votes.command.VoteCommand
import ru.ruscrafting.votes.config.ArcVotesSettings
import ru.ruscrafting.votes.domain.RewardProvider
import ru.ruscrafting.votes.live.VoteLiveConfiguration
import ru.ruscrafting.votes.live.VoteLiveState
import ru.ruscrafting.votes.reload.ArcVotesReloadController
import ru.ruscrafting.votes.reload.VoteRewardRuntime
import ru.ruscrafting.votes.reload.VoteRewardRuntimeFactory
import ru.ruscrafting.votes.reward.VoteRewardService
import ru.ruscrafting.votes.reward.VaultRedisEconomyRewardDepositor
import ru.ruscrafting.votes.storage.MySqlVoteRepository
import ru.ruscrafting.votes.status.VoteDailyStatusService
import ru.ruscrafting.votes.text.VoteLocale
import java.nio.file.Files
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Level

class ArcVotesPlugin : JavaPlugin() {
    private var lifecycle: PaperPluginRuntime? = null
    private var sqlRuntime: SqlRuntime? = null
    private val sqlReady = AtomicBoolean(false)
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
            val dailyStatus = repository?.let {
                VoteDailyStatusService(
                    history = it,
                    cacheTtl = Duration.ofSeconds(settings.status.cacheTtlSeconds),
                    maximumCacheEntries = settings.status.maximumCacheEntries,
                    voteDayZone = settings.status.voteDayZone,
                )
            }
            val rewardRuntimeFactory = VoteRewardRuntimeFactory(::createRewardRuntime)
            val rewardRuntime = rewardRuntimeFactory.create(settings)
            val live = VoteLiveState(
                VoteLiveConfiguration(
                    settings = settings,
                    locale = locale,
                    dailyStatus = dailyStatus,
                    ingress = null,
                    rewardDepositor = rewardRuntime.depositor,
                    vaultReady = rewardRuntime.vaultReady,
                    redisEconomyReady = rewardRuntime.redisEconomyReady,
                ),
            )

            val rewardService = repository?.let { storage ->
                val sql = requireNotNull(sqlRuntime)
                val ledger = runtime.own(
                    MySqlOneTimeUseLedger.attach(
                        runtime = sql,
                        runtimeName = "arc-votes-${settings.serverId}",
                        partition = MySqlOneTimeUsePartition("vote_reward"),
                    ),
                )
                runtime.own(
                    VoteRewardService(
                        server = server,
                        tasks = runtime.tasks,
                        repository = storage,
                        ledger = ledger,
                        live = live::current,
                        logger = logger,
                    ),
                ).also {
                    server.pluginManager.registerEvents(it, this)
                    it.start()
                }
            }

            val ingressCounters = VoteIngressCounters()
            val ingress = repository?.let { storage ->
                VoteIngressService(
                    settings = settings,
                    repository = storage,
                    logger = logger,
                    onDurableEvent = { event -> rewardService?.onDurableEvent(event) },
                    counters = ingressCounters,
                )
            }
            live.publish(live.current().copy(ingress = ingress))

            val reloadController = runtime.own(
                ArcVotesReloadController(
                    dataRoot = dataPath,
                    live = live,
                    repository = repository,
                    history = repository,
                    rewardService = rewardService,
                    rewardRuntimeFactory = rewardRuntimeFactory,
                    logger = logger,
                    ingressCounters = ingressCounters,
                ),
            ).also(ArcVotesReloadController::startInitialHttp)

            val voteCommand = VoteCommand(live::current, runtime.tasks, logger, repository)
            requireNotNull(getCommand("vote")).apply {
                setExecutor(voteCommand)
                tabCompleter = voteCommand
            }
            val adminCommand = ArcVotesAdminCommand(live::current, reloadController)
            requireNotNull(getCommand("arcvotes")).apply {
                setExecutor(adminCommand)
                tabCompleter = adminCommand
            }
            installHealth(runtime, live, reloadController)
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
        ConfigManager.clear()
        Tasks.reset()
    }

    private fun installHealth(
        runtime: PaperPluginRuntime,
        live: VoteLiveState,
        reloadController: ArcVotesReloadController,
    ) {
        runtime.registerHealth("votes") {
            val current = live.current()
            val settings = current.settings
            val mysql = settings.sql == null || sqlReady.get()
            val http = !settings.http.enabled || reloadController.isHttpReady()
            val vault = !settings.reward.enabled || current.vaultReady
            val redisEconomy = !settings.reward.enabled || current.redisEconomyReady
            RuntimeHealthContribution(
                state = if (mysql && http && vault && redisEconomy) RuntimeHealthState.UP else RuntimeHealthState.DOWN,
                schemas = mapOf("votes" to 4),
                dependencies = mapOf(
                    "mysql" to mysql,
                    "callback_http" to http,
                    "vault" to vault,
                    "redis_economy" to redisEconomy,
                ),
            )
        }
    }

    private fun createRewardRuntime(settings: ArcVotesSettings): VoteRewardRuntime {
        if (!settings.reward.enabled) return VoteRewardRuntime(null, vaultReady = true, redisEconomyReady = true)
        val reward = requireNotNull(settings.reward.bundle)
        val requiresVault = reward.components.any { it.provider == RewardProvider.VAULT }
        val requiresRedisEconomy = reward.components.any { it.provider == RewardProvider.REDIS_ECONOMY }
        val economy = server.servicesManager.getRegistration(Economy::class.java)?.provider
        require(!requiresVault || economy != null) { "Vault economy service is required for standard vote rewards" }
        val redisEconomyClassLoader = server.pluginManager.getPlugin("RedisEconomy")?.javaClass?.classLoader
        require(!requiresRedisEconomy || redisEconomyClassLoader != null) {
            "RedisEconomy plugin is required for premium vote rewards"
        }
        return VoteRewardRuntime(
            depositor = VaultRedisEconomyRewardDepositor.create(
                vault = economy,
                redisEconomyClassLoader = redisEconomyClassLoader,
                reward = reward,
            ),
            vaultReady = !requiresVault || economy != null,
            redisEconomyReady = !requiresRedisEconomy || redisEconomyClassLoader != null,
        )
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
        ArcVotesSettings.mergeDefaults(dataPath)
        listOf("lang/ru.yml", "lang/en.yml").forEach { resource ->
            ConfigManager.of(dataPath, resource).mergeMissingFromBundled(resource)
        }
    }

    private companion object {
        const val STARTUP_TIMEOUT_SECONDS = 30L
        const val HEALTH_REPORT_TICKS = 1_200L
    }
}
