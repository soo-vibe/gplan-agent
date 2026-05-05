package com.example.gplanagent

import android.content.Context
import com.example.gplanagent.auth.AuthManager
import com.example.gplanagent.auth.GoogleAuthManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.CertificatePinner
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class NotLoggedInException : RuntimeException("not logged in")
class SessionExpiredException : RuntimeException("session expired")

object ApiService {

    private val BASE_URL = BuildConfig.BASE_URL

    /**
     * Certificate pins for the Cloud Run backend. The chain we ship against
     * today is:
     *
     *   Leaf:         CN=*.a.run.app                    (GTS WR2 issued)  — rotates ~quarterly, NOT pinned
     *   Intermediate: CN=WR2  (Google Trust Services)   (GTS Root R1)     — pinned
     *   Root:         CN=GTS Root R1                    (cross-signed)    — pinned
     *
     * OkHttp requires AT LEAST ONE of the pinned hashes to match SOME cert in
     * the chain, so as long as either WR2 or GTS Root R1 stays in place, the
     * app keeps working through leaf rotations.
     *
     * If Google rotates BOTH the intermediate and the root simultaneously
     * (rare — root rotations happen every several years), this app dies until
     * a new APK ships with refreshed pins. Refresh procedure:
     *
     *   openssl s_client -connect <host>:443 -servername <host> -showcerts \
     *     < /dev/null 2>/dev/null > chain.pem
     *   for c in cert*.pem; do
     *     openssl x509 -in $c -pubkey -noout \
     *       | openssl pkey -pubin -outform der \
     *       | openssl dgst -sha256 -binary | base64
     *   done
     */
    private val pinner = CertificatePinner.Builder()
        .add(
            "gplan-agent-173551063984.asia-northeast3.run.app",
            "sha256/YPtHaftLw6/0vnc2BnNKGF54xiCA28WFcccjkA4ypCM=", // GTS WR2 (intermediate)
            "sha256/hxqRlPTu1bMS/0DITB1SSu0vd4u/8l8TjPgfaAp63Gc=", // GTS Root R1
        )
        .build()

    private val client = OkHttpClient.Builder()
        .certificatePinner(pinner)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val JSON = "application/json; charset=utf-8".toMediaType()

    private fun authedBuilder(ctx: Context, url: String): Request.Builder {
        val token = AuthManager.getToken(ctx) ?: throw NotLoggedInException()
        return Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
    }

    private fun handleAuth(ctx: Context, response: Response) {
        if (response.code == 401) {
            AuthManager.clear(ctx)
            throw SessionExpiredException()
        }
    }

    data class Session(val token: String, val email: String)

    data class ParsedSchedule(
        val hasSchedule: Boolean,
        val title: String,
        val date: String,
        val startTime: String,
        val endTime: String,
        val location: String,
        val meetingUrl: String,
        val description: String,
    )

    /** Exchange Google ID token for backend api_token. Public (no Bearer needed). */
    suspend fun googleSignIn(ctx: Context, idToken: String): Session = withContext(Dispatchers.IO) {
        val body = JSONObject().put("id_token", idToken).toString().toRequestBody(JSON)
        val request = Request.Builder()
            .url("$BASE_URL/auth/google-signin")
            .post(body)
            .build()
        client.newCall(request).execute().use { response ->
            val raw = response.body!!.string()
            if (!response.isSuccessful) throw RuntimeException("auth failed: ${response.code} $raw")
            val json = JSONObject(raw)
            Session(token = json.getString("token"), email = json.optString("email"))
        }
    }

    /** LLM-only parsing. Calendar write happens client-side via CalendarRepo. */
    suspend fun parse(ctx: Context, message: String): ParsedSchedule = withContext(Dispatchers.IO) {
        val body = JSONObject().put("message", message).toString().toRequestBody(JSON)
        val request = authedBuilder(ctx, "$BASE_URL/parse").post(body).build()
        client.newCall(request).execute().use { response ->
            handleAuth(ctx, response)
            if (!response.isSuccessful) {
                throw RuntimeException("parse failed: ${response.code}")
            }
            val json = JSONObject(response.body!!.string())
            ParsedSchedule(
                hasSchedule = json.optBoolean("has_schedule", false),
                title = json.optString("title", ""),
                date = json.optString("date", ""),
                startTime = json.optString("start_time", ""),
                endTime = json.optString("end_time", ""),
                location = json.optString("location", ""),
                meetingUrl = json.optString("meeting_url", ""),
                description = json.optString("description", ""),
            )
        }
    }

    suspend fun logout(ctx: Context) = withContext(Dispatchers.IO) {
        try {
            val request = authedBuilder(ctx, "$BASE_URL/logout")
                .post("".toRequestBody(JSON))
                .build()
            client.newCall(request).execute().close()
        } catch (_: Exception) {
            // best-effort; clear local state regardless
        }
        try { GoogleAuthManager.signOut(ctx) } catch (_: Exception) {}
        AuthManager.clear(ctx)
    }
}
