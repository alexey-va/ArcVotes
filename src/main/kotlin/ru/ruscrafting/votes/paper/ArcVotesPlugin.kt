package ru.ruscrafting.votes.paper

import net.milkbowl.vault.economy.Economy
import org.bukkit.plugin.java.JavaPlugin
import ru.arc.config.ConfigManager
import ru.arc.core.BoundedAsyncExecutor
import ru.arc.core.BudgetedTaskQueue
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.BukkitTaskScheduler
import ru.arc.core.PaperArcRuntime
import ru.arc.core.Tasks
import ru.arc.core.whenCompleteSync
import ru.arc.logging.ArcLogging
import ru.arc.logging.LoggingConfigSource
import ru.arc.logging.LoggingModuleConfig
import ru.arc.logging.LokiAttachTarget
import ru.arc.logging.LokiInstallSpec
import ru.arc.logging.paper.PaperLoggingPlatform
import ru.arc.menu.MenuCatalogRepository
import ru.arc.observability.RuntimeHealthContribution
import ru.arc.observability.RuntimeHealthState
import ru.arc.observability.StructuredDebugLine
import ru.arc.paper.runtime.PaperPluginRuntime
import ru.arc.paper.sql.PaperSqlServices
import ru.arc.paper.menu.PaperMenuService
import ru.arc.sql.SqlRuntime
import ru.arc.sql.onetime.MySqlOneTimeUseLedger
import ru.arc.sql.onetime.MySqlOneTimeUsePartition
import ru.ruscrafting.votes.callback.VoteIngressCounters
import ru.ruscrafting.votes.callback.VoteIngressService
import ru.ruscrafting.votes.api.VoteConfirmedEvent
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
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Level

open class ArcVotesPlugin : JavaPlugin() {
    private var lifecycle: PaperPluginRuntime? = null
    private class StorageGeneration(val worker: BoundedAsyncExecutor, val callbacks: LifecycleTaskScope) {
        val nativeWork = BudgetedTaskQueue(callbacks)
        val drain = CompletableFuture<Unit>()
        @Volatile var sql: SqlRuntime? = null
        @Volatile var ledger: MySqlOneTimeUseLedger? = null
        var reloadController: ArcVotesReloadController? = null
    }
    private var generation: StorageGeneration? = null
    private var predecessor = CompletableFuture.completedFuture(Unit)
    internal fun drained(): CompletableFuture<Unit> = (generation?.drain ?: predecessor).copy()

    private class Prepared(
        val settings: ArcVotesSettings,
        val locale: VoteLocale,
        val menu: VoteMenuConfiguration,
        val repository: MySqlVoteRepository?,
        val ledger: MySqlOneTimeUseLedger?,
    )
    private val sqlReady = AtomicBoolean(false)
    private val debug = StructuredDebugLine("ARCVOTES_EVENT")

    override fun onEnable() {
        val runtime = PaperPluginRuntime(this, "arc-votes", BukkitTaskScheduler(this)).also {
            lifecycle = it
            it.start("version" to pluginMeta.version)
        }
        predecessor.whenCompleteSync(runtime.tasks) { _, failure ->
            if (lifecycle !== runtime) return@whenCompleteSync
            if (failure != null) failStartup(failure) else beginGeneration(runtime)
        }
    }

    private fun beginGeneration(runtime: PaperPluginRuntime) {
        try {
            PaperArcRuntime.installScheduling(this)
            val owner = StorageGeneration(BoundedAsyncExecutor("arc-votes-lifecycle"), LifecycleTaskScope(BukkitTaskScheduler(this)))
            generation = owner
            runtime.own(PaperSqlServices.installConsumer(this, owner.drain))
            prepare(owner).whenCompleteSync(runtime.tasks) { prepared, failure ->
                if (generation !== owner || lifecycle !== runtime) return@whenCompleteSync
                if (failure != null || prepared == null) failStartup(failure ?: IllegalStateException("Missing vote configuration"))
                else runCatching { finishEnable(runtime, owner, prepared) }.onFailure(::failStartup)
            }
        } catch (failure: Throwable) { failStartup(failure) }
    }

