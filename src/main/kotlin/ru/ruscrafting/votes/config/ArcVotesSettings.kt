package ru.ruscrafting.votes.config

import ru.arc.config.Config
import ru.arc.config.ConfigManager
import ru.arc.sql.SqlConnectionConfig
import ru.arc.sql.SqlSslMode
import ru.ruscrafting.votes.domain.RewardProvider
import ru.ruscrafting.votes.domain.VoteRewardBundle
import ru.ruscrafting.votes.domain.VoteRewardComponent
import java.math.BigDecimal
import java.net.InetAddress
import java.net.Inet6Address
import java.net.URI
import java.nio.file.Path
import java.time.ZoneId
import java.util.Locale

enum class MonitoringSource(val configKey: String) {
    MINECRAFT_RATING("minecraft-rating"),
    HOTMC("hotmc"),
    MONITORING_MINECRAFT("monitoring-minecraft"),
    GAME_MONITORING("game-monitoring"),
}

data class HttpSettings(
    val enabled: Boolean,
    val bindAddress: InetAddress,
    val port: Int,
    val workerThreads: Int,
    val queueCapacity: Int,
    val maximumBodyBytes: Int,
    val persistenceTimeoutMs: Long,
    val trustSingleForwardedClientIp: Boolean,
    val maximumHeaderCount: Int = 32,
    val maximumHeaderBytes: Int = 8192,
    val maximumHeaderValueBytes: Int = 4096,
) {
    init {
        require(bindAddress.isLoopbackAddress) { "http.bind-address must be a loopback address" }
        require(port in 1..65_535) { "http.port must be between 1 and 65535" }
        require(workerThreads in 1..8) { "http.worker-threads must be between 1 and 8" }
        require(queueCapacity in 8..1_024) { "http.queue-capacity must be between 8 and 1024" }
        require(maximumBodyBytes in 1_024..65_536) { "http.maximum-body-bytes must be between 1024 and 65536" }
        require(persistenceTimeoutMs in 500..30_000) { "http.persistence-timeout-ms must be between 500 and 30000" }
        require(maximumHeaderCount in 8..128) { "http.maximum-header-count must be between 8 and 128" }
        require(maximumHeaderBytes in 1_024..32_768) { "http.maximum-header-bytes must be between 1024 and 32768" }
        require(maximumHeaderValueBytes in 256..16_384) { "http.maximum-header-value-bytes must be between 256 and 16384" }
        require(maximumHeaderValueBytes <= maximumHeaderBytes) { "http.maximum-header-value-bytes must not exceed maximum-header-bytes" }
    }
}

data class RewardSettings(
    val enabled: Boolean,
    val pollIntervalSeconds: Long,
    val maximumPendingPerPlayer: Int,
    val standard: RewardComponentSettings,
    val premium: RewardComponentSettings,
    val maximumOnlinePlayerBatch: Int = 500,
) {
    init {
        require(pollIntervalSeconds in 1..300) { "reward.poll-interval-seconds must be between 1 and 300" }
        require(maximumPendingPerPlayer in 1..64) { "reward.maximum-pending-per-player must be between 1 and 64" }
        require(maximumOnlinePlayerBatch in 1..1_000) { "reward.maximum-online-player-batch must be between 1 and 1000" }
        require(!enabled || standard.enabled || premium.enabled) { "At least one vote reward component must be enabled" }
    }

    val bundle: VoteRewardBundle? = if (!enabled) null else VoteRewardBundle(
        listOfNotNull(standard.component(), premium.component()),
    )
}

data class RewardComponentSettings(
    val enabled: Boolean,
    val key: String,
    val provider: RewardProvider,
    val amount: BigDecimal,
    val currencyId: String? = null,
) {
    private val validated = VoteRewardComponent(key, provider, amount, currencyId)

    fun component(): VoteRewardComponent? = validated.takeIf { enabled }
}

