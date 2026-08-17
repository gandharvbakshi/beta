package com.example.beta

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.security.MessageDigest
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SwiggyLiveTestIdentityTest {

    @Test
    fun emitsOpaqueInstallationIdForLocalLiveTestHarness() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val token = SwiggyInstallationIdentity.installationToken(context)
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("beta-installation-v1\u0000".toByteArray() + decodeBase64Url(token))
            .joinToString("") { byte -> "%02x".format(byte) }
        println("BETA_SWIGGY_INSTALLATION_ID=$digest")
    }

    private fun decodeBase64Url(value: String): ByteArray {
        return android.util.Base64.decode(
            value,
            android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING,
        )
    }
}
