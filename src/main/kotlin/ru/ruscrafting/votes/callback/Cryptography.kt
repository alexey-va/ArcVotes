package ru.ruscrafting.votes.callback

import ru.ruscrafting.votes.config.SecretValue
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object CallbackCryptography {
    fun verifySha1Concatenation(actualHex: String, secret: SecretValue, vararg fields: String): Boolean {
        val actual = decodeHex(actualHex, 20) ?: return false
        val digest = MessageDigest.getInstance("SHA-1")
        fields.forEach { digest.update(it.toByteArray(StandardCharsets.UTF_8)) }
        digest.update(secret.utf8())
        return MessageDigest.isEqual(digest.digest(), actual)
    }

    fun verifyHmacSha256(actualHex: String, secret: SecretValue, canonical: String): Boolean {
        val actual = decodeHex(actualHex, 32) ?: return false
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.utf8(), "HmacSHA256"))
        return MessageDigest.isEqual(mac.doFinal(canonical.toByteArray(StandardCharsets.UTF_8)), actual)
    }

    fun sha256Id(vararg fields: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fields.forEach { field ->
            val bytes = field.toByteArray(StandardCharsets.UTF_8)
            digest.update((bytes.size ushr 24).toByte())
            digest.update((bytes.size ushr 16).toByte())
            digest.update((bytes.size ushr 8).toByte())
            digest.update(bytes.size.toByte())
            digest.update(bytes)
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    fun constantTimeEquals(expected: SecretValue, actual: String): Boolean = MessageDigest.isEqual(
        expected.utf8(),
        actual.toByteArray(StandardCharsets.UTF_8),
    )

    private fun decodeHex(value: String, expectedBytes: Int): ByteArray? {
        if (value.length != expectedBytes * 2 || value.any { it.digitToIntOrNull(16) == null }) return null
        return ByteArray(expectedBytes) { index ->
            val high = value[index * 2].digitToInt(16)
            val low = value[index * 2 + 1].digitToInt(16)
            ((high shl 4) or low).toByte()
        }
    }
}
