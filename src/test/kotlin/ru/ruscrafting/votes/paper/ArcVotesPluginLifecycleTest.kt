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
import java.util.concurrent.atomic.AtomicInteger

class ArcVotesPluginLifecycleTest : FreeSpec({
    "accepted preparation is the predecessor of the latest enable after an intermediate disable" {
        MockBukkitTestRuntime.open().use { paper ->
            paper.createSimplePlugin("ARC")
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val preparations = AtomicInteger()
            mockkObject(ArcVotesSettings.Companion)
            every { ArcVotesSettings.loadFresh(any(), any()) } answers {
                Bukkit.isPrimaryThread() shouldBe false
                if (preparations.incrementAndGet() == 1) {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                }
                throw IllegalStateException("isolated lifecycle preparation")
            }
            var plugin: ArcVotesPlugin? = null
            try {
                plugin = paper.loadPlugin<ArcVotesPlugin>()
                awaitVotesLifecycle(paper) { entered.count == 0L }
                paper.server.pluginManager.disablePlugin(plugin)
                plugin.drained().isDone shouldBe false
                plugin.onDisable()
                plugin.drained().isDone shouldBe false
                paper.server.pluginManager.enablePlugin(plugin)
                paper.performTicks(3)
                preparations.get() shouldBe 1
                paper.server.pluginManager.disablePlugin(plugin)
                paper.server.pluginManager.enablePlugin(plugin)
                paper.performTicks(3)
                preparations.get() shouldBe 1
                release.countDown()
                awaitVotesLifecycle(paper) { preparations.get() == 2 }
                awaitVotesLifecycle(paper) { !plugin.isEnabled }
            } finally {
                release.countDown()
                plugin?.let { active -> paper.server.pluginManager.disablePlugin(active); active.drained().get(5, TimeUnit.SECONDS) }
                unmockkObject(ArcVotesSettings.Companion)
            }
        }
    }

    "terminal ledger completion retains SQL and its failure closes admission of the next generation" {
        val settings = ArcVotesSettings.loadFresh(Path.of(System.getProperty("arcvotes.projectDir"))
            .resolve("src/main/resources")) { null }.copy(
            sql = SqlConnectionConfig("localhost", database = "test", username = "test", password = "disposable-test"),
        )
        MockBukkitTestRuntime.open().use { paper ->
            paper.createSimplePlugin("ARC")
            val ledgerAttached = CountDownLatch(1)
            val closeEntered = CountDownLatch(1)
            val ledgerClose = CompletableFuture<Unit>()
            val sqlDrainEntered = CountDownLatch(1)
            val sqlDrain = CompletableFuture<Unit>()
            val sqlClosed = AtomicBoolean()
            val opens = AtomicInteger()
            val executor = mockk<SqlExecutor> {
                every { closeAsync() } answers {
                    Bukkit.isPrimaryThread() shouldBe false
                    sqlDrainEntered.countDown()
                    sqlDrain
                }
            }
            val sql = mockk<SqlRuntime> {
                every { this@mockk.executor } returns executor
                every { close() } answers { sqlClosed.set(true); Unit }
            }
            val ledger = mockk<MySqlOneTimeUseLedger> {
                every { closeAsync() } answers {
                    Bukkit.isPrimaryThread() shouldBe false
                    closeEntered.countDown()
                    ledgerClose
                }
            }
            mockkObject(ArcVotesSettings.Companion, SqlRuntime.Companion, MySqlOneTimeUseLedger.Companion)
            mockkConstructor(MySqlVoteRepository::class)
            every { ArcVotesSettings.loadFresh(any(), any()) } returns settings
            every { SqlRuntime.create(any(), any()) } answers { opens.incrementAndGet(); sql }
            every { anyConstructed<MySqlVoteRepository>().initialize() } returns CompletableFuture.completedFuture(Unit)
            every { MySqlOneTimeUseLedger.attach(any(), any(), any()) } answers { ledgerAttached.countDown(); ledger }
            var plugin: ArcVotesPlugin? = null
            try {
                plugin = paper.loadPlugin<ArcVotesPlugin>()
                // Keep native installation queued while the real worker acquires its owned ledger.
                paper.performTicks(1)
                ledgerAttached.await(5, TimeUnit.SECONDS) shouldBe true
                paper.server.pluginManager.disablePlugin(plugin)
                closeEntered.await(5, TimeUnit.SECONDS) shouldBe true
                plugin.drained().isDone shouldBe false
                sqlClosed.get() shouldBe false
                paper.server.pluginManager.enablePlugin(plugin)
                paper.performTicks(3)
                opens.get() shouldBe 1
                ledgerClose.completeExceptionally(IllegalStateException("terminal cleanup failed"))
                sqlDrainEntered.await(5, TimeUnit.SECONDS) shouldBe true
                plugin.drained().isDone shouldBe false
                sqlClosed.get() shouldBe false
                opens.get() shouldBe 1
                sqlDrain.completeExceptionally(IllegalStateException("accepted SQL cleanup failed"))
                awaitVotesLifecycle(paper) { !plugin.isEnabled }
                sqlClosed.get() shouldBe true
                plugin.drained().isCompletedExceptionally shouldBe true
                opens.get() shouldBe 1
            } finally {
                ledgerClose.complete(Unit)
                sqlDrain.complete(Unit)
                plugin?.let { active ->
                    paper.server.pluginManager.disablePlugin(active)
                    runCatching { active.drained().get(5, TimeUnit.SECONDS) }
                }
                unmockkConstructor(MySqlVoteRepository::class)
                unmockkObject(ArcVotesSettings.Companion, SqlRuntime.Companion, MySqlOneTimeUseLedger.Companion)
            }
        }
    }
})

private fun awaitVotesLifecycle(paper: MockBukkitTestRuntime, ready: () -> Boolean) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
    while (!ready() && System.nanoTime() < deadline) { paper.performTicks(1); Thread.sleep(1) }
    ready() shouldBe true
}
