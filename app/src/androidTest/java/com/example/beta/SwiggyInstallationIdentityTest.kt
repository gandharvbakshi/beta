package com.example.beta

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SwiggyInstallationIdentityTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before
    fun clearTestIdentity() {
        context.getSharedPreferences("swiggy_installation_identity", android.content.Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun installationBearerIsStableAndEncryptedAtRest() {
        val first = SwiggyInstallationIdentity.installationToken(context)
        val second = SwiggyInstallationIdentity.installationToken(context)
        val prefs = context.getSharedPreferences(
            "swiggy_installation_identity",
            android.content.Context.MODE_PRIVATE,
        )
        val encrypted = prefs.getString("encrypted_installation_token_v1", null).orEmpty()

        assertEquals(first, second)
        assertTrue(first.length >= 43)
        assertTrue(encrypted.contains('.'))
        assertFalse(encrypted.contains(first))
        assertNull(prefs.getString("installation_token", null))
    }
}
