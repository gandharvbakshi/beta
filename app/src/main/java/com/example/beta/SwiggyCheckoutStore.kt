package com.example.beta

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.util.UUID

/** Only an opaque attempt UUID, no payment data. Never expires an unresolved attempt. */
internal class SwiggyCheckoutStore(context: Context, name: String = "swiggy-checkout-attempt-v1") {
    private val file = File(context.applicationContext.noBackupFilesDir, name)
    private val handoffFile = File(context.applicationContext.noBackupFilesDir, "$name-opened")
    private val atomic = AtomicFile(file)
    private val handoffAtomic = AtomicFile(handoffFile)
    init { require(name.matches(Regex("[A-Za-z0-9_-]+"))) }
    fun isPending(): Boolean = synchronized(lock) {
        existsWithRecoveryFiles(file) || hasHandoffRecord()
    }
    fun load(): String? = synchronized(lock) {
        readUuid(atomic)
    }
    fun loadHandoff(): String? = synchronized(lock) { readUuid(handoffAtomic) }
    fun hasHandoffFor(id: String): Boolean = synchronized(lock) { loadHandoff() == id }
    fun hasHandoffRecord(): Boolean = synchronized(lock) { existsWithRecoveryFiles(handoffFile) }
    fun save(id: String): Boolean = synchronized(lock) {
        if (runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false).not()) return false
        if (isPending()) return false
        val out = runCatching { atomic.startWrite() }.getOrNull() ?: return false
        try {
            out.write(id.toByteArray(Charsets.US_ASCII))
            atomic.finishWrite(out)
            load() == id
        } catch (_: Exception) { runCatching { atomic.failWrite(out) }; false }
    }
    fun markHandoff(id: String): Boolean = synchronized(lock) {
        if (runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false).not()) return false
        if (load() != id) return false
        val existing = loadHandoff()
        if (existing == id) return true
        if (hasHandoffRecord()) return false
        val out = runCatching { handoffAtomic.startWrite() }.getOrNull() ?: return false
        try {
            out.write(id.toByteArray(Charsets.US_ASCII))
            handoffAtomic.finishWrite(out)
            loadHandoff() == id
        } catch (_: Exception) { runCatching { handoffAtomic.failWrite(out) }; false }
    }
    fun clearHandoff(): Boolean = synchronized(lock) {
        runCatching { handoffAtomic.delete() }.isSuccess && !hasHandoffRecord()
    }
    fun clear(): Boolean = synchronized(lock) {
        val clearedPrimary = runCatching { atomic.delete() }.isSuccess
        val clearedHandoff = runCatching { handoffAtomic.delete() }.isSuccess
        clearedPrimary && clearedHandoff && !isPending()
    }
    private fun readUuid(atomicFile: AtomicFile): String? = runCatching {
        atomicFile.openRead().use { input ->
            val bytes = ByteArray(37)
            val count = input.read(bytes)
            if (count != 36 || input.read() != -1) return@use null
            String(bytes, 0, count, Charsets.US_ASCII).takeIf { UUID.fromString(it).toString() == it }
        }
    }.getOrNull()
    private fun existsWithRecoveryFiles(target: File): Boolean =
        target.exists() || File(target.path + ".bak").exists() || File(target.path + ".new").exists()
    private companion object { val lock = Any() }
}
