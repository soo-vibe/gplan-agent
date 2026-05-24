package com.example.planna

import android.content.Context
import com.example.planna.auth.GoogleAuthManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * Direct Google Calendar v3 client. Uses the access token obtained via
 * GoogleAuthManager (calendar.events scope) — no backend involvement.
 *
 * Idempotency: deterministic event IDs derived from (source, sourceKey).
 * Re-creating the same event returns 409 from Calendar, treated as success.
 *
 * Tagging: events created here carry extendedProperties.private.gplan_source
 * so we can filter "events the agent created" client-side (replaces the
 * backend's user_events ownership table).
 */
object CalendarRepo {
    private val JSON = "application/json; charset=utf-8".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    data class Event(
        val id: String,
        val title: String,
        val start: String,
        val source: String,
    )

    /**
     * Calendar event ID rules: 5..1024 chars, lowercase a-v + 0-9. SHA-1 hex
     * (0-9, a-f) sits inside that alphabet. 32 chars = 16 bytes of entropy,
     * collision probability negligible at our scale.
     */
    fun makeEventId(source: String, sourceKey: String): String {
        val md = MessageDigest.getInstance("SHA-1")
        val hash = md.digest("$source|$sourceKey".toByteArray(Charsets.UTF_8))
        return hash.take(16).joinToString("") { "%02x".format(it) }
    }

    private fun authedRequestBuilder(ctx: Context): Request.Builder {
        val token = GoogleAuthManager.fetchCalendarAccessToken(ctx)
        return Request.Builder().header("Authorization", "Bearer $token")
    }

    private fun eventsUrl(): HttpUrl.Builder = HttpUrl.Builder()
        .scheme("https")
        .host("www.googleapis.com")
        .addPathSegments("calendar/v3/calendars/primary/events")

    suspend fun createEvent(
        ctx: Context,
        source: String,
        sourceKey: String,
        title: String,
        startIso: String,
        endIso: String,
        timeZone: String = "Asia/Seoul",
        description: String = "",
        location: String = "",
    ): String = withContext(Dispatchers.IO) {
        val id = makeEventId(source, sourceKey)
        val body = JSONObject()
            .put("id", id)
            .put("summary", title)
            .put("start", JSONObject().put("dateTime", startIso).put("timeZone", timeZone))
            .put("end", JSONObject().put("dateTime", endIso).put("timeZone", timeZone))
            .also { if (description.isNotEmpty()) it.put("description", description) }
            .also { if (location.isNotEmpty()) it.put("location", location) }
            .put(
                "extendedProperties",
                JSONObject().put(
                    "private",
                    JSONObject()
                        .put("gplan_source", source)
                        .put("gplan_source_key", sourceKey)
                )
            )
            .toString()
            .toRequestBody(JSON)

        val req = authedRequestBuilder(ctx)
            .url(eventsUrl().build())
            .post(body)
            .build()

        client.newCall(req).execute().use { resp ->
            if (resp.code == 409) return@withContext id  // already exists, idempotent
            if (!resp.isSuccessful) {
                val err = resp.body?.string().orEmpty()
                throw RuntimeException("calendar create failed: ${resp.code} $err")
            }
            JSONObject(resp.body!!.string()).optString("id", id)
        }
    }

    suspend fun deleteEvent(ctx: Context, eventId: String): Boolean = withContext(Dispatchers.IO) {
        val url = eventsUrl().addPathSegment(eventId).build()
        val req = authedRequestBuilder(ctx).url(url).delete().build()
        client.newCall(req).execute().use { resp ->
            // 200/204 = deleted; 404/410 = already gone (still treat as success)
            resp.code == 200 || resp.code == 204 || resp.code == 404 || resp.code == 410
        }
    }

    /**
     * Returns events the agent CREATED today, regardless of when the event
     * itself starts (an SMS sent today about a meeting next week still
     * appears in today's list).
     *
     * Uses updatedMin to bound the server response to events touched today,
     * then filters client-side by:
     *   - extendedProperties.private.gplan_source (only agent-created events)
     *   - created >= start of today (skip events merely *updated* today)
     */
    suspend fun listEventsForToday(
        ctx: Context,
        timeZone: String = "Asia/Seoul",
    ): List<Event> = withContext(Dispatchers.IO) {
        val tz = TimeZone.getTimeZone(timeZone)
        val todayStart = Calendar.getInstance(tz).apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val todayStartMs = todayStart.timeInMillis
        val todayStartIso = isoFormat(todayStart.time, tz)
        val rangeStart = (todayStart.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, -30) }
        val rangeEnd = (todayStart.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, 365) }

        val url = eventsUrl()
            .addQueryParameter("timeMin", isoFormat(rangeStart.time, tz))
            .addQueryParameter("timeMax", isoFormat(rangeEnd.time, tz))
            .addQueryParameter("updatedMin", todayStartIso)
            .addQueryParameter("singleEvents", "true")
            .addQueryParameter("orderBy", "updated")
            .addQueryParameter("maxResults", "100")
            .build()

        val req = authedRequestBuilder(ctx).url(url).get().build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw RuntimeException("calendar list failed: ${resp.code}")
            }
            val items = JSONObject(resp.body!!.string()).optJSONArray("items") ?: JSONArray()
            val out = mutableListOf<Event>()
            for (i in 0 until items.length()) {
                val item = items.getJSONObject(i)
                val source = item
                    .optJSONObject("extendedProperties")
                    ?.optJSONObject("private")
                    ?.optString("gplan_source", "")
                    ?: ""
                if (source.isEmpty()) continue
                val createdMs = parseRfc3339(item.optString("created", ""))
                if (createdMs < todayStartMs) continue
                out.add(
                    Event(
                        id = item.optString("id"),
                        title = item.optString("summary"),
                        start = item.optJSONObject("start")?.optString("dateTime", "") ?: "",
                        source = source,
                    )
                )
            }
            out
        }
    }

    private fun parseRfc3339(s: String): Long {
        if (s.isEmpty()) return 0L
        return try {
            // Calendar API returns "2026-05-05T12:34:56.789Z"
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US)
                .parse(s)?.time ?: 0L
        } catch (_: Exception) {
            try {
                SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).parse(s)?.time ?: 0L
            } catch (_: Exception) { 0L }
        }
    }

    private fun isoFormat(date: Date, tz: TimeZone): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
        sdf.timeZone = tz
        return sdf.format(date)
    }
}
