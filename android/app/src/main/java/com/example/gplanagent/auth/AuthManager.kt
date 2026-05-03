package com.example.gplanagent.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.SecureRandom

object AuthManager {
    private const val PREFS = "auth_prefs"
    private const val KEY_TOKEN = "api_token"
    private const val KEY_EMAIL = "email"
    private const val KEY_PENDING_NONCE = "pending_login_nonce"

    private fun prefs(ctx: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(ctx)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            ctx.applicationContext,
            PREFS,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun getToken(ctx: Context): String? = prefs(ctx).getString(KEY_TOKEN, null)
    fun getEmail(ctx: Context): String? = prefs(ctx).getString(KEY_EMAIL, null)
    fun isLoggedIn(ctx: Context): Boolean = !getToken(ctx).isNullOrEmpty()

    fun saveSession(ctx: Context, token: String, email: String) {
        prefs(ctx).edit().putString(KEY_TOKEN, token).putString(KEY_EMAIL, email).apply()
    }

    fun clear(ctx: Context) {
        prefs(ctx).edit().clear().apply()
    }

    /**
     * Generates a fresh nonce, stores it, and returns it. The caller passes
     * the nonce to /oauth/login; the backend echoes it back via the deep link
     * and we accept the deep link only if the echoed nonce matches.
     *
     * Without this, any installed app can craft a deep link with an attacker-
     * controlled bearer token and silently overwrite this app's session.
     */
    fun beginPendingLogin(ctx: Context): String {
        val bytes = ByteArray(24)
        SecureRandom().nextBytes(bytes)
        val nonce = bytes.joinToString("") { "%02x".format(it) }
        prefs(ctx).edit().putString(KEY_PENDING_NONCE, nonce).apply()
        return nonce
    }

    /** Reads and clears the pending nonce. Returns null if none was stored. */
    fun consumePendingNonce(ctx: Context): String? {
        val p = prefs(ctx)
        val nonce = p.getString(KEY_PENDING_NONCE, null)
        p.edit().remove(KEY_PENDING_NONCE).apply()
        return nonce
    }
}
