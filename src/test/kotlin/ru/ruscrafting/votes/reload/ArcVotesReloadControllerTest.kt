package ru.ruscrafting.votes.reload

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import ru.arc.config.ConfigManager
import ru.arc.core.BoundedAsyncExecutor
import ru.arc.core.BudgetedTaskQueue
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.TestTaskScheduler
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import ru.ruscrafting.votes.config.ArcVotesSettings
import ru.ruscrafting.votes.live.VoteLiveConfiguration
import ru.ruscrafting.votes.live.VoteLiveState
import ru.ruscrafting.votes.text.VoteLocale
import java.nio.file.Files
import java.util.logging.Logger
import kotlin.io.path.readText
import kotlin.io.path.writeText

class ArcVotesReloadControllerTest : StringSpec({
    afterTest { ConfigManager.clear() }

    "applies a fresh live configuration and publishes one new generation" {
        val root = Files.createTempDirectory("arcvotes-reload-applied")
        val live = initialLive(root)
        val before = live.current()
        root.resolve("config.yml").writeText(
            root.resolve("config.yml").readText()
                .replace("default: ru", "default: en")
                .replace("cache-ttl-seconds: 30", "cache-ttl-seconds: 90"),
        )
        val controller = controller(root, live)
        try {

        controller.reloadAndAwait() shouldBe ArcVotesReloadResult.Applied
        (live.current() !== before) shouldBe true
        live.current().settings.defaultLocale shouldBe "en"
        live.current().settings.status.cacheTtlSeconds shouldBe 90L
        } finally { controller.close() }
    }

    "rejects invalid fresh configuration without replacing the live generation" {
        val root = Files.createTempDirectory("arcvotes-reload-failed")
        val live = initialLive(root)
        val before = live.current()
        root.resolve("config.yml").writeText(
            root.resolve("config.yml").readText().replace("default: ru", "default: xx"),
        )
        val controller = controller(root, live)
        try {

        controller.reloadAndAwait() shouldBe ArcVotesReloadResult.Failed
        live.current() shouldBe before
        } finally { controller.close() }
    }

    "requires restart for a server id change and does not publish it" {
        val root = Files.createTempDirectory("arcvotes-reload-restart")
        val live = initialLive(root)
        val before = live.current()
        root.resolve("config.yml").writeText(
            root.resolve("config.yml").readText().replace("server-id: survival", "server-id: spawn"),
        )
        val controller = controller(root, live)
        try {

        val result = controller.reloadAndAwait().shouldBeInstanceOf<ArcVotesReloadResult.RestartRequired>()
        result.fields shouldBe listOf("server-id")
        live.current() shouldBe before
        } finally { controller.close() }
    }
    "a delayed preparation returns immediately and coalesces concurrent reloads" {
        val root = Files.createTempDirectory("arcvotes-reload-delayed")
        val live = initialLive(root)
        val before = live.current()
        ReloadFixture(root, live).use { fixture ->
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            fixture.storage.submit { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
            entered.await(2, TimeUnit.SECONDS) shouldBe true
            try {
                val pending = fixture.controller.reload()
                pending.isDone shouldBe false
                fixture.controller.reload().join() shouldBe ArcVotesReloadResult.Busy
                fixture.scheduler.tick(5)
                live.current() shouldBe before
                release.countDown()
                fixture.await(pending) shouldBe ArcVotesReloadResult.Applied
            } finally { release.countDown() }
        }
    }

})

private fun initialLive(root: java.nio.file.Path): VoteLiveState {
    val settings = ArcVotesSettings.load(root) { null }
    val locale = VoteLocale(root, { settings.defaultLocale }, { settings.useClientLocale }).also { it.validate() }
    return VoteLiveState(
        VoteLiveConfiguration(
            settings = settings,
            locale = locale,
            dailyStatus = null,
            ingress = null,
            rewardDepositor = null,
            vaultReady = true,
            redisEconomyReady = true,
        ),
    )
}

private class ReloadFixture(root: java.nio.file.Path, live: VoteLiveState) : AutoCloseable {
    val storage = BoundedAsyncExecutor("vote-reload-test")
    val scheduler = TestTaskScheduler()
    val tasks = LifecycleTaskScope(scheduler)
    val work = BudgetedTaskQueue(tasks)
    val controller = ArcVotesReloadController(
        dataRoot = root, live = live, repository = null, history = null, rewardService = null,
        rewardRuntimeFactory = VoteRewardRuntimeFactory { VoteRewardRuntime(null, true, true) },
        logger = Logger.getLogger("ArcVotesReloadControllerTest"), storage = storage, nativeWork = work,
    )

    fun reloadAndAwait(): ArcVotesReloadResult = await(controller.reload())

    fun <T> await(future: CompletableFuture<T>): T {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!future.isDone && System.nanoTime() < deadline) { scheduler.tick(); Thread.sleep(1) }
        check(future.isDone) { "Reload did not settle" }
        return future.join()
    }

    override fun close() {
        controller.close()
        work.close()
        tasks.close()
        storage.closeAsync(controller::closePersistence).get(10, TimeUnit.SECONDS)
    }
}

private fun controller(root: java.nio.file.Path, live: VoteLiveState): ReloadFixture = ReloadFixture(root, live)
