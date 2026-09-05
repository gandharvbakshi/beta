package com.example.beta

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class SwiggyDraftStoreTest {
    @Test
    fun roundTrip_isEncrypted_and_clear_writes_tombstone() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = newStore(context, "roundtrip")
        val text = "milk, bread, eggs"

        assertTrue(store.save(text, nowMillis = NOW))
        assertEquals(text, store.load(nowMillis = NOW))

        val raw = rawFile(context, "roundtrip").readBytes()
        assertFalse(raw.containsSequence(text.toByteArray(Charsets.UTF_8)))

        assertTrue(store.save("", nowMillis = NOW + 1))
        assertNull(store.load(nowMillis = NOW + 1))

        assertTrue(store.clear())
        assertNull(store.load(nowMillis = System.currentTimeMillis()))
    }

    @Test
    fun expiry_future_and_corruption_fail_closed() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val futureStore = newStore(context, "future")
        val expiredStore = newStore(context, "expired")
        val corruptFile = rawFile(context, "corrupt")

        assertTrue(futureStore.save("future text", nowMillis = NOW + TTL + 1))
        assertNull(futureStore.load(nowMillis = NOW))

        assertTrue(expiredStore.save("old text", nowMillis = NOW - TTL - 1))
        assertNull(expiredStore.load(nowMillis = NOW))

        corruptFile.writeBytes(byteArrayOf(0x01, 0x02, 0x03, 0x04))
        assertNull(SwiggyDraftStore(context, draftFileName("corrupt")).load(nowMillis = NOW))
    }

    @Test
    fun oversize_save_is_rejected_without_truncating_existing_payload() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = newStore(context, "oversize")
        val initial = "milk, bread"
        val oversize = buildString {
            repeat(8001) { append('a') }
        }

        assertTrue(store.save(initial, nowMillis = NOW))
        val before = rawFile(context, "oversize").readBytes()

        assertFalse(store.save(oversize, nowMillis = NOW))
        val after = rawFile(context, "oversize").readBytes()

        assertArrayEquals(before, after)
        assertEquals(initial, store.load(nowMillis = NOW))
    }

    @Test
    fun directory_target_returns_false_without_overwriting_gate() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val path = rawFile(context, "directory")
        path.deleteRecursively()
        assertTrue(path.mkdirs() || path.isDirectory)
        File(path, "sentinel.txt").writeText("sentinel")

        val store = newStore(context, "directory")
        assertFalse(store.save("milk", nowMillis = NOW))
        assertTrue(path.isDirectory)
        assertTrue(File(path, "sentinel.txt").exists())
    }

    @Test
    fun oversized_on_disk_file_loads_as_null() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val path = rawFile(context, "oversized-load")
        val bytes = ByteArray(64 * 1024 + 1) { 7 }
        path.writeBytes(bytes)

        assertNull(SwiggyDraftStore(context, draftFileName("oversized-load")).load(nowMillis = NOW))
    }

    @Test
    fun unsafe_basenames_are_rejected() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        try {
            SwiggyDraftStore(context, "../escape")
            throw AssertionError("Expected unsafe basename to be rejected.")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
    }

    @After
    fun cleanUpSyntheticArtifacts() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        listOf("roundtrip", "future", "expired", "corrupt", "oversize", "directory", "oversized-load")
            .map { rawFile(context, it) }
            .forEach { file ->
                if (file.isDirectory) {
                    file.deleteRecursively()
                } else {
                    file.delete()
                }
                File(file.parentFile, "${file.name}.new").delete()
            }
    }

    private fun newStore(context: Context, suffix: String): SwiggyDraftStore {
        return SwiggyDraftStore(context, draftFileName(suffix))
    }

    private fun rawFile(context: Context, suffix: String): File {
        return File(context.noBackupFilesDir, draftFileName(suffix))
    }

    private fun draftFileName(suffix: String): String {
        return "test-swiggy-draft-$suffix.bin"
    }

    private fun ByteArray.containsSequence(sequence: ByteArray): Boolean {
        if (sequence.isEmpty() || sequence.size > size) return false
        for (start in 0..(size - sequence.size)) {
            var matched = true
            for (index in sequence.indices) {
                if (this[start + index] != sequence[index]) {
                    matched = false
                    break
                }
            }
            if (matched) return true
        }
        return false
    }

    private companion object {
        const val NOW = 1_700_000_000_000L
        const val TTL = 24L * 60L * 60L * 1000L
    }
}
