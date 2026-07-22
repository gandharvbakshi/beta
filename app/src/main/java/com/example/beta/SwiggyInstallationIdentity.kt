package com.example.beta

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.SecureRandom
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object SwiggyInstallationIdentity {
    private const val PREFS_NAME = "swiggy_installation_identity"
    private const val KEY_ENCRYPTED_INSTALLATION_TOKEN = "encrypted_installation_token_v1"
    private const val LEGACY_PLAINTEXT_TOKEN = "installation_token"
    private const val KEYSTORE_ALIAS = "beta_swiggy_installation_identity_v1"
    private const val TOKEN_SIZE_BYTES = 32

    private val random = SecureRandom()
    private val lock = Any()

    fun installationToken(context: Context): String {
        val prefs = preferences(context)
        readStoredToken(prefs)?.let { return it }

        synchronized(lock) {
            readStoredToken(prefs)?.let { return it }

            val token = generateToken()
            prefs.edit()
                .remove(LEGACY_PLAINTEXT_TOKEN)
                .putString(KEY_ENCRYPTED_INSTALLATION_TOKEN, encrypt(token))
                .commit()
            return token
        }
    }

    internal fun generateToken(): String {
        val bytes = ByteArray(TOKEN_SIZE_BYTES)
        random.nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun preferences(context: Context): SharedPreferences {
        return context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private fun readStoredToken(prefs: SharedPreferences): String? {
        val encrypted = prefs.getString(KEY_ENCRYPTED_INSTALLATION_TOKEN, null)
        if (!encrypted.isNullOrBlank()) {
            runCatching { decrypt(encrypted) }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?.let { return it }
            prefs.edit().remove(KEY_ENCRYPTED_INSTALLATION_TOKEN).commit()
        }
        // This feature has not shipped with a plaintext token. Remove any
        // development-era value instead of promoting it into a bearer secret.
        if (prefs.contains(LEGACY_PLAINTEXT_TOKEN)) {
            prefs.edit().remove(LEGACY_PLAINTEXT_TOKEN).commit()
        }
        return null
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, installationKey())
        val iv = Base64.encodeToString(cipher.iv, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val ciphertext = Base64.encodeToString(
            cipher.doFinal(value.toByteArray(Charsets.UTF_8)),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )
        return "$iv.$ciphertext"
    }

    private fun decrypt(value: String): String {
        val parts = value.split('.', limit = 2)
        require(parts.size == 2)
        val iv = Base64.decode(parts[0], Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val ciphertext = Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, installationKey(), GCMParameterSpec(128, iv))
        return cipher.doFinal(ciphertext).toString(Charsets.UTF_8)
    }

    private fun installationKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEYSTORE_ALIAS, null) as? SecretKey)?.let { return it }
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
}
