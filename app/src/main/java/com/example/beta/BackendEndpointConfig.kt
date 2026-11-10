package com.example.beta

import java.io.InputStream
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** The packaged asset is the runtime source, not a second release attestation. */
internal data class BackendEndpointConfig private constructor(val baseUrl: String) {
    fun path(path: String): String = "$baseUrl/${path.trimStart('/')}"

    companion object {
        const val ASSET_NAME = "beta_backend_config.json"
        private const val MAX_BYTES = 4096

        fun read(input: InputStream): BackendEndpointConfig {
            val bytes = ByteArray(MAX_BYTES + 1)
            var size = 0
            while (size < bytes.size) {
                val read = input.read(bytes, size, bytes.size - size)
                if (read < 0) break
                require(read > 0) { "Endpoint config unreadable" }
                size += read
            }
            require(size in 1..MAX_BYTES) { "Endpoint config size invalid" }
            val text = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, 0, size)).toString()
            val root = JsonParser(text).parseStrict() as? JsonValue.Obj
                ?: error("Endpoint config object required")
            require(root.members.keys == setOf("version", "backend_base_url"))
            require((root.members["version"] as? JsonValue.Num)?.raw == "1")
            val url = (root.members["backend_base_url"] as? JsonValue.Str)?.value
                ?: error("Endpoint config URL required")
            val uri = URI(url)
            require(url == url.trim() && uri.rawAuthority == uri.authority && uri.host != null)
            require(uri.rawUserInfo == null && uri.rawPath.isNullOrEmpty() &&
                uri.rawQuery == null && uri.rawFragment == null)
            require(uri.port == -1 || uri.port in 1..65535)
            val loopback = uri.host in setOf("127.0.0.1", "localhost", "10.0.2.2")
            require(uri.scheme == "https" || uri.scheme == "http" && loopback)
            return BackendEndpointConfig(url)
        }
    }
}

internal class BackendConfigurationUnavailableException : IllegalStateException("Backend configuration unavailable")