    private fun prepare(owner: StorageGeneration): CompletableFuture<Prepared> = owner.worker.submit {
            saveDefaultConfig()
            saveResourceIfMissing("lang/ru.yml")
            saveResourceIfMissing("lang/en.yml")
            mergeBundledDefaults()
            val settings = ArcVotesSettings.loadFresh(dataPath)
            val locale = VoteLocale.fresh(dataPath, { settings.defaultLocale }, { settings.useClientLocale }).also { it.validate() }
            installLogging(settings)
            val menu = VoteMenuConfiguration.loadFresh(dataPath)
            val repository = settings.sql?.let { sqlConfig ->
                val sql = SqlRuntime.create(sqlConfig, "arc-votes-${settings.serverId}").also { owner.sql = it }
                MySqlVoteRepository(sql).also { repository ->
                    repository.initialize().get(STARTUP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                }
            }
            val ledger = owner.sql?.let { sql ->
                MySqlOneTimeUseLedger.attach(sql, "arc-votes-${settings.serverId}", MySqlOneTimeUsePartition("vote_reward"))
                    .also { owner.ledger = it }
            }
            Prepared(settings, locale, menu, repository, ledger)
    }

    private fun finishEnable(runtime: PaperPluginRuntime, owner: StorageGeneration, prepared: Prepared) {
        val storage = owner.worker
        val nativeWork = owner.nativeWork
        val settings = prepared.settings
        val locale = prepared.locale
        val repository = prepared.repository
        sqlReady.set(repository != null)
        val dailyStatus = repository?.let {
            VoteDailyStatusService(
                history = it,
                cacheTtl = Duration.ofSeconds(settings.status.cacheTtlSeconds),
                maximumCacheEntries = settings.status.maximumCacheEntries,
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
            val ledger = requireNotNull(prepared.ledger)
            runtime.own(
                VoteRewardService(
                    server = server,
                    tasks = runtime.tasks,
                    repository = storage,
                    ledger = ledger,
                    live = live::current,
                    logger = logger,
                    nativeWork = nativeWork,
                    onConfirmedEvent = ::publishConfirmedVote,
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
                onDurableEvent = { event ->
                    rewardService?.onDurableEvent(event)
                    publishConfirmedVote(runtime, event)
                },
                counters = ingressCounters,
            )
        }
        live.publish(live.current().copy(ingress = ingress))

        var menuConfiguration = prepared.menu
        val menuCatalogs = MenuCatalogRepository(menuConfiguration.catalog)
        val menuService = runtime.own(
            PaperMenuService(this, menuCatalogs, BukkitTaskScheduler(this)),
        )

        val activeReloadController = runtime.own(
            ArcVotesReloadController(
                dataRoot = dataPath,
                live = live,
                repository = repository,
                history = repository,
                rewardService = rewardService,
                rewardRuntimeFactory = rewardRuntimeFactory,
                logger = logger,
                storage = storage,
                nativeWork = nativeWork,
                ingressCounters = ingressCounters,
                onDurableEvent = { event -> publishConfirmedVote(runtime, event) },
                loadMenuCandidate = { VoteMenuConfiguration.loadFresh(dataPath) },
                applyMenuCandidate = { candidate ->
                    menuCatalogs.replace(candidate.catalog)
                    menuConfiguration = candidate
                    menuService.closeSessions()
                },
            ),
        ).also { owner.reloadController = it }
        logger.info("ARCVOTES_EVENT public confirmation API enabled (VoteConfirmedEvent)")

        val voteMenu = VoteMenu(
            live::current,
            runtime.tasks,
            logger,
            repository,
            menuService,
            { menuConfiguration },
        )
        val voteCommand = VoteCommand(live::current, runtime.tasks, logger, repository, voteMenu)
        requireNotNull(getCommand("vote")).apply {
            setExecutor(voteCommand)
            tabCompleter = voteCommand
        }
        val adminCommand = ArcVotesAdminCommand(live::current, activeReloadController)
        requireNotNull(getCommand("arcvotes")).apply {
            setExecutor(adminCommand)
            tabCompleter = adminCommand
        }
        installHealth(runtime, live, activeReloadController)
        activeReloadController.startInitialHttp().whenComplete { _, failure ->
            nativeWork.call({ generation === owner && lifecycle === runtime }) {
                if (failure != null) failStartup(failure)
                else {
                    runtime.ready(
                        "server" to settings.serverId, "http" to settings.http.enabled,
                        "mysql" to (settings.sql != null), "rewards" to settings.reward.enabled,
                        "sources" to settings.enabledSources.size,
                    )
                    runtime.reportHealthEvery(HEALTH_REPORT_TICKS)
                    logger.info(debug.line("state" to "ready", "server" to settings.serverId,
                        "http" to settings.http.enabled, "rewards" to settings.reward.enabled,
                        "sources" to settings.enabledSources.size))
                }
            }
        }

    }

    private fun failStartup(failure: Throwable) {
        runCatching { lifecycle?.health?.markDown(); lifecycle?.emitHealth() }
        logger.log(Level.SEVERE, "ArcVotes failed closed during startup", failure)
        server.pluginManager.disablePlugin(this)
    }

    override fun onDisable() {
        val owner = generation
        generation = null
        if (owner != null) predecessor = owner.drain
        val runtime = lifecycle
        lifecycle = null
        var failure: Throwable? = null
        fun attempt(close: () -> Unit) {
            try { close() } catch (error: Throwable) {
                if (failure == null) failure = error else failure?.addSuppressed(error)
            }
        }
        attempt { owner?.nativeWork?.close() }
        attempt { owner?.callbacks?.close() }
        attempt { runtime?.close() }
        if (owner != null) closeStorage(owner, failure)
        sqlReady.set(false)
        ConfigManager.clear()
        Tasks.reset()
    }

    private fun closeStorage(owner: StorageGeneration, nativeFailure: Throwable?) {
        owner.worker.closeAsync {
            var failure = nativeFailure
            listOf<() -> Unit>(
                { owner.reloadController?.closePersistence(); Unit },
                { owner.ledger?.closeAsync()?.join(); Unit },
                { owner.sql?.executor?.closeAsync()?.join(); Unit },
                { owner.sql?.close(); Unit },
            ).forEach { close ->
                try { close() } catch (error: Throwable) {
                    if (failure == null) failure = error else if (failure !== error) failure?.addSuppressed(error)
                }
            }
            failure?.let { throw it }
        }.whenComplete { _, failure ->
            if (failure == null) owner.drain.complete(Unit) else {
                owner.drain.completeExceptionally(failure)
                logger.log(Level.SEVERE, "Could not close ArcVotes storage", failure)
            }
        }
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
        VoteLocale.mergeDefaults(dataPath)
    }

    private fun publishConfirmedVote(runtime: PaperPluginRuntime, event: ru.ruscrafting.votes.domain.VoteEvent) {
        runtime.tasks.runSync {
            if (lifecycle !== runtime) return@runSync
            val playerId = event.playerId
                ?: server.getPlayerExact(event.vote.playerName.value)?.uniqueId
                ?: return@runSync
            val onlinePlayer = server.getPlayer(playerId)
            if (event.playerId == null && onlinePlayer == null) return@runSync
            server.pluginManager.callEvent(VoteConfirmedEvent(event.id.toString(), playerId))
        }
    }

    private fun publishConfirmedVote(event: ru.ruscrafting.votes.domain.VoteEvent, playerId: java.util.UUID) {
        server.pluginManager.callEvent(VoteConfirmedEvent(event.id.toString(), playerId))
    }

    private companion object {
        const val STARTUP_TIMEOUT_SECONDS = 30L
        const val HEALTH_REPORT_TICKS = 1_200L
    }
}
