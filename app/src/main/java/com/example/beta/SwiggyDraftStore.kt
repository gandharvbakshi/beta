package com.example.beta

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal class SwiggyDraftStore(
    context: Context,
    private val fileName: String = DEFAULT_FILE_NAME,
) {
    init {
        require(isSafeBasename(fileName)) {
            "Draft file name must be a simple basename."
        }
    }

    private val appContext = context.applicationContext
    private val file = File(appContext.noBackupFilesDir, fileName)
    private val atomicFile = AtomicFile(file)

    @Volatile
    private var cachedKey: SecretKey? = null

    fun load(nowMillis: Long = System.currentTimeMillis()): String? = synchronized(lock) {
        if (!file.exists() || file.length() <= 0L || file.length() > MAX_FILE_BYTES) return null
        val key = getKey(createIfMissing = false) ?: return null
        val raw = runCatching { readBoundedBytes() }.getOrNull() ?: return null
        if (raw.isEmpty() || raw.size > MAX_FILE_BYTES) return null

        return runCatching {
            val payload = decrypt(raw, key)
            val record = readRecord(payload)
            when {
                record.schemaVersion != SCHEMA_VERSION -> null
                record.text.isBlank() -> null
                record.timestampMillis < 0L -> null
                record.timestampMillis > nowMillis -> null
                nowMillis - record.timestampMillis > TTL_MILLIS -> null
                record.text.length > MAX_TEXT_CHARS -> null
                else -> record.text
            }
        }.getOrNull()
    }

    fun save(text: String, nowMillis: Long = System.currentTimeMillis()): Boolean = synchronized(lock) {
        if (nowMillis < 0L) return false
        if (text.isBlank()) return clear(nowMillis)
        if (text.length > MAX_TEXT_CHARS) return false
        val key = getKey(createIfMissing = true) ?: return false
        val payload = runCatching { writeRecord(text, nowMillis) }.getOrNull() ?: return false
        val encrypted = runCatching { encrypt(payload, key) }.getOrNull() ?: return false
        return writeAtomically(encrypted)
    }

    fun clear(): Boolean = synchronized(lock) {
        return clear(System.currentTimeMillis())
    }

    private fun clear(nowMillis: Long): Boolean {
        val key = getKey(createIfMissing = true) ?: return false
        val payload = runCatching { writeRecord("", nowMillis) }.getOrNull() ?: return false
        val encrypted = runCatching { encrypt(payload, key) }.getOrNull() ?: return false
        return writeAtomically(encrypted) && runCatching { readBackMatches(encrypted) }.getOrDefault(false)
    }

    private fun writeAtomically(bytes: ByteArray): Boolean {
        val out = runCatching { atomicFile.startWrite() }.getOrNull() ?: return false
        return try {
            out.write(bytes)
            out.flush()
            atomicFile.finishWrite(out)
            readBackMatches(bytes)
        } catch (_: Throwable) {
            runCatching { atomicFile.failWrite(out) }
            false
        }
    }

    private fun readBoundedBytes(): ByteArray {
        FileInputStream(file).use { input ->
            val buffer = ByteArrayOutputStream()
            val chunk = ByteArray(4096)
            var total = 0
            while (true) {
                val read = input.read(chunk)
                if (read <= 0) break
                total += read
                if (total > MAX_FILE_BYTES) return ByteArray(0)
                buffer.write(chunk, 0, read)
            }
            return buffer.toByteArray()
        }
    }

    private fun readBackMatches(expected: ByteArray): Boolean {
        if (!file.exists() || file.length().toInt() != expected.size) return false
        return runCatching { readBoundedBytes() }.getOrNull()?.contentEquals(expected) == true
    }

    private fun getKey(createIfMissing: Boolean): SecretKey? {
        cachedKey?.let { return it }
        return runCatching {
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (keyStore.getKey(KEYSTORE_ALIAS, null) as? SecretKey)
                ?: if (createIfMissing) generateKey() else null
        }.getOrNull()?.also { cachedKey = it }
    }

    private fun generateKey(): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private fun isSafeBasename(name: String): Boolean {
        val trimmed = name.trim()
        return trimmed.isNotEmpty() &&
            trimmed == File(trimmed).name &&
            !trimmed.contains("..") &&
            !trimmed.contains('/') &&
            !trimmed.contains('\\') &&
            !trimmed.contains('\u0000')
    }

    private fun encrypt(payload: ByteArray, key: SecretKey): ByteArray {
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val ciphertext = cipher.doFinal(payload)
        return ByteArrayOutputStream().use { buffer ->
            DataOutputStream(buffer).use { output ->
                output.writeInt(FORMAT_VERSION)
                output.writeInt(cipher.iv.size)
                output.write(cipher.iv)
                output.writeInt(ciphertext.size)
                output.write(ciphertext)
            }
            buffer.toByteArray()
        }
    }

    private fun decrypt(raw: ByteArray, key: SecretKey): ByteArray {
        if (raw.size > MAX_FILE_BYTES) throw IllegalArgumentException("draft file too large")
        return DataInputStream(ByteArrayInputStream(raw)).use { input ->
            val version = input.readInt()
            require(version == FORMAT_VERSION)
            val ivSize = input.readInt()
            require(ivSize in 12..32)
            val iv = ByteArray(ivSize).also { input.readFully(it) }
            val ciphertextSize = input.readInt()
            require(ciphertextSize in 1..MAX_FILE_BYTES)
            val ciphertext = ByteArray(ciphertextSize).also { input.readFully(it) }
            require(input.available() == 0)
            val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            cipher.doFinal(ciphertext)
        }
    }

    private fun writeRecord(text: String, nowMillis: Long): ByteArray {
        return ByteArrayOutputStream().use { buffer ->
            DataOutputStream(buffer).use { output ->
                output.writeInt(SCHEMA_VERSION)
                output.writeLong(nowMillis)
                output.writeUTF(text)
            }
            buffer.toByteArray()
        }
    }

    private fun readRecord(payload: ByteArray): DraftRecord {
        return DataInputStream(ByteArrayInputStream(payload)).use { input ->
            val schemaVersion = input.readInt()
            val timestampMillis = input.readLong()
            val text = input.readUTF()
            require(input.available() == 0)
            DraftRecord(
                schemaVersion = schemaVersion,
                timestampMillis = timestampMillis,
                text = text,
            )
        }
    }

    private data class DraftRecord(
        val schemaVersion: Int,
        val timestampMillis: Long,
        val text: String,
    )

    private companion object {
        const val DEFAULT_FILE_NAME = "swiggy-draft-v1.bin"
        const val KEYSTORE_ALIAS = "beta_swiggy_draft_v1"
        const val SCHEMA_VERSION = 1
        const val FORMAT_VERSION = 1
        const val MAX_TEXT_CHARS = 8000
        const val TTL_MILLIS = 24L * 60L * 60L * 1000L
        const val MAX_FILE_BYTES = 64 * 1024
        const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
        private val lock = Any()
    }
}
