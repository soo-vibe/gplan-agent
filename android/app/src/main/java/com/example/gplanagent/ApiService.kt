package com.example.gplanagent

import android.content.Context
import com.example.gplanagent.auth.AuthManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

    private val client = OkHttpClient.Builder()
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
            // best-effort; clear local token regardless
        }
        AuthManager.clear(ctx)
    }
}