data class SourcePresentation(
    val displayName: String,
    val voteUrl: URI,
) {
    init {
        require(displayName.length in 1..32 && displayName.all { it.code in 32..126 || it.code >= 160 }) {
            "Monitoring display name is invalid"
        }
        require(voteUrl.scheme == "https" && !voteUrl.host.isNullOrBlank() && voteUrl.userInfo == null && voteUrl.fragment == null) {
            "Monitoring vote URL must be an absolute HTTPS URL without user info or fragment"
        }
    }
}

data class NetworkSourcePolicy(
    val enforceIpAllowlist: Boolean,
    val allowedIps: Set<String>,
) {
    init {
        require(allowedIps.size <= 32) { "A monitoring IP allowlist may contain at most 32 addresses" }
        require(allowedIps.all(::isIpLiteral)) { "Monitoring IP allowlist must contain only IP literals" }
        require(!enforceIpAllowlist || allowedIps.isNotEmpty()) { "An enforced monitoring IP allowlist must not be empty" }
    }
}

data class SignedFormSourceSettings(
    val enabled: Boolean,
    val presentation: SourcePresentation,
    val secret: SecretValue?,
    val maximumAgeSeconds: Long,
    val maximumFutureSkewSeconds: Long,
    val network: NetworkSourcePolicy,
) {
    init {
        require(!enabled || secret != null) { "Enabled signed-form monitoring requires a secret" }
        require(maximumAgeSeconds in 300..1_209_600) { "Monitoring maximum age must be between 5 minutes and 14 days" }
        require(maximumFutureSkewSeconds in 0..3_600) { "Monitoring future skew must be between 0 and 3600 seconds" }
    }
}

data class MonitoringMinecraftSettings(
    val enabled: Boolean,
    val presentation: SourcePresentation,
    val secret: SecretValue?,
    val expectedServerId: String,
    val maximumAgeSeconds: Long,
    val maximumFutureSkewSeconds: Long,
    val network: NetworkSourcePolicy,
) {
    init {
        require(!enabled || secret != null) { "Enabled MonitoringMinecraft adapter requires a secret" }
        require(expectedServerId.matches(Regex("[0-9]{1,20}"))) { "MonitoringMinecraft server id must be numeric" }
        require(maximumAgeSeconds in 300..1_209_600) { "MonitoringMinecraft maximum age must be between 5 minutes and 14 days" }
        require(maximumFutureSkewSeconds in 0..3_600) { "MonitoringMinecraft future skew must be between 0 and 3600 seconds" }
    }
}

data class GameMonitoringSettings(
    val enabled: Boolean,
    val presentation: SourcePresentation,
    val webhookToken: SecretValue?,
    val expectedEntityType: String,
    val expectedEntityId: String,
    val network: NetworkSourcePolicy,
    val connectTimeoutMs: Long = 3_000,
    val requestTimeoutMs: Long = 4_000,
    val maximumResponseBytes: Int = 32_768,
    val apiBaseUrl: URI = URI("https://api.gamemonitoring.ru/votes/"),
) {
    init {
        require(!enabled || webhookToken != null) { "Enabled GameMonitoring adapter requires a webhook token" }
        require(expectedEntityType in setOf("server", "project")) { "GameMonitoring entity type must be server or project" }
        require(expectedEntityId.matches(Regex("[0-9]{1,20}"))) { "GameMonitoring entity id must be numeric" }
        require(connectTimeoutMs in 250..30_000) { "GameMonitoring connect-timeout-ms must be between 250 and 30000" }
        require(requestTimeoutMs in 500..60_000 && requestTimeoutMs >= connectTimeoutMs) {
            "GameMonitoring request-timeout-ms must be between 500 and 60000 and at least connect timeout"
        }
        require(maximumResponseBytes in 1_024..262_144) { "GameMonitoring maximum-response-bytes must be between 1024 and 262144" }
        require(
            apiBaseUrl.scheme.equals("https", ignoreCase = true) &&
                apiBaseUrl.host.equals("api.gamemonitoring.ru", ignoreCase = true) &&
                apiBaseUrl.port in setOf(-1, 443) &&
                apiBaseUrl.rawUserInfo == null &&
                apiBaseUrl.rawQuery == null &&
                apiBaseUrl.rawFragment == null &&
                apiBaseUrl.path.startsWith('/') &&
                apiBaseUrl.path.endsWith("/votes/"),
        ) {
            "GameMonitoring api-base-url must be a clean HTTPS api.gamemonitoring.ru URL ending /votes/"
        }
    }
}

