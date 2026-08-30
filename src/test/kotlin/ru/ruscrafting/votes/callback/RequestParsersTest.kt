package ru.ruscrafting.votes.callback

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.shouldBe

class RequestParsersTest : StringSpec({
    "form parser decodes UTF-8 percent encoding without accepting duplicate fields" {
        FormBodyParser.parse("nickname=Alex_42&note=%D1%82%D0%B5%D1%81%D1%82".toByteArray()) shouldContainExactly
            mapOf("nickname" to "Alex_42", "note" to "тест")

        shouldThrow<CallbackRejected> {
            FormBodyParser.parse("nickname=one&nickname=two".toByteArray())
        }.safeCode shouldBe "duplicate_field"
    }

    "multipart parser rejects file uploads and duplicate vote fields" {
        val boundary = "Boundary42"
        val upload = buildString {
            append("--$boundary\r\n")
            append("Content-Disposition: form-data; name=\"nick\"; filename=\"payload.txt\"\r\n\r\n")
            append("Steve\r\n--$boundary--\r\n")
        }.toByteArray()
        shouldThrow<CallbackRejected> { MultipartFormParser.parse(upload, boundary) }

        val duplicate = multipart(boundary, linkedMapOf("nick" to "Steve"))
            .toString(Charsets.UTF_8)
            .replace("--$boundary--", "--$boundary\r\nContent-Disposition: form-data; name=\"nick\"\r\n\r\nAlex\r\n--$boundary--")
            .toByteArray()
        shouldThrow<CallbackRejected> { MultipartFormParser.parse(duplicate, boundary) }
            .safeCode shouldBe "duplicate_field"
    }

    "JSON parser rejects duplicate keys and nested callback payloads" {
        shouldThrow<CallbackRejected> {
            JsonBodyParser.objectFields("{\"event_id\":1,\"event_id\":2}".toByteArray())
        }.safeCode shouldBe "invalid_json"
        shouldThrow<CallbackRejected> {
            JsonBodyParser.objectFields("{\"event_id\":{\"nested\":1}}".toByteArray())
        }.safeCode shouldBe "unsupported_json_value"
    }
})

private fun multipart(boundary: String, fields: Map<String, String>): ByteArray = buildString {
    fields.forEach { (name, value) ->
        append("--$boundary\r\n")
        append("Content-Disposition: form-data; name=\"$name\"\r\n\r\n")
        append(value)
        append("\r\n")
    }
    append("--$boundary--\r\n")
}.toByteArray()
