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
class SwiggyCartReviewStoreTest {
    @Test
    fun roundTrip_isEncrypted_and_clear_removes_token() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = newStore(context, "roundtrip")
        val token = "review-token-123"

        assertTrue(store.save(token))
        assertEquals(token, store.load())

        val raw = rawFile(context, "roundtrip").readBytes()
        assertFalse(raw.containsSequence(token.toByteArray(Charsets.UTF_8)))

        assertTrue(store.clear())
        assertNull(store.load())
    }

    @Test
    fun corrupt_or_invalid_token_fail_closed_without_overwriting_existing_value() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = newStore(context, "guard")
        val token = "review-token-guard"
        val before = rawFile(context, "guard")

        assertTrue(store.save(token))
        val savedBytes = before.readBytes()

        assertFalse(store.save(""))
        assertFalse(store.save(buildString { repeat(4097) { append('x') } }))
        assertArrayEquals(savedBytes, before.readBytes())
        assertEquals(token, store.load())

        before.writeBytes(byteArrayOf(0x01, 0x02, 0x03, 0x04))
        assertNull(newStore(context, "guard").load())
    }

    @After
    fun cleanUpSyntheticArtifacts() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        listOf("roundtrip", "guard")
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

    private fun newStore(context: Context, suffix: String): SwiggyCartReviewStore {
        return SwiggyCartReviewStore(context, reviewFileName(suffix))
    }

    private fun rawFile(context: Context, suffix: String): File {
        return File(context.noBackupFilesDir, reviewFileName(suffix))
    }

    private fun reviewFileName(suffix: String): String {
        return "test-swiggy-cart-review-$suffix.bin"
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
}
