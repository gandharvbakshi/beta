package com.example.beta

import android.content.Context
import android.util.AtomicFile
import java.io.File

/** No cart/token data: a durable warning that a confirmed write needs review. */
internal class SwiggyPendingCartStore(context: Context) {
    private val file = File(context.applicationContext.noBackupFilesDir, "swiggy-cart-pending-v1")
    private val backup = File(file.path + ".bak")
    private val atomic = AtomicFile(file)

    fun isPending(): Boolean = synchronized(lock) { file.exists() || backup.exists() }

    fun markPending(): Boolean = synchronized(lock) {
        val output = runCatching { atomic.startWrite() }.getOrNull() ?: return false
        try {
            output.write(byteArrayOf(1))
            atomic.finishWrite(output)
            file.length() == 1L && atomic.openRead().use { it.read() == 1 }
        } catch (_: Exception) {
            runCatching { atomic.failWrite(output) }
            false
        }
    }

    fun clear(): Boolean = synchronized(lock) {
        runCatching { atomic.delete() }.isSuccess && !file.exists() && !backup.exists()
    }

    private companion object { val lock = Any() }
}
