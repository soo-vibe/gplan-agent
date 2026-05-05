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

    data class SourceStats(val sms: Int, val kakao: Int, val gmail: Int, val total: Int)

    data class TodayEvent(val id: String, val title: String, val start: String, val source: String)

    data class StatsResult(
        val todayAdded: SourceStats,
        val todayList: List<TodayEvent>
    )

    data class SaveResult(val success: Boolean, val message: String, val eventLink: String = "")

    data class Profile(val email: String, val name: String, val picture: String)

    data class Session(val token: String, val email: String)

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

    suspend fun getStats(ctx: Context): StatsResult = withContext(Dispatchers.IO) {
        val request = authedBuilder(ctx, "$BASE_URL/stats").get().build()
        client.newCall(request).execute().use { response ->
            handleAuth(ctx, response)
            val json = JSONObject(response.body!!.string())

            fun parseSource(obj: JSONObject) = SourceStats(
                sms = obj.optInt("sms", 0),
                kakao = obj.optInt("kakao", 0),
                gmail = obj.optInt("gmail", 0),
                total = obj.optInt("total", 0)
            )

            val listArray = json.optJSONArray("today_list")
            val todayList = mutableListOf<TodayEvent>()
            if (listArray != null) {
                for (i in 0 until listArray.length()) {
                    val item = listArray.getJSONObject(i)
                    todayList.add(TodayEvent(
                        id = item.optString("id"),
                        title = item.optString("title"),
                        start = item.optString("start"),
                        source = item.optString("source")
                    ))
                }
            }

            StatsResult(
                todayAdded = parseSource(json.getJSONObject("today_added")),
                todayList = todayList
            )
        }
    }

    suspend fun parseAndSave(
        ctx: Context,
        message: String,
        source: String = "",
        sender: String = "",
        senderOrg: String = "",
    ): SaveResult = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("message", message)
            .put("source", source)
            .put("sender", sender)
            .put("sender_org", senderOrg)
            .toString()
            .toRequestBody(JSON)
        val request = authedBuilder(ctx, "$BASE_URL/parse-and-save")
            .post(body)
            .build()
        client.newCall(request).execute().use { response ->
            handleAuth(ctx, response)
            val json = JSONObject(response.body!!.string())
            SaveResult(
                success = json.optBoolean("success", false),
                message = json.optString("message"),
                eventLink = json.optJSONObject("event")?.optString("link") ?: ""
            )
        }
    }

    suspend fun getMe(ctx: Context): Profile = withContext(Dispatchers.IO) {
        val request = authedBuilder(ctx, "$BASE_URL/me").get().build()
        client.newCall(request).execute().use { response ->
            handleAuth(ctx, response)
            val json = JSONObject(response.body!!.string())
            Profile(
                email = json.optString("email"),
                name = json.optString("name"),
                picture = json.optString("picture")
            )
        }
    }

    suspend fun deleteEvent(ctx: Context, eventId: String): Boolean = withContext(Dispatchers.IO) {
        val request = authedBuilder(ctx, "$BASE_URL/event/$eventId")
            .delete()
            .build()
        client.newCall(request).execute().use { response ->
            handleAuth(ctx, response)
            val json = JSONObject(response.body!!.string())
            json.optBoolean("success", false)
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
