package ru.ruscrafting.votes.paper

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkObject
import io.mockk.unmockkConstructor
import io.mockk.unmockkObject
import org.bukkit.Bukkit
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.loadPlugin
import ru.arc.sql.SqlConnectionConfig
import ru.arc.sql.SqlRuntime
import ru.arc.sql.SqlExecutor
import ru.arc.sql.onetime.MySqlOneTimeUseLedger
import ru.ruscrafting.votes.config.ArcVotesSettings
import ru.ruscrafting.votes.storage.MySqlVoteRepository
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class ArcVotesPluginLifecycleTest : FreeSpec({
    "maintenance configuration failure is handled before enable returns" {
        MockBukkitTestRuntime.open().use { paper ->
            paper.createSimplePlugin("ARC")
            var preparations = 0
            mockkObject(ArcVotesSettings.Companion)
            every { ArcVotesSettings.loadFresh(any(), any()) } answers {
                Bukkit.isPrimaryThread() shouldBe false
                preparations++
                throw IllegalStateException("isolated configuration failure")
            }
            try {
                val plugin = paper.loadPlugin<ArcVotesPlugin>()
                preparations shouldBe 1
                plugin.isEnabled shouldBe false
                plugin.drained().isDone shouldBe true
                plugin.onDisable()
                preparations shouldBe 1
            } finally { unmockkObject(ArcVotesSettings.Companion) }
        }
    }

    "maintenance attempts actual ledger and SQL drains before releasing SQL" {
        val loaded = ArcVotesSettings.loadFresh(Path.of(System.getProperty("arcvotes.projectDir"))
            .resolve("src/main/resources")) { null }
        // A missing Vault service gives a supported startup failure after ledger acquisition.
        val settings = loaded.copy(
            sql = SqlConnectionConfig("localhost", database = "test", username = "test", password = "disposable-test"),
            reward = loaded.reward.copy(enabled = true),
        )
        MockBukkitTestRuntime.open().use { paper ->
            paper.createSimplePlugin("ARC")
            val ledgerEntered = CountDownLatch(1)
            val sqlEntered = CountDownLatch(1)
            val ledgerClose = CompletableFuture<Unit>()
            val sqlDrain = CompletableFuture<Unit>()
            val sqlClosed = AtomicBoolean()
            val returned = AtomicBoolean()
            val observedBeforeReturn = AtomicBoolean()
            val executor = mockk<SqlExecutor> {
                every { closeAsync() } answers { Bukkit.isPrimaryThread() shouldBe false; sqlEntered.countDown(); sqlDrain }
            }
            val sql = mockk<SqlRuntime> {
                every { this@mockk.executor } returns executor
                every { close() } answers { sqlClosed.set(true); Unit }
            }
            val ledger = mockk<MySqlOneTimeUseLedger> {
                every { closeAsync() } answers { Bukkit.isPrimaryThread() shouldBe false; ledgerEntered.countDown(); ledgerClose }
            }
            mockkObject(ArcVotesSettings.Companion, SqlRuntime.Companion, MySqlOneTimeUseLedger.Companion)
            mockkConstructor(MySqlVoteRepository::class)
            every { ArcVotesSettings.loadFresh(any(), any()) } returns settings
            every { SqlRuntime.create(any(), any()) } returns sql
            every { anyConstructed<MySqlVoteRepository>().initialize() } returns CompletableFuture.completedFuture(Unit)
            every { MySqlOneTimeUseLedger.attach(any(), any(), any()) } returns ledger
            val release = Thread {
                try {
                    if (ledgerEntered.await(5, TimeUnit.SECONDS)) observedBeforeReturn.set(!returned.get() && !sqlClosed.get())
                    ledgerClose.completeExceptionally(IllegalStateException("isolated ledger cleanup failure"))
                    sqlEntered.await(5, TimeUnit.SECONDS)
                } finally { ledgerClose.complete(Unit); sqlDrain.complete(Unit) }
            }.apply { start() }
            try {
                val plugin = paper.loadPlugin<ArcVotesPlugin>()
                returned.set(true)
                observedBeforeReturn.get() shouldBe true
                sqlEntered.count shouldBe 0L
                sqlClosed.get() shouldBe true
                plugin.isEnabled shouldBe false
                plugin.drained().isCompletedExceptionally shouldBe true
            } finally {
                ledgerClose.complete(Unit); sqlDrain.complete(Unit); release.join(5_000)
                unmockkConstructor(MySqlVoteRepository::class)
                unmockkObject(ArcVotesSettings.Companion, SqlRuntime.Companion, MySqlOneTimeUseLedger.Companion)
            }
        }
    }
})