data class StatusSettings(
    val voteDayZone: ZoneId = ZoneId.of("Europe/Moscow"),
    val cacheTtlSeconds: Long = 30,
    val maximumCacheEntries: Int = 2_048,
) {
    init {
        require(cacheTtlSeconds in 1..300) { "status.cache-ttl-seconds must be between 1 and 300" }
        require(maximumCacheEntries in 128..10_000) { "status.maximum-cache-entries must be between 128 and 10000" }
    }
}

data class ArcVotesSettings(
    val serverId: String,
    val defaultLocale: String,
    val useClientLocale: Boolean,
    val http: HttpSettings,
    val sql: SqlConnectionConfig?,
    val reward: RewardSettings,
    val minecraftRating: SignedFormSourceSettings,
    val hotMc: SignedFormSourceSettings,
    val monitoringMinecraft: MonitoringMinecraftSettings,
    val gameMonitoring: GameMonitoringSettings,
    val status: StatusSettings = StatusSettings(),
) {
    val enabledSources: Set<MonitoringSource> = buildSet {
        if (minecraftRating.enabled) add(MonitoringSource.MINECRAFT_RATING)
        if (hotMc.enabled) add(MonitoringSource.HOTMC)
        if (monitoringMinecraft.enabled) add(MonitoringSource.MONITORING_MINECRAFT)
        if (gameMonitoring.enabled) add(MonitoringSource.GAME_MONITORING)
    }

    val presentations: Map<MonitoringSource, SourcePresentation> = mapOf(
        MonitoringSource.MINECRAFT_RATING to minecraftRating.presentation,
        MonitoringSource.HOTMC to hotMc.presentation,
        MonitoringSource.MONITORING_MINECRAFT to monitoringMinecraft.presentation,
        MonitoringSource.GAME_MONITORING to gameMonitoring.presentation,
    )

    init {
        require(serverId.matches(Regex("[a-z0-9_-]{1,40}"))) { "server-id is unsafe" }
        require(defaultLocale in setOf("ru", "en")) { "locale.default must be ru or en" }
        require(!http.enabled || sql != null) { "MySQL must be enabled before callback HTTP ingress" }
        require(!http.enabled || enabledSources.isNotEmpty()) { "At least one monitoring must be enabled with HTTP ingress" }
        require(!reward.enabled || sql != null) { "MySQL must be enabled before vote rewards" }
    }

    companion object {
        fun load(
            dataRoot: Path,
            environment: (String) -> String? = System::getenv,
        ): ArcVotesSettings {
            return loadFromConfig(dataRoot, ConfigManager.of(dataRoot, "config.yml"), environment)
        }

        fun loadFresh(dataRoot: Path, environment: (String) -> String? = System::getenv): ArcVotesSettings =
            loadFromConfig(dataRoot, Config(dataRoot, "config.yml"), environment)

        private fun loadFromConfig(
            dataRoot: Path,
            config: Config,
            environment: (String) -> String?,
        ): ArcVotesSettings {
            val secrets = SecretResolver(dataRoot, environment)
            val mysqlEnabled = config.boolean("mysql.enabled")
            return ArcVotesSettings(
                serverId = config.string("server-id").trim().lowercase(Locale.ROOT),
                defaultLocale = config.string("locale.default").trim().lowercase(Locale.ROOT),
                useClientLocale = config.boolean("locale.use-client-locale"),
                http = HttpSettings(
                    enabled = config.boolean("http.enabled"),
                    bindAddress = parseLoopback(config.string("http.bind-address")),
                    port = config.int("http.port"),
                    workerThreads = config.int("http.worker-threads"),
                    queueCapacity = config.int("http.queue-capacity"),
                    maximumBodyBytes = config.int("http.maximum-body-bytes"),
                    persistenceTimeoutMs = config.long("http.persistence-timeout-ms"),
                    trustSingleForwardedClientIp = config.boolean("http.trust-single-forwarded-client-ip"),
                    maximumHeaderCount = config.int("http.maximum-header-count", 32),
                    maximumHeaderBytes = config.int("http.maximum-header-bytes", 8192),
                    maximumHeaderValueBytes = config.int("http.maximum-header-value-bytes", 4096),
                ),
                sql = if (mysqlEnabled) loadSql(config, secrets) else null,
                reward = RewardSettings(
                    enabled = config.boolean("reward.enabled"),
                    pollIntervalSeconds = config.long("reward.poll-interval-seconds"),
                    maximumPendingPerPlayer = config.int("reward.maximum-pending-per-player"),
                    standard = RewardComponentSettings(
                        enabled = config.boolean("reward.standard.enabled"),
                        key = "standard",
                        provider = RewardProvider.VAULT,
                        amount = config.string("reward.standard.amount").trim().toBigDecimal(),
                    ),
                    premium = RewardComponentSettings(
                        enabled = config.boolean("reward.premium.enabled"),
                        key = "premium",
                        provider = RewardProvider.REDIS_ECONOMY,
                        amount = config.string("reward.premium.amount").trim().toBigDecimal(),
                        currencyId = config.string("reward.premium.currency-id").trim(),
                    ),
                    maximumOnlinePlayerBatch = config.int("reward.maximum-online-player-batch", 500),
                ),
                status = StatusSettings(
                    voteDayZone = ZoneId.of(config.string("status.vote-day-zone", "Europe/Moscow").trim()),
                    cacheTtlSeconds = config.long("status.cache-ttl-seconds", 30),
                    maximumCacheEntries = config.int("status.maximum-cache-entries", 2048),
                ),
                minecraftRating = loadSignedForm(config, secrets, MonitoringSource.MINECRAFT_RATING),
                hotMc = loadSignedForm(config, secrets, MonitoringSource.HOTMC),
                monitoringMinecraft = loadMonitoringMinecraft(config, secrets),
                gameMonitoring = loadGameMonitoring(config, secrets),
            )
        }

        private fun loadSql(config: Config, secrets: SecretResolver): SqlConnectionConfig {
            val passwordEnvironment = environmentName(config.string("mysql.password-env"))
            return SqlConnectionConfig(
                host = config.string("mysql.host").trim(),
                port = config.int("mysql.port"),
                database = config.string("mysql.database").trim(),
                username = config.string("mysql.username").trim(),
                password = secrets.require(passwordEnvironment).revealForCryptography(),
                sslMode = SqlSslMode.valueOf(config.string("mysql.ssl-mode").trim().uppercase(Locale.ROOT)),
                minimumIdle = config.int("mysql.minimum-idle"),
                maximumPoolSize = config.int("mysql.maximum-pool-size"),
                connectionTimeoutMs = config.long("mysql.connection-timeout-ms"),
                socketTimeoutMs = config.long("mysql.socket-timeout-ms"),
                validationTimeoutMs = config.long("mysql.validation-timeout-ms"),
                maxLifetimeMs = config.long("mysql.max-lifetime-ms"),
                failFast = config.boolean("mysql.fail-fast"),
            )
        }

        private fun loadSignedForm(config: Config, secrets: SecretResolver, source: MonitoringSource): SignedFormSourceSettings {
            val prefix = "monitorings.${source.configKey}"
            val enabled = config.boolean("$prefix.enabled")
            return SignedFormSourceSettings(
                enabled = enabled,
                presentation = presentation(config, prefix),
                secret = if (enabled) secrets.require(environmentName(config.string("$prefix.secret-env"))) else null,
                maximumAgeSeconds = config.long("$prefix.maximum-age-seconds"),
                maximumFutureSkewSeconds = config.long("$prefix.maximum-future-skew-seconds"),
                network = networkPolicy(config, prefix),
            )
        }

        private fun loadMonitoringMinecraft(config: Config, secrets: SecretResolver): MonitoringMinecraftSettings {
            val prefix = "monitorings.monitoring-minecraft"
            val enabled = config.boolean("$prefix.enabled")
            return MonitoringMinecraftSettings(
                enabled = enabled,
                presentation = presentation(config, prefix),
                secret = if (enabled) secrets.require(environmentName(config.string("$prefix.secret-env"))) else null,
                expectedServerId = config.string("$prefix.expected-server-id").trim(),
                maximumAgeSeconds = config.long("$prefix.maximum-age-seconds"),
                maximumFutureSkewSeconds = config.long("$prefix.maximum-future-skew-seconds"),
                network = networkPolicy(config, prefix),
            )
        }

        private fun loadGameMonitoring(config: Config, secrets: SecretResolver): GameMonitoringSettings {
            val prefix = "monitorings.game-monitoring"
            val enabled = config.boolean("$prefix.enabled")
            return GameMonitoringSettings(
                enabled = enabled,
                presentation = presentation(config, prefix),
                webhookToken = if (enabled) secrets.require(environmentName(config.string("$prefix.webhook-token-env"))) else null,
                expectedEntityType = config.string("$prefix.expected-entity-type").trim().lowercase(Locale.ROOT),
                expectedEntityId = config.string("$prefix.expected-entity-id").trim(),
                connectTimeoutMs = config.long("$prefix.connect-timeout-ms", 3_000),
                requestTimeoutMs = config.long("$prefix.request-timeout-ms", 4_000),
                maximumResponseBytes = config.int("$prefix.maximum-response-bytes", 32_768),
                apiBaseUrl = URI(config.string("$prefix.api-base-url", "https://api.gamemonitoring.ru/votes/").trim()),
                network = networkPolicy(config, prefix),
            )
        }

        private fun presentation(config: Config, prefix: String): SourcePresentation = SourcePresentation(
            displayName = config.string("$prefix.display-name").trim(),
            voteUrl = URI(config.string("$prefix.vote-url").trim()),
        )

        private fun networkPolicy(config: Config, prefix: String): NetworkSourcePolicy = NetworkSourcePolicy(
            enforceIpAllowlist = config.boolean("$prefix.enforce-ip-allowlist"),
            allowedIps = config.stringList("$prefix.allowed-ips").map(String::trim).filter(String::isNotEmpty).toSet(),
        )

        private fun environmentName(value: String): String = value.trim().also {
            require(it.matches(Regex("[A-Z][A-Z0-9_]{2,80}"))) { "Secret environment variable name is unsafe" }
        }

        private fun parseLoopback(value: String): InetAddress {
            val normalized = value.trim()
            require(normalized in setOf("127.0.0.1", "::1")) { "http.bind-address must be 127.0.0.1 or ::1" }
            return InetAddress.getByName(normalized)
        }
    }
}

private val IPV4 = Regex("(?:0|[1-9][0-9]{0,2})(?:\\.(?:0|[1-9][0-9]{0,2})){3}")
private val IPV6 = Regex("[0-9A-Fa-f:]{2,45}")

private fun isIpLiteral(value: String): Boolean {
    if (IPV4.matches(value)) return value.split('.').all { it.toInt() in 0..255 }
    if (!IPV6.matches(value) || ':' !in value) return false
    return runCatching { InetAddress.getByName(value) }.getOrNull() is Inet6Address
}
