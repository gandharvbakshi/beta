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
import java.io.InputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal class SwiggyDraftStore(
    context: Context,
    private val fileName: String = DEFAULT_FILE_NAME,
    private val expireAfterMillis: Long? = TTL_MILLIS,
) {
    init {
        require(isSafeBasename(fileName)) {
            "Draft file name must be a simple basename."
        }
        require(expireAfterMillis == null || expireAfterMillis >= 0L) {
            "Expiry must be non-negative or disabled."
        }
    }

    private val appContext = context.applicationContext
    private val file = File(appContext.noBackupFilesDir, fileName)
    private val atomicFile = AtomicFile(file)

    @Volatile
    private var cachedKey: SecretKey? = null

    fun load(nowMillis: Long = System.currentTimeMillis()): String? =
        loadIncludingEmpty(nowMillis)?.takeIf { it.isNotBlank() }

    /** Returns valid stored text including an empty tombstone; null means missing, expired, or unreadable. */
    fun loadIncludingEmpty(nowMillis: Long = System.currentTimeMillis()): String? = synchronized(lock) {
        readStoredRecord(nowMillis)?.let { record ->
            when {
                record.schemaVersion == LEGACY_SCHEMA_VERSION -> record.text
                record.schemaVersion == RECOVERY_SCHEMA_VERSION && isMainDraftFile -> record.recovery?.instruction
                else -> null
            }
        }
    }

    /** Open or migrate the main draft's exact committed text into a new durable lineage. */
    internal fun openRecovery(expectedText: String, nowMillis: Long = System.currentTimeMillis()): GroceryIntentRecoveryRecord? = synchronized(lock) {
        if (!isMainDraftFile || nowMillis < 0L || !isRecoveryText(expectedText)) return null
        val stored = readStoredRecord(nowMillis) ?: return null
        when (stored.schemaVersion) {
            RECOVERY_SCHEMA_VERSION -> stored.recovery?.takeIf { it.instruction == expectedText }
            LEGACY_SCHEMA_VERSION -> {
                if (stored.text != expectedText) return null
                val migrated = runCatching { GroceryIntentRecoveryRecord.begin(stored.text) }.getOrNull() ?: return null
                if (!writeRecoveryRecord(migrated, nowMillis)) return null
                readStoredRecord(nowMillis)?.recovery?.takeIf { it == migrated && it.instruction == expectedText }
            }
            else -> null
        }
    }

    /** Read an existing schema-2 lineage; this never migrates a legacy draft. */
    internal fun loadRecovery(nowMillis: Long = System.currentTimeMillis()): GroceryIntentRecoveryRecord? = synchronized(lock) {
        if (!isMainDraftFile || nowMillis < 0L) return null
        readStoredRecord(nowMillis)?.takeIf { it.schemaVersion == RECOVERY_SCHEMA_VERSION }?.recovery
    }

    /** An in-flight edit suggestion must not adopt another request, even if the
     * composer changed away and back to identical text. Legacy writes also use
     * fresh authenticated ciphertext; do not migrate a draft just to start a read. */
    internal fun editHintGeneration(): ByteArray? = synchronized(lock) {
        if (!isMainDraftFile) return@synchronized null
        runCatching { atomicFile.openRead().use { readBoundedBytes(it) } }.getOrNull()
            ?.takeIf { it.isNotEmpty() }?.let { java.security.MessageDigest.getInstance("SHA-256").digest(it) }
    }

    internal fun openEditHintRecovery(expected: String, generation: ByteArray): GroceryIntentRecoveryRecord? =
        synchronized(lock) {
            if (editHintGeneration()?.contentEquals(generation) != true) return@synchronized null
            openRecovery(expected)?.takeIf { it.dirty == null }
        }

    /** Full-record compare-and-set; retry after an ambiguous successful write is idempotent. */
    internal fun compareAndSetRecovery(
        expected: GroceryIntentRecoveryRecord,
        updated: GroceryIntentRecoveryRecord,
        nowMillis: Long = System.currentTimeMillis(),
    ): Boolean = synchronized(lock) {
        if (!isMainDraftFile || nowMillis < 0L) return false
        val current = readStoredRecord(nowMillis)?.takeIf { it.schemaVersion == RECOVERY_SCHEMA_VERSION }?.recovery
            ?: return false
        if (current == expected && expected == updated) return true
        if (!expected.acceptsSuccessor(updated)) return false
        if (current == updated) return true
        if (current != expected) return false
        writeRecoveryRecord(updated, nowMillis)
    }

    /** Composer writes preserve an unchanged lineage, but a new text starts legacy lineage-free storage. */
    internal fun saveComposer(text: String, nowMillis: Long = System.currentTimeMillis()): Boolean = synchronized(lock) {
        if (!isMainDraftFile) return save(text, nowMillis)
        if (nowMillis < 0L || !isRecoveryText(text)) return false
        val current = readStoredRecord(nowMillis)?.takeIf { it.schemaVersion == RECOVERY_SCHEMA_VERSION }?.recovery
        if (current?.instruction == text) return true
        saveLegacy(text, nowMillis, MAX_RECOVERY_TEXT_CHARS)
    }

    /** Legacy editor checkpoints only replace the exact committed source they opened. */
    internal fun saveConfirmedComposerEdit(expected: String, edited: String, nowMillis: Long = System.currentTimeMillis()): Boolean = synchronized(lock) {
        if (!isMainDraftFile) return false
        val stored = readStoredRecord(nowMillis) ?: return false
        val current = stored.recovery?.instruction ?: stored.text
        if (current != expected) return false
        // Pausing the legacy editor is not a new edit. Preserve even a dirty recovery record.
        if (edited == expected) return true
        if (stored.recovery?.dirty != null) return false
        saveComposer(edited, nowMillis)
    }

    /** True when the atomic record or any AtomicFile recovery artifact exists. */
    fun hasStoredRecord(): Boolean = synchronized(lock) {
        file.exists() || File(file.parentFile, "${file.name}.bak").exists() ||
            File(file.parentFile, "${file.name}.new").exists()
    }

    /** Serializes compound read/merge/write operations across every store instance in this process. */
    internal fun <T> withStoreLock(block: () -> T): T = synchronized(lock, block)

    fun save(text: String, nowMillis: Long = System.currentTimeMillis()): Boolean = synchronized(lock) {
        saveLegacy(text, nowMillis)
    }

    private fun saveLegacy(text: String, nowMillis: Long, maxChars: Int = MAX_TEXT_CHARS): Boolean {
        if (nowMillis < 0L) return false
        if (text.isBlank()) return clear(nowMillis)
        if (text.length > maxChars) return false
        val key = getKey(createIfMissing = true) ?: return false
        val payload = runCatching { writeRecord(text, nowMillis) }.getOrNull() ?: return false
        val encrypted = runCatching { encrypt(payload, key) }.getOrNull() ?: return false
        return encrypted.size <= MAX_FILE_BYTES && writeAtomically(encrypted)
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
        if (bytes.size > maxFileBytes) return false
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

    private fun readBoundedBytes(input: InputStream, limit: Int = maxFileBytes): ByteArray {
        val buffer = ByteArrayOutputStream()
        val chunk = ByteArray(4096)
        var total = 0
        while (true) {
            val read = input.read(chunk)
            if (read <= 0) break
            total += read
            if (total > limit) return ByteArray(0)
            buffer.write(chunk, 0, read)
        }
        return buffer.toByteArray()
    }

    private fun hasRecoveryArtifacts(): Boolean =
        File(file.parentFile, "${file.name}.bak").exists() ||
            File(file.parentFile, "${file.name}.new").exists()

    private fun readBackMatches(expected: ByteArray): Boolean {
        if (!isMainDraftFile && (hasRecoveryArtifacts() || !file.exists())) return false
        return runCatching {
            atomicFile.openRead().use { input -> readBoundedBytes(input) }
        }.getOrNull()?.contentEquals(expected) == true
    }

    private val isMainDraftFile: Boolean get() = fileName == DEFAULT_FILE_NAME
    private val maxFileBytes: Int get() = if (isMainDraftFile) MAX_RECOVERY_FILE_BYTES else MAX_FILE_BYTES

    private fun readStoredRecord(nowMillis: Long): DraftRecord? {
        if (nowMillis < 0L || (!isMainDraftFile && hasRecoveryArtifacts())) return null
        if (isMainDraftFile && !file.exists() && !File(file.parentFile, "${file.name}.bak").exists()) return null
        if (!isMainDraftFile && (!file.exists() || file.length() <= 0L || file.length() > maxFileBytes)) return null
        val key = getKey(createIfMissing = false) ?: return null
        val raw = runCatching {
            atomicFile.openRead().use { input -> readBoundedBytes(input) }
        }.getOrNull() ?: return null
        if (raw.isEmpty() || raw.size > maxFileBytes) return null
        return runCatching {
            val record = readRecord(decrypt(raw, key))
            val legacyLimit = if (isMainDraftFile) MAX_RECOVERY_TEXT_CHARS else MAX_TEXT_CHARS
            when {
                record.timestampMillis < 0L -> null
                expireAfterMillis != null && record.timestampMillis > nowMillis -> null
                expireAfterMillis?.let { nowMillis - record.timestampMillis > it } == true -> null
                record.schemaVersion == LEGACY_SCHEMA_VERSION && record.text.length > legacyLimit -> null
                record.schemaVersion == RECOVERY_SCHEMA_VERSION && !isMainDraftFile -> null
                record.schemaVersion == RECOVERY_SCHEMA_VERSION && record.recovery == null -> null
                record.schemaVersion !in setOf(LEGACY_SCHEMA_VERSION, RECOVERY_SCHEMA_VERSION) -> null
                else -> record
            }
        }.getOrNull()
    }

    private fun isRecoveryText(text: String): Boolean =
        runCatching { GroceryIntentRequestRevision.begin(text) }.isSuccess

    private fun writeRecoveryRecord(record: GroceryIntentRecoveryRecord, nowMillis: Long): Boolean {
        if (!isMainDraftFile || nowMillis < 0L) return false
        val key = getKey(createIfMissing = true) ?: return false
        val payload = runCatching { writeRecoveryPayload(record, nowMillis) }.getOrNull() ?: return false
        if (payload.size > MAX_RECOVERY_PAYLOAD_BYTES) return false
        val encrypted = runCatching { encrypt(payload, key) }.getOrNull() ?: return false
        return encrypted.size <= MAX_RECOVERY_FILE_BYTES && writeAtomically(encrypted) &&
            readStoredRecord(nowMillis)?.recovery == record
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
        if (raw.size > maxFileBytes) throw IllegalArgumentException("draft file too large")
        return DataInputStream(ByteArrayInputStream(raw)).use { input ->
            val version = input.readInt()
            require(version == FORMAT_VERSION)
            val ivSize = input.readInt()
            require(ivSize in 12..32)
            val iv = ByteArray(ivSize).also { input.readFully(it) }
            val ciphertextSize = input.readInt()
            require(ciphertextSize in 1..maxFileBytes)
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
                output.writeInt(LEGACY_SCHEMA_VERSION)
                output.writeLong(nowMillis)
                output.writeUTF(text)
            }
            buffer.toByteArray()
        }
    }

    private fun writeRecoveryPayload(record: GroceryIntentRecoveryRecord, nowMillis: Long): ByteArray =
        ByteArrayOutputStream().use { buffer ->
            DataOutputStream(buffer).use { output ->
                output.writeInt(RECOVERY_SCHEMA_VERSION)
                output.writeLong(nowMillis)
                output.writeUTF(record.requestId)
                output.writeUTF(record.originalInstruction)
                output.writeUTF(record.instruction)
                output.writeInt(record.revision)
                output.writeLong(record.mutationGeneration)
                output.writeInt(record.changedRanges.size)
                record.changedRanges.forEach { range ->
                    output.writeInt(range.startUtf16)
                    output.writeInt(range.endUtf16)
                }
                val edit = record.dirty
                output.writeByte(if (edit == null) 0 else 1)
                if (edit != null) {
                    output.writeUTF(edit.quote.text)
                    output.writeInt(edit.quote.startUtf16)
                    output.writeInt(edit.quote.endUtf16)
                    output.writeUTF(edit.text)
                    // Keep PHRASE byte-compatible with the legacy writer. Old readers reject
                    // WHOLE_MESSAGE's trailing discriminator rather than downgrade its meaning.
                    if (edit.kind != GroceryIntentEditKind.PHRASE) output.writeUTF(edit.kind.name)
                }
            }
            buffer.toByteArray()
        }

    private fun readRecord(payload: ByteArray): DraftRecord {
        return DataInputStream(ByteArrayInputStream(payload)).use { input ->
            val schemaVersion = input.readInt()
            val timestampMillis = input.readLong()
            val record = when (schemaVersion) {
                LEGACY_SCHEMA_VERSION -> DraftRecord(
                    schemaVersion = schemaVersion,
                    timestampMillis = timestampMillis,
                    text = input.readUTF(),
                )
                RECOVERY_SCHEMA_VERSION -> {
                    val requestId = input.readUTF()
                    val original = input.readUTF()
                    val current = input.readUTF()
                    val revision = input.readInt()
                    val mutationGeneration = input.readLong()
                    val count = input.readInt()
                    require(count in 0..MAX_CHANGED_RANGES && count <= input.available() / 8)
                    val ranges = List(count) {
                        GroceryIntentRecoveryRecord.ChangedRange(input.readInt(), input.readInt())
                    }
                    val edit = when (input.readUnsignedByte()) {
                        0 -> null
                        1 -> {
                            val quoteText = input.readUTF()
                            val start = input.readInt()
                            val end = input.readInt()
                            val editText = input.readUTF()
                            // Legacy recovery payloads end here and represent phrase edits.
                            val kind = if (input.available() == 0) GroceryIntentEditKind.PHRASE
                                else GroceryIntentEditKind.valueOf(input.readUTF())
                            GroceryIntentRecoveryRecord.PendingEdit(
                                GroceryIntentReviewV2Quote(quoteText, start, end), editText, kind,
                            )
                        }
                        else -> throw IllegalArgumentException("invalid dirty marker")
                    }
                    val recovery = GroceryIntentRecoveryRecord.restore(
                        requestId, original, current, revision, mutationGeneration, ranges, edit,
                    ) ?: throw IllegalArgumentException("invalid recovery record")
                    DraftRecord(schemaVersion, timestampMillis, recovery.instruction, recovery)
                }
                else -> throw IllegalArgumentException("invalid draft schema")
            }
            require(input.available() == 0)
            record
        }
    }

    private data class DraftRecord(
        val schemaVersion: Int,
        val timestampMillis: Long,
        val text: String,
        val recovery: GroceryIntentRecoveryRecord? = null,
    )

    private companion object {
        const val DEFAULT_FILE_NAME = "swiggy-draft-v1.bin"
        const val KEYSTORE_ALIAS = "beta_swiggy_draft_v1"
        const val LEGACY_SCHEMA_VERSION = 1
        const val RECOVERY_SCHEMA_VERSION = 2
        const val FORMAT_VERSION = 1
        const val MAX_TEXT_CHARS = 8000
        const val MAX_RECOVERY_TEXT_CHARS = 12_000
        const val MAX_CHANGED_RANGES = 6000
        const val TTL_MILLIS = 24L * 60L * 60L * 1000L
        const val MAX_FILE_BYTES = 64 * 1024
        const val MAX_RECOVERY_FILE_BYTES = 256 * 1024
        const val MAX_RECOVERY_PAYLOAD_BYTES = MAX_RECOVERY_FILE_BYTES - 64
        const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
        private val lock = Any()
    }
}
