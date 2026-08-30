package ru.ruscrafting.votes.callback

import ru.ruscrafting.votes.domain.AuthenticatedVote
import java.net.InetAddress
import java.util.Locale
import java.util.concurrent.CompletableFuture

data class CallbackRequest(
    val method: String,
    val path: String,
    val headers: Map<String, List<String>>,
    val body: ByteArray,
    val clientAddress: InetAddress,
) {
    fun singleHeader(name: String): String? {
        val values = headers[name.lowercase(Locale.ROOT)] ?: return null
        if (values.size != 1) throw CallbackRejected(400, "duplicate_header")
        return values.single().trim()
    }
}

data class CallbackResponse(
    val status: Int,
    val body: ByteArray = ByteArray(0),
    val contentType: String? = null,
) {
    init {
        require(status in 100..599) { "Invalid callback response status" }
        require(body.size <= 1_024) { "Callback response is too large" }
    }

    companion object {
        fun ok(): CallbackResponse = CallbackResponse(200, "ok".toByteArray(Charsets.US_ASCII), "text/plain; charset=us-ascii")
        fun noContent(): CallbackResponse = CallbackResponse(204)
        fun error(status: Int, code: String): CallbackResponse = CallbackResponse(
            status,
            code.toByteArray(Charsets.US_ASCII),
            "text/plain; charset=us-ascii",
        )
    }
}

sealed interface CallbackAuthenticationResult {
    data class Accepted(val vote: AuthenticatedVote) : CallbackAuthenticationResult
    data object TestAcknowledged : CallbackAuthenticationResult
    data object Ignored : CallbackAuthenticationResult
}

class CallbackRejected(
    val status: Int,
    val safeCode: String,
) : RuntimeException(safeCode) {
    init {
        require(status in 400..499) { "Rejected callback status must be a client error" }
        require(safeCode.matches(Regex("[a-z0-9_]{1,48}"))) { "Rejected callback code is unsafe" }
    }
}

interface VoteCallbackAdapter {
    val successResponse: CallbackResponse
    fun authenticate(request: CallbackRequest): CompletableFuture<CallbackAuthenticationResult>
}

fun <T> completed(value: T): CompletableFuture<T> = CompletableFuture.completedFuture(value)
