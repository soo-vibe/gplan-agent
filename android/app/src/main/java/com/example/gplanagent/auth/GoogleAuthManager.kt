package com.example.gplanagent.auth

import android.app.Activity
import android.content.Context
import com.example.gplanagent.BuildConfig
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Wraps Google Sign-In so the app gets:
 *   - an ID token (audience = backend Web client ID) for backend auth, and
 *   - an OAuth access token with calendar.events scope for direct Calendar
 *     API calls from the device.
 *
 * Single source of truth for sign-in state — AuthManager defers to the
 * cached GoogleSignInAccount instead of holding its own session.
 */
object GoogleAuthManager {
    const val SCOPE_CALENDAR_EVENTS = "https://www.googleapis.com/auth/calendar.events"

    private fun signInOptions(): GoogleSignInOptions =
        GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(BuildConfig.GOOGLE_WEB_CLIENT_ID)
            .requestEmail()
            .requestProfile()
            .requestScopes(Scope(SCOPE_CALENDAR_EVENTS))
            .build()

    fun client(activity: Activity): GoogleSignInClient =
        GoogleSignIn.getClient(activity, signInOptions())

    fun client(ctx: Context): GoogleSignInClient =
        GoogleSignIn.getClient(ctx, signInOptions())

    fun lastSignedInAccount(ctx: Context): GoogleSignInAccount? =
        GoogleSignIn.getLastSignedInAccount(ctx)

    /**
     * Returns a fresh Google ID token (audience = Web client ID). silentSignIn
     * refreshes ID/access tokens transparently when the cached one is expired,
     * and serves the cached one without a network call when still valid.
     *
     * Throws if the user is not signed in (no last account) or if the refresh
     * fails (revoked grant, network error). The caller treats this as a
     * session-expired situation and bounces back to LoginActivity.
     */
    suspend fun getIdToken(ctx: Context): String =
        suspendCancellableCoroutine { cont ->
            client(ctx).silentSignIn().addOnCompleteListener { task ->
                if (!cont.isActive) return@addOnCompleteListener
                try {
                    val account = task.getResult(ApiException::class.java)
                    val idToken = account.idToken
                    if (idToken.isNullOrEmpty()) {
                        cont.resumeWithException(IllegalStateException("no idToken on refreshed account"))
                    } else {
                        cont.resume(idToken)
                    }
                } catch (e: Exception) {
                    cont.resumeWithException(e)
                }
            }
        }

    /**
     * Synchronously fetches a fresh OAuth access token for Calendar API.
     * Must be called off the main thread. The Play Services library caches
     * the token and refreshes it just-in-time when expired.
     */
    fun fetchCalendarAccessToken(ctx: Context): String {
        val account = lastSignedInAccount(ctx)?.account
            ?: throw IllegalStateException("not signed in")
        return GoogleAuthUtil.getToken(ctx, account, "oauth2:$SCOPE_CALENDAR_EVENTS")
    }

    suspend fun signOut(ctx: Context) {
        suspendCancellableCoroutine<Unit> { cont ->
            client(ctx).signOut().addOnCompleteListener {
                if (cont.isActive) cont.resume(Unit)
            }
        }
    }
}
