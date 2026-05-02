package com.example.gplanagent.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

object AuthManager {
    private const val PREFS = "auth_prefs"
    private const val KEY_TOKEN = "api_token"
    private const val KEY_EMAIL = "email"

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
}
