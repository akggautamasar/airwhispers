package com.airwhispers.data.prefs

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.airwhispers.core.AppLog
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Token storage backed by an AES/GCM key that lives in the Android Keystore.
 *
 * Auth tokens are never written to plain SharedPreferences and never leave the
 * device in cleartext. Keys are non-exportable and hardware-backed where the
 * device supports it.
 */
class SecretStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun putAccessToken(token: String?) = put(KEY_ACCESS, token)
    fun accessToken(): String? = get(KEY_ACCESS)

    fun putRefreshToken(token: String?) = put(KEY_REFRESH, token)
    fun refreshToken(): String? = get(KEY_REFRESH)

    fun putUserId(id: String?) = put(KEY_USER_ID, id)
    fun userId(): String? = get(KEY_USER_ID)

    fun clearSession() {
        prefs.edit()
            .remove(KEY_ACCESS)
            .remove(KEY_REFRESH)
            .remove(KEY_USER_ID)
            .apply()
    }

    fun clearAll() = prefs.edit().clear().apply()

    private fun put(key: String, value: String?) {
        if (value == null) {
            prefs.edit().remove(key).apply()
            return
        }
        runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            val payload = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
            prefs.edit()
                .putString(key, Base64.encodeToString(payload, Base64.NO_WRAP))
                .apply()
        }.onFailure {
            // Failing loudly is better than silently degrading to cleartext.
            AppLog.e("SecretStore", "store_failed", it, "key" to key)
        }
    }

    private fun get(key: String): String? {
        val encoded = prefs.getString(key, null) ?: return null
        return runCatching {
            val payload = Base64.decode(encoded, Base64.NO_WRAP)
            if (payload.size <= IV_BYTES) return@runCatching null
            val iv = payload.copyOfRange(0, IV_BYTES)
            val body = payload.copyOfRange(IV_BYTES, payload.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(body), Charsets.UTF_8)
        }.getOrElse {
            // A key that survives re-install mismatch: drop the value, force re-auth.
            AppLog.w("SecretStore", "read_failed", it, "key" to key)
            prefs.edit().remove(key).apply()
            null
        }
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                // No user-authentication requirement: messages must keep working
                // while the screen is locked during a call.
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "airwhispers.secrets.v1"
        const val PREFS_NAME = "airwhispers.secrets"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
        const val KEY_ACCESS = "access_token"
        const val KEY_REFRESH = "refresh_token"
        const val KEY_USER_ID = "user_id"
    }
}
