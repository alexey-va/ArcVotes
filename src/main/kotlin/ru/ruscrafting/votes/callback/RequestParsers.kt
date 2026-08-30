package ru.ruscrafting.votes.callback

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.StreamReadConstraints
import com.fasterxml.jackson.core.StreamReadFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Locale

object ContentTypes {
    fun requireForm(value: String?) {
        val parsed = parse(value)
        if (parsed.mediaType != "application/x-www-form-urlencoded") throw CallbackRejected(415, "unsupported_media_type")
        val charset = parsed.parameters["charset"]
        if (charset != null && !charset.equals("utf-8", ignoreCase = true)) {
            throw CallbackRejected(415, "unsupported_charset")
        }
    }

    fun requireJson(value: String?) {
        val parsed = parse(value)
        if (parsed.mediaType != "application/json") throw CallbackRejected(415, "unsupported_media_type")
        val charset = parsed.parameters["charset"]
        if (charset != null && !charset.equals("utf-8", ignoreCase = true)) {
            throw CallbackRejected(415, "unsupported_charset")
        }
    }

    fun requireMultipartBoundary(value: String?): String {
        val parsed = parse(value)
        if (parsed.mediaType != "multipart/form-data") throw CallbackRejected(415, "unsupported_media_type")
        val boundary = parsed.parameters["boundary"] ?: throw CallbackRejected(400, "missing_boundary")
        if (!boundary.matches(Regex("[A-Za-z0-9'()+_,./:=?-]{1,70}"))) {
            throw CallbackRejected(400, "invalid_boundary")
        }
        return boundary
    }

    private fun parse(value: String?): ParsedContentType {
        val raw = value?.trim()?.takeIf(String::isNotEmpty) ?: throw CallbackRejected(415, "missing_content_type")
        if (raw.length > 256 || raw.any { it == '\r' || it == '\n' }) throw CallbackRejected(400, "invalid_content_type")
        val parts = raw.split(';')
        val mediaType = parts.first().trim().lowercase(Locale.ROOT)
        if (!mediaType.matches(Regex("[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+"))) {
            throw CallbackRejected(400, "invalid_content_type")
        }
        val parameters = linkedMapOf<String, String>()
        parts.drop(1).forEach { rawParameter ->
            val parameter = rawParameter.trim()
            val separator = parameter.indexOf('=')
            if (separator <= 0) throw CallbackRejected(400, "invalid_content_type")
            val name = parameter.substring(0, separator).trim().lowercase(Locale.ROOT)
            var parameterValue = parameter.substring(separator + 1).trim()
            if (parameterValue.length >= 2 && parameterValue.startsWith('"') && parameterValue.endsWith('"')) {
                parameterValue = parameterValue.substring(1, parameterValue.length - 1)
            }
            if (!name.matches(Regex("[a-z0-9_-]{1,32}")) || parameterValue.isEmpty()) {
                throw CallbackRejected(400, "invalid_content_type")
            }
            if (parameters.putIfAbsent(name, parameterValue) != null) throw CallbackRejected(400, "duplicate_parameter")
        }
        return ParsedContentType(mediaType, parameters)
    }

    private data class ParsedContentType(val mediaType: String, val parameters: Map<String, String>)
}

object FormBodyParser {
    fun parse(body: ByteArray, maximumFields: Int = 16): Map<String, String> {
        if (body.isEmpty()) throw CallbackRejected(400, "empty_body")
        if (body.any { it.toInt() and 0xff > 0x7f }) throw CallbackRejected(400, "invalid_form_encoding")
        val raw = body.toString(StandardCharsets.US_ASCII)
        val result = linkedMapOf<String, String>()
        raw.split('&').forEach { pair ->
            if (pair.isEmpty()) throw CallbackRejected(400, "invalid_form_field")
            val separator = pair.indexOf('=')
            if (separator <= 0) throw CallbackRejected(400, "invalid_form_field")
            val name = decode(pair.substring(0, separator))
            val value = decode(pair.substring(separator + 1))
            if (!name.matches(Regex("[A-Za-z][A-Za-z0-9_.-]{0,63}"))) throw CallbackRejected(400, "invalid_field_name")
            if (value.length > 4_096) throw CallbackRejected(400, "field_too_large")
            if (result.putIfAbsent(name, value) != null) throw CallbackRejected(400, "duplicate_field")
            if (result.size > maximumFields) throw CallbackRejected(400, "too_many_fields")
        }
        return result
    }

    private fun decode(value: String): String {
        val output = ByteArrayOutputStream(value.length)
        var index = 0
        while (index < value.length) {
            when (val character = value[index]) {
                '+' -> {
                    output.write(' '.code)
                    index++
                }
                '%' -> {
                    if (index + 2 >= value.length) throw CallbackRejected(400, "invalid_percent_encoding")
                    val high = value[index + 1].digitToIntOrNull(16) ?: throw CallbackRejected(400, "invalid_percent_encoding")
                    val low = value[index + 2].digitToIntOrNull(16) ?: throw CallbackRejected(400, "invalid_percent_encoding")
                    output.write((high shl 4) or low)
                    index += 3
                }
                else -> {
                    if (character.code !in 0x20..0x7e) throw CallbackRejected(400, "invalid_form_encoding")
                    output.write(character.code)
                    index++
                }
            }
        }
        return decodeUtf8(output.toByteArray())
    }
}

