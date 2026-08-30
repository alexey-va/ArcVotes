package ru.ruscrafting.votes.reward

import net.milkbowl.vault.economy.Economy
import net.milkbowl.vault.economy.EconomyResponse
import org.bukkit.entity.Player
import ru.ruscrafting.votes.domain.RewardProvider
import ru.ruscrafting.votes.domain.VoteRewardBundle
import ru.ruscrafting.votes.domain.VoteRewardComponent
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.UUID

enum class RewardDepositResult {
    APPLIED,
    REJECTED,
}

/**
 * Applies one configured vote reward component on the Paper primary thread.
 *
 * A returned [RewardDepositResult.APPLIED] means the provider accepted the
 * mutation. An exception leaves the effect outcome unknown and must enter
 * durable recovery rather than being retried automatically.
 */
fun interface VoteRewardDepositor {
    fun deposit(player: Player, component: VoteRewardComponent): RewardDepositResult
}

class VaultRedisEconomyRewardDepositor private constructor(
    private val vault: Economy?,
    private val redisCurrencies: Map<String, RedisCurrencyHandle>,
) : VoteRewardDepositor {
    override fun deposit(player: Player, component: VoteRewardComponent): RewardDepositResult {
        val response = when (component.provider) {
            RewardProvider.VAULT -> requireNotNull(vault) { "Vault economy service is unavailable" }
                .depositPlayer(player, component.amount.toDouble())
            RewardProvider.REDIS_ECONOMY -> requireNotNull(redisCurrencies[component.currencyId]) {
                "Configured RedisEconomy currency is unavailable"
            }.deposit(player.uniqueId, player.name, component.amount.toDouble())
        }
        return if (response.transactionSuccess()) RewardDepositResult.APPLIED else RewardDepositResult.REJECTED
    }

    companion object {
        fun create(
            vault: Economy?,
            redisEconomyClassLoader: ClassLoader?,
            reward: VoteRewardBundle,
        ): VaultRedisEconomyRewardDepositor {
            require(reward.components.none { it.provider == RewardProvider.VAULT } || vault != null) {
                "Vault economy service is required for standard vote rewards"
            }
            val currencyIds = reward.components
                .filter { it.provider == RewardProvider.REDIS_ECONOMY }
                .map { requireNotNull(it.currencyId) }
                .toSet()
            val currencies = if (currencyIds.isEmpty()) emptyMap() else RedisEconomyBridge.open(
                requireNotNull(redisEconomyClassLoader) { "RedisEconomy API is required for premium vote rewards" },
                currencyIds,
            )
            return VaultRedisEconomyRewardDepositor(vault, currencies)
        }

    }
}

private data class RedisCurrencyHandle(
    private val currency: Any,
    private val depositMethod: Method,
) {
    fun deposit(playerId: UUID, playerName: String, amount: Double): EconomyResponse = try {
        depositMethod.invoke(currency, playerId, playerName, amount, TRANSACTION_REASON) as? EconomyResponse
            ?: error("RedisEconomy returned an unsupported deposit result")
    } catch (failure: InvocationTargetException) {
        throw failure.targetException
    }
}

private const val TRANSACTION_REASON = "ArcVotes vote reward"

private object RedisEconomyBridge {
    private const val API_CLASS = "dev.unnm3d.rediseconomy.api.RedisEconomyAPI"
    private const val CURRENCY_CLASS = "dev.unnm3d.rediseconomy.currency.Currency"

    fun open(classLoader: ClassLoader, currencyIds: Set<String>): Map<String, RedisCurrencyHandle> {
        val apiClass = Class.forName(API_CLASS, true, classLoader)
        val currencyClass = Class.forName(CURRENCY_CLASS, true, classLoader)
        val api = requireNotNull(apiClass.getMethod("getAPI").invoke(null)) { "RedisEconomy API is unavailable" }
        val getCurrency = apiClass.getMethod("getCurrencyByName", String::class.java)
        val isEnabled = currencyClass.getMethod("isEnabled")
        val deposit = currencyClass.getMethod(
            "depositPlayer",
            UUID::class.java,
            String::class.java,
            Double::class.javaPrimitiveType,
            String::class.java,
        )
        return currencyIds.associateWith { currencyId ->
            val currency = requireNotNull(getCurrency.invoke(api, currencyId)) {
                "RedisEconomy currency '$currencyId' is unavailable"
            }
            require(isEnabled.invoke(currency) == true) { "RedisEconomy currency '$currencyId' is disabled" }
            RedisCurrencyHandle(currency, deposit)
        }
    }
}
