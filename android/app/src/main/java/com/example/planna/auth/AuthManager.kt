package com.example.planna.auth

import android.content.Context

/**
 * Thin wrapper over GoogleAuthManager. Sign-in state lives in the
 * GoogleSignIn cache (Play Services) — no separate token storage.
 */
object AuthManager {
    fun isLoggedIn(ctx: Context): Boolean =
        GoogleAuthManager.lastSignedInAccount(ctx) != null

    fun getEmail(ctx: Context): String? =
        GoogleAuthManager.lastSignedInAccount(ctx)?.email

    /** Display name from the Google account; null if not signed in or not provided. */
    fun getDisplayName(ctx: Context): String? =
        GoogleAuthManager.lastSignedInAccount(ctx)?.displayName
}
