package com.example.gplanagent

import android.content.Context

/**
 * End-to-end pipeline: backend /parse → client-side compose → Calendar insert.
 * Replaces the backend's /parse-and-save flow.
 *
 * Idempotency: CalendarRepo derives a deterministic event ID from
 * (source, sourceKey), so a duplicate insert returns 409 from Calendar and
 * we treat it as success — no Firestore dedup table needed.
 */
object ScheduleProcessor {
    data class Outcome(
        val saved: Boolean,
        val title: String? = null,
        val noSchedule: Boolean = false,
    )

    suspend fun process(
        ctx: Context,
        text: String,
        source: String,
        sender: String = "",
        senderOrg: String = "",
        sourceKey: String = text,
    ): Outcome {
        val parsed = ApiService.parse(ctx, text)
        if (!parsed.hasSchedule || parsed.date.isBlank()) {
            return Outcome(saved = false, noSchedule = true)
        }

        val baseTitle = parsed.title.ifBlank { "일정" }
        val title = buildTitle(baseTitle, sender, senderOrg)
        val description = buildDescription(parsed)
        val startTime = parsed.startTime.ifBlank { "09:00" }
        val endTime = parsed.endTime.ifBlank { bumpHour(startTime) }

        CalendarRepo.createEvent(
            ctx = ctx,
            source = source,
            sourceKey = sourceKey,
            title = title,
            startIso = buildIso(parsed.date, startTime),
            endIso = buildIso(parsed.date, endTime),
            description = description,
            location = parsed.location,
        )
        return Outcome(saved = true, title = title)
    }

    private fun buildTitle(baseTitle: String, sender: String, senderOrg: String): String {
        if (sender.isBlank()) return baseTitle
        if (senderOrg.isNotBlank()) return "$baseTitle:$sender($senderOrg)"
        return "$baseTitle:$sender"
    }

    private fun buildDescription(parsed: ApiService.ParsedSchedule): String {
        val meta = listOfNotNull(
            parsed.location.takeIf { it.isNotBlank() },
            parsed.meetingUrl.takeIf { it.isNotBlank() },
        ).joinToString(", ")
        val body = parsed.description
        return when {
            meta.isNotEmpty() && body.isNotEmpty() -> "$meta\n\n$body"
            meta.isNotEmpty() -> meta
            else -> body
        }
    }

    private fun buildIso(date: String, time: String): String = "${date}T${time}:00+09:00"

    private fun bumpHour(time: String): String {
        val parts = time.split(":")
        if (parts.size != 2) return time
        val h = parts[0].toIntOrNull() ?: return time
        return "%02d:%s".format(h + 1, parts[1])
    }
}
