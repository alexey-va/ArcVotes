package ru.ruscrafting.votes.config

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/** Secret wrapper whose diagnostics can never reveal the underlying value. */
class SecretValue private constructor(private val value: String) {
    init {
        require(value.isNotBlank()) { "Secret value must not be blank" }
        require('\n' !in value && '\r' !in value) { "Secret value must be one line" }
        require(value.toByteArray(StandardCharsets.UTF_8).size <= MAX_BYTES) { "Secret value is too large" }
    }

    fun utf8(): ByteArray = value.toByteArray(StandardCharsets.UTF_8)

    fun revealForCryptography(): String = value

    override fun toString(): String = "<redacted>"

    companion object {
        private const val MAX_BYTES = 4_096

        fun of(value: String): SecretValue = SecretValue(value)
    }
}

class SecretResolver(
    dataRoot: Path,
    private val environment: (String) -> String? = System::getenv,
) {
    private val dotEnv: Map<String, String> = loadDotEnv(dataRoot.resolve(".env"))

    fun require(environmentName: String): SecretValue {
        require(environmentName.matches(SAFE_ENVIRONMENT_NAME)) {
            "Secret environment variable name is unsafe"
        }
        val value = environment(environmentName)?.takeIf(String::isNotBlank)
            ?: dotEnv[environmentName]?.takeIf(String::isNotBlank)
            ?: error("Required secret environment variable $environmentName is missing")
        return SecretValue.of(value)
    }

    private fun loadDotEnv(path: Path): Map<String, String> {
        if (!Files.isRegularFile(path)) return emptyMap()
        val result = linkedMapOf<String, String>()
        Files.readAllLines(path, StandardCharsets.UTF_8).forEachIndexed { index, rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith('#')) return@forEachIndexed
            val separator = line.indexOf('=')
            require(separator > 0) { "Invalid plugin-local .env entry on line ${index + 1}" }
            val key = line.substring(0, separator).trim()
            val value = line.substring(separator + 1).trim()
            require(key.matches(SAFE_ENVIRONMENT_NAME)) { "Unsafe plugin-local .env key on line ${index + 1}" }
            require(value.isNotBlank() && '\n' !in value && '\r' !in value) {
                "Blank plugin-local .env value on line ${index + 1}"
            }
            require(result.putIfAbsent(key, value) == null) {
                "Duplicate plugin-local .env key on line ${index + 1}"
            }
        }
        return result
    }

    private companion object {
        val SAFE_ENVIRONMENT_NAME = Regex("[A-Z][A-Z0-9_]{2,80}")
    }
}
