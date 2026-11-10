package com.example.beta

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BackendEndpointConfigTest {
    @Test
    fun acceptsHostedHttpsAndJoinsPathsWithoutDuplicateSlashes() {
        val base = "https://beta-backend-staging-kvuem5t7mq-el.a.run.app"
        val config = read(configJson(base))
        assertEquals(base, config.baseUrl)
        assertEquals("$base/feedback", config.path("feedback"))
        assertEquals("$base/swiggy/connect", config.path("/swiggy/connect"))
    }

    @Test
    fun acceptsOnlyExplicitHttpLoopbackHosts() {
        for (host in listOf("127.0.0.1", "localhost", "10.0.2.2")) {
            val config = read(configJson("http://$host:8080"))
            assertEquals("http://$host:8080", config.baseUrl)
        }
    }

    @Test
    fun rejectsMissingDuplicateUnknownAndIncorrectlyTypedFields() {
        val invalid = listOf(
            "{}",
            "{\"version\":1}",
            "{\"backend_base_url\":\"https://example.com\"}",
            "{\"version\":1,\"backend_base_url\":\"https://example.com\",\"other\":0}",
            "{\"version\":1,\"version\":1,\"backend_base_url\":\"https://example.com\"}",
            "{\"version\":1,\"backend_base_url\":\"https://example.com\",\"backend_base_url\":\"https://other.example\"}",
            "{\"version\":true,\"backend_base_url\":\"https://example.com\"}",
            "{\"version\":1.0,\"backend_base_url\":\"https://example.com\"}",
            "{\"version\":2,\"backend_base_url\":\"https://example.com\"}",
            "{\"version\":\"1\",\"backend_base_url\":\"https://example.com\"}",
            "{\"version\":1,\"backend_base_url\":false}",
        )
        invalid.forEach { json -> assertThrows(Exception::class.java) { read(json) } }
    }

    @Test
    fun rejectsMalformedOrTrailingJsonAndInvalidUtf8() {
        for (json in listOf(
            "{\"version\":1,\"backend_base_url\":\"https://example.com\"",
            "{\"version\":1,\"backend_base_url\":\"https://example.com\"} trailing",
            "{\"version\":1,\"backend_base_url\":\"https://example.com\"}{}",
        )) assertThrows(Exception::class.java) { read(json) }

        val valid = configJson("https://example.com").toByteArray(Charsets.UTF_8)
        val malformedUtf8 = valid + byteArrayOf(0xC3.toByte(), 0x28)
        assertThrows(Exception::class.java) { read(malformedUtf8) }
    }

    @Test
    fun rejectsOversizeAsset() {
        val tooLarge = ByteArray(4097) { ' '.code.toByte() }
        assertThrows(Exception::class.java) { read(tooLarge) }
    }

    @Test
    fun rejectsRemoteHttpAndUrlsWithAuthorityOrPathComponents() {
        val invalid = listOf(
            "http://example.com",
            "http://8.8.8.8:8080",
            "ftp://example.com",
            "https://user@example.com",
            "https://example.com/path",
            "https://example.com/",
            "https://example.com?debug=1",
            "https://example.com#fragment",
            "https://example.com:0",
            "https://example.com:65536",
            "https://example.com:bad",
            "https:///missing-host",
            "https://example.com ",
        )
        invalid.forEach { url -> assertThrows(Exception::class.java) { read(configJson(url)) } }
    }

    private fun read(json: String): BackendEndpointConfig = read(json.toByteArray(Charsets.UTF_8))

    private fun read(bytes: ByteArray): BackendEndpointConfig =
        BackendEndpointConfig.read(ByteArrayInputStream(bytes))

    private fun configJson(url: String) =
        "{\"version\":1,\"backend_base_url\":\"$url\"}"
}
