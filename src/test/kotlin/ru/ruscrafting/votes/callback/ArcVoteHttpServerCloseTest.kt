package ru.ruscrafting.votes.callback

import com.sun.net.httpserver.HttpContext
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import ru.ruscrafting.votes.config.ArcVotesSettings
import java.nio.file.Files
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.logging.Logger

class ArcVoteHttpServerCloseTest : FreeSpec({
    "listener stop failure still terminates its real executor and remains failed on repeated close" {
        withHttpServer { native, server, executor ->
            val failure = IllegalStateException("listener stop failed")
            native.stopAction = { throw failure }
            val closed = shouldThrow<IllegalStateException> { server.close() }
            closed.cause shouldBe failure
            executor.isTerminated shouldBe true
            shouldThrow<IllegalStateException> { server.close() }.cause shouldBe failure
        }
    }

    "interrupted shutdown confirms worker termination and preserves both the flag and failure" {
        withHttpServer { native, server, executor ->
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            executor.execute {
                entered.countDown()
                try { release.await() } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
            }
            entered.await(2, TimeUnit.SECONDS) shouldBe true
            native.stopAction = { Thread.currentThread().interrupt() }
            try {
                val failure = shouldThrow<IllegalStateException> { server.close() }
                (failure.cause is InterruptedException) shouldBe true
                Thread.currentThread().isInterrupted shouldBe true
                executor.isTerminated shouldBe true
            } finally {
                Thread.interrupted()
                release.countDown()
            }
        }
    }

    "forced shutdown cannot acknowledge a worker that ignores interruption" {
        withHttpServer { _, server, executor ->
            val entered = CountDownLatch(1)
            val interrupted = CountDownLatch(1)
            val release = CountDownLatch(1)
            executor.execute {
                entered.countDown()
                while (release.count > 0) {
                    try { release.await() } catch (_: InterruptedException) { interrupted.countDown() }
                }
            }
            entered.await(2, TimeUnit.SECONDS) shouldBe true
            try {
                val failure = shouldThrow<IllegalStateException> { server.close() }
                failure.cause?.message shouldBe "ArcVotes HTTP callback workers did not terminate"
                interrupted.count shouldBe 0L
                executor.isTerminated shouldBe false
                shouldThrow<IllegalStateException> { server.close() }
            } finally {
                release.countDown()
                executor.awaitTermination(2, TimeUnit.SECONDS) shouldBe true
            }
        }
    }
})

/** Captures the real owned executor through HttpServer's public registration boundary. */
private fun withHttpServer(block: (RecordingHttpServer, ArcVoteHttpServer, ThreadPoolExecutor) -> Unit) {
    val root = Files.createTempDirectory("arcvotes-http-close")
    val settings = ArcVotesSettings.load(root) { null }.http
    val native = RecordingHttpServer()
    var server: ArcVoteHttpServer? = null
    try {
        server = ArcVoteHttpServer(settings, { ArcVoteHttpState(settings, null) }, Logger.getAnonymousLogger()) { native }
        block(native, server, native.ownedExecutor as ThreadPoolExecutor)
    } finally {
        Thread.interrupted()
        server?.let { runCatching(it::close) }
        (native.ownedExecutor as? ThreadPoolExecutor)?.apply { shutdownNow(); awaitTermination(2, TimeUnit.SECONDS) }
        root.toFile().deleteRecursively()
    }
}

private class RecordingHttpServer : HttpServer() {
    var ownedExecutor: Executor? = null
    var stopAction: () -> Unit = {}
    override fun bind(address: InetSocketAddress?, backlog: Int) = Unit
    override fun start() = Unit
    override fun stop(delay: Int) { check(delay == 1); stopAction() }
    override fun setExecutor(executor: Executor?) { ownedExecutor = executor }
    override fun getExecutor(): Executor? = ownedExecutor
    override fun getAddress(): InetSocketAddress = InetSocketAddress("127.0.0.1", 0)
    override fun createContext(path: String?, handler: HttpHandler?): HttpContext? = null
    override fun createContext(path: String?): HttpContext? = null
    override fun removeContext(path: String?) = Unit
    override fun removeContext(context: HttpContext?) = Unit
}