object MultipartFormParser {
    fun parse(body: ByteArray, boundary: String, maximumFields: Int = 16): Map<String, String> {
        val raw = body.toString(StandardCharsets.ISO_8859_1)
        val marker = "--$boundary"
        if (!raw.startsWith(marker)) throw CallbackRejected(400, "invalid_multipart")
        var cursor = 0
        val result = linkedMapOf<String, String>()
        while (true) {
            if (!raw.startsWith(marker, cursor)) throw CallbackRejected(400, "invalid_multipart")
            cursor += marker.length
            if (raw.startsWith("--", cursor)) {
                cursor += 2
                if (cursor == raw.length) return result
                if (raw.substring(cursor) == "\r\n") return result
                throw CallbackRejected(400, "invalid_multipart_epilogue")
            }
            if (!raw.startsWith("\r\n", cursor)) throw CallbackRejected(400, "invalid_multipart")
            cursor += 2
            val headersEnd = raw.indexOf("\r\n\r\n", cursor)
            if (headersEnd < 0 || headersEnd - cursor > 1_024) throw CallbackRejected(400, "invalid_part_headers")
            val headers = parsePartHeaders(raw.substring(cursor, headersEnd))
            cursor = headersEnd + 4
            val nextMarker = raw.indexOf("\r\n$marker", cursor)
            if (nextMarker < 0) throw CallbackRejected(400, "unterminated_part")
            val valueBytes = raw.substring(cursor, nextMarker).toByteArray(StandardCharsets.ISO_8859_1)
            if (valueBytes.size > 4_096) throw CallbackRejected(400, "field_too_large")
            val value = decodeUtf8(valueBytes)
            if ('\r' in value || '\n' in value) throw CallbackRejected(400, "invalid_field_value")
            val name = headers.fieldName
            if (result.putIfAbsent(name, value) != null) throw CallbackRejected(400, "duplicate_field")
            if (result.size > maximumFields) throw CallbackRejected(400, "too_many_fields")
            cursor = nextMarker + 2
        }
    }

    private fun parsePartHeaders(raw: String): PartHeaders {
        val values = linkedMapOf<String, String>()
        raw.split("\r\n").forEach { line ->
            val separator = line.indexOf(':')
            if (separator <= 0 || line.any { it.code !in 0x20..0x7e }) throw CallbackRejected(400, "invalid_part_headers")
            val name = line.substring(0, separator).trim().lowercase(Locale.ROOT)
            val value = line.substring(separator + 1).trim()
            if (values.putIfAbsent(name, value) != null) throw CallbackRejected(400, "duplicate_part_header")
        }
        val disposition = values["content-disposition"] ?: throw CallbackRejected(400, "missing_content_disposition")
        val match = Regex("form-data;\\s*name=\"([A-Za-z][A-Za-z0-9_.-]{0,63})\"").matchEntire(disposition)
            ?: throw CallbackRejected(400, "invalid_content_disposition")
        val unsupported = values.keys - setOf("content-disposition", "content-type")
        if (unsupported.isNotEmpty()) throw CallbackRejected(400, "unsupported_part_header")
        values["content-type"]?.let { contentType ->
            if (!contentType.equals("text/plain", ignoreCase = true) &&
                !contentType.equals("text/plain; charset=utf-8", ignoreCase = true)
            ) throw CallbackRejected(415, "unsupported_part_type")
        }
        return PartHeaders(match.groupValues[1])
    }

    private data class PartHeaders(val fieldName: String)
}

object JsonBodyParser {
    private val mapper = ObjectMapper(
        JsonFactory.builder()
            .streamReadConstraints(
                StreamReadConstraints.builder()
                    .maxNestingDepth(8)
                    .maxStringLength(4_096)
                    .maxNumberLength(64)
                    .build(),
            )
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build(),
    )

    fun objectFields(body: ByteArray, maximumFields: Int = 32): Map<String, JsonNode> {
        val root = try {
            mapper.readTree(body)
        } catch (_: Exception) {
            throw CallbackRejected(400, "invalid_json")
        }
        if (root == null || !root.isObject) throw CallbackRejected(400, "invalid_json_object")
        val fields = linkedMapOf<String, JsonNode>()
        root.fields().forEachRemaining { (name, value) ->
            if (!name.matches(Regex("[A-Za-z][A-Za-z0-9_.-]{0,63}"))) throw CallbackRejected(400, "invalid_field_name")
            if (!value.isValueNode || value.isNull || value.isBinary) throw CallbackRejected(400, "unsupported_json_value")
            fields[name] = value
            if (fields.size > maximumFields) throw CallbackRejected(400, "too_many_fields")
        }
        return fields
    }

    fun parseTree(body: ByteArray): JsonNode = try {
        mapper.readTree(body) ?: throw CallbackUpstreamFailure("empty_upstream_json")
    } catch (failure: CallbackUpstreamFailure) {
        throw failure
    } catch (_: Exception) {
        throw CallbackUpstreamFailure("invalid_upstream_json")
    }
}

class CallbackUpstreamFailure(val safeCode: String) : RuntimeException(safeCode) {
    init {
        require(safeCode.matches(Regex("[a-z0-9_]{1,48}"))) { "Upstream callback code is unsafe" }
    }
}

private fun decodeUtf8(bytes: ByteArray): String = try {
    StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()
} catch (_: Exception) {
    throw CallbackRejected(400, "invalid_utf8")
}
