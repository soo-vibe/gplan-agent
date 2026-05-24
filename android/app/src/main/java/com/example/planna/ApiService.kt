package com.example.planna

import android.content.Context
import com.example.planna.auth.GoogleAuthManager
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
     * a new APK ships with refreshed pins.
     */
    private val pinner = CertificatePinner.Builder()
        .add(
            "planna-173551063984.asia-northeast3.run.app",
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

    private suspend fun authedBuilder(ctx: Context, url: String): Request.Builder {
        val idToken = try {
            GoogleAuthManager.getIdToken(ctx)
        } catch (e: Exception) {
            throw NotLoggedInException()
        }
        return Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $idToken")
    }

    private fun handleAuth(response: Response) {
        if (response.code == 401) {
            throw SessionExpiredException()
        }
    }

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

    /**
     * LLM-only parsing. Calendar write happens client-side via CalendarRepo.
     *
     * Context fields (source/sender/senderOrg/userName) are sent so the backend
     * prompt can disambiguate "I" vs "you" in messages — e.g. distinguish a
     * group invitation ("다들 참석부탁") from a sender's personal excuse
     * ("저는 결혼식이 있어서 늦게 합류"). Backend may ignore them harmlessly
     * if its prompt hasn't been updated yet.
     */
    suspend fun parse(
        ctx: Context,
        message: String,
        source: String = "",
        sender: String = "",
        senderOrg: String = "",
        userName: String = "",
    ): ParsedSchedule = withContext(Dispatchers.IO) {
        val payload = JSONObject().put("message", message)
        if (source.isNotBlank()) payload.put("source", source)
        if (sender.isNotBlank()) payload.put("sender", sender)
        if (senderOrg.isNotBlank()) payload.put("sender_org", senderOrg)
        if (userName.isNotBlank()) payload.put("user_name", userName)
        val body = payload.toString().toRequestBody(JSON)
        val request = authedBuilder(ctx, "$BASE_URL/parse").post(body).build()
        client.newCall(request).execute().use { response ->
            handleAuth(response)
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
}
