package com.example.beta

import android.content.Context
import android.util.AtomicFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SwiggyCheckoutStoreTest {
    @Test
    fun save_and_load_round_trip_uuid_across_instances_without_ttl_or_shared_preferences() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = rawFile(context, "roundtrip")
        val store = newStore(context, "roundtrip")
        val attemptId = UUID.randomUUID().toString()

        assertTrue(store.save(attemptId))
        assertEquals(attemptId, newStore(context, "roundtrip").load())
        assertNull(newStore(context, "roundtrip").loadHandoff())
        assertFalse(newStore(context, "roundtrip").hasHandoffFor(attemptId))
        assertEquals(attemptId, file.readText(Charsets.US_ASCII))
        assertEquals(36L, file.length())
        assertTrue(file.parentFile?.absoluteFile == context.noBackupFilesDir.absoluteFile)
        assertFalse(sharedPrefsFile(context, "roundtrip").exists())
    }

    @Test
    fun rejects_non_uuid_values_without_creating_data_files() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = rawFile(context, "invalid")
        val store = newStore(context, "invalid")

        assertFalse(store.save("not-a-uuid"))
        assertFalse(store.save("1234"))
        assertNull(store.load())
        assertFalse(file.exists())
        assertFalse(backupFile(file).exists())
        assertFalse(sharedPrefsFile(context, "invalid").exists())
    }

    @Test
    fun corrupted_primary_file_remains_pending_and_loads_null() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = rawFile(context, "corrupt")
        file.writeBytes(byteArrayOf(0x01, 0x02, 0x03, 0x04))

        val store = newStore(context, "corrupt")

        assertTrue(store.isPending())
        assertNull(store.load())
        assertTrue(file.exists())
        assertFalse(sharedPrefsFile(context, "corrupt").exists())
    }

    @Test
    fun backup_file_is_recovered_by_load() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = rawFile(context, "backup")
        val backup = backupFile(file)
        val attemptId = UUID.randomUUID().toString()
        backup.writeText(attemptId, Charsets.US_ASCII)
        file.delete()

        val store = newStore(context, "backup")

        assertTrue(store.isPending())
        assertEquals(attemptId, store.load())
        assertEquals(attemptId, file.readText(Charsets.US_ASCII))
        assertFalse(sharedPrefsFile(context, "backup").exists())
    }

    @Test
    fun handoff_marker_round_trip_requires_the_same_uuid_and_survives_restart() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = rawFile(context, "handoff")
        val store = newStore(context, "handoff")
        val attemptId = UUID.randomUUID().toString()

        assertTrue(store.save(attemptId))
        assertTrue(store.markHandoff(attemptId))
        assertEquals(attemptId, store.load())
        assertEquals(attemptId, store.loadHandoff())
        assertTrue(store.hasHandoffFor(attemptId))
        assertFalse(store.markHandoff(UUID.randomUUID().toString()))
        assertTrue(newStore(context, "handoff").hasHandoffFor(attemptId))
        assertFalse(sharedPrefsFile(context, "handoff").exists())
        assertFalse(backupFile(file).exists())
    }

    @Test
    fun corrupted_primary_or_handoff_file_remains_pending_and_loads_null() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val primary = rawFile(context, "corrupt")
        val opened = handoffFile(context, "corrupt")
        primary.writeBytes(byteArrayOf(0x01, 0x02, 0x03, 0x04))
        opened.writeBytes(byteArrayOf(0x05, 0x06, 0x07, 0x08))

        val store = newStore(context, "corrupt")

        assertTrue(store.isPending())
        assertNull(store.load())
        assertNull(store.loadHandoff())
        assertTrue(store.hasHandoffRecord())
        assertTrue(primary.exists())
        assertTrue(opened.exists())
        assertFalse(sharedPrefsFile(context, "corrupt").exists())
    }

    @Test
    fun corrupted_handoff_cannot_be_replaced_to_allow_another_payment() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = newStore(context, "corrupt")
        val attemptId = UUID.randomUUID().toString()
        assertTrue(store.save(attemptId))
        handoffFile(context, "corrupt").writeText("invalid")
        assertTrue(store.hasHandoffRecord())
        assertFalse(store.markHandoff(attemptId))
        assertTrue(store.clearHandoff())
        assertFalse(store.hasHandoffRecord())
    }

    @Test
    fun clear_removes_primary_backup_and_handoff_artifacts() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = rawFile(context, "clear")
        val backup = backupFile(file)
        val opened = handoffFile(context, "clear")
        val store = newStore(context, "clear")
        val attemptId = UUID.randomUUID().toString()

        assertTrue(store.save(attemptId))
        assertTrue(store.markHandoff(attemptId))
        backup.writeText(attemptId, Charsets.US_ASCII)
        opened.writeText(attemptId, Charsets.US_ASCII)
        assertTrue(file.exists())
        assertTrue(backup.exists())
        assertTrue(opened.exists())

        assertTrue(store.clear())
        assertFalse(file.exists())
        assertFalse(backup.exists())
        assertFalse(opened.exists())
        assertNull(store.load())
        assertNull(store.loadHandoff())
        assertFalse(sharedPrefsFile(context, "clear").exists())
    }

    @After
    fun clean_up_synthetic_artifacts() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        listOf("roundtrip", "invalid", "corrupt", "backup", "handoff", "clear").forEach { suffix ->
            val file = rawFile(context, suffix)
            assertTrue(file.parentFile?.absoluteFile == context.noBackupFilesDir.absoluteFile)
            AtomicFile(file).delete()
            file.delete()
            backupFile(file).delete()
            handoffFile(context, suffix).delete()
            backupFile(handoffFile(context, suffix)).delete()
            File(file.parentFile, "${file.name}.new").delete()
            sharedPrefsFile(context, suffix).delete()
        }
    }

    private fun newStore(context: Context, suffix: String): SwiggyCheckoutStore {
        return SwiggyCheckoutStore(context, fileName(suffix))
    }

    private fun rawFile(context: Context, suffix: String): File {
        return File(context.noBackupFilesDir, fileName(suffix))
    }

    private fun backupFile(file: File): File {
        return File(file.path + ".bak")
    }

    private fun handoffFile(context: Context, suffix: String): File {
        return File(context.noBackupFilesDir, "${fileName(suffix)}-opened")
    }

    private fun sharedPrefsFile(context: Context, suffix: String): File {
        return File(File(context.applicationInfo.dataDir, "shared_prefs"), "${fileName(suffix)}.xml")
    }

    private fun fileName(suffix: String): String {
        return "test-swiggy-checkout-$suffix-v1"
    }
}
