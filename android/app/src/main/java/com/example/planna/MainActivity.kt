package com.example.planna

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.planna.auth.AuthManager
import com.example.planna.auth.GoogleAuthManager
import com.example.planna.auth.LoginActivity
import com.example.planna.onboarding.OnboardingActivity
import com.example.planna.onboarding.PermissionStatus
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var tvTodaySms: TextView
    private lateinit var tvTodayKakao: TextView
    private lateinit var tvTodayGmail: TextView
    private lateinit var llSmsContainer: LinearLayout
    private lateinit var tvPermissionWarning: TextView

    // Throttle list refreshes to once per second when many events arrive in
    // a burst (e.g., processing several queued SMS in quick succession).
    private var lastStatsLoadAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!AuthManager.isLoggedIn(this)) {
            goToLogin()
            return
        }

        setContentView(R.layout.activity_main)

        tvTodaySms = findViewById(R.id.tvTodaySms)
        tvTodayKakao = findViewById(R.id.tvTodayKakao)
        tvTodayGmail = findViewById(R.id.tvTodayGmail)
        llSmsContainer = findViewById(R.id.llSmsContainer)
        tvPermissionWarning = findViewById(R.id.tvPermissionWarning)

        tvPermissionWarning.setOnClickListener {
            startActivity(Intent(this, OnboardingActivity::class.java))
        }

        // Header action buttons replace the ActionBar overflow menu
        // (we use a NoActionBar theme so the system menu never shows).
        findViewById<TextView>(R.id.btnPermissions).setOnClickListener {
            startActivity(Intent(this, OnboardingActivity::class.java))
        }
        findViewById<TextView>(R.id.btnLogout).setOnClickListener {
            confirmLogout()
        }

        loadStats()

        // RCS 폴링 — 백그라운드 15분 주기 + 한 번 캐치업
        RcsHelper.primeLastSeenIdIfUnset(this)
        RcsSyncWorker.schedulePeriodic(applicationContext)

        lifecycleScope.launch {
            ScheduleEventBus.events.collect { message ->
                runOnUiThread {
                    Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                    if (message.startsWith(ScheduleEventBus.SESSION_EXPIRED_PREFIX)) {
                        goToLogin()
                    } else {
                        val now = System.currentTimeMillis()
                        if (now - lastStatsLoadAt > 1000) {
                            lastStatsLoadAt = now
                            loadStats()
                        }
                    }
                }
            }
        }

    }

    override fun onResume() {
        super.onResume()
        refreshPermissionBanner()
        lifecycleScope.launch {
            RcsSync.runOnce(applicationContext)
        }
    }

    private fun refreshPermissionBanner() {
        if (!::tvPermissionWarning.isInitialized) return
        val missing = mutableListOf<String>()
        if (!PermissionStatus.smsGranted(this)) missing += "SMS"
        if (!PermissionStatus.notificationListenerGranted(this)) missing += "카톡 알림"
        if (!PermissionStatus.batteryUnrestricted(this)) missing += "배터리"

        if (missing.isEmpty()) {
            tvPermissionWarning.visibility = View.GONE
        } else {
            tvPermissionWarning.text = "! ${missing.joinToString(" / ")} 권한 누락 — 탭해서 설정"
            tvPermissionWarning.visibility = View.VISIBLE
        }
    }

    private fun confirmLogout() {
        AlertDialog.Builder(this)
            .setMessage("로그아웃 하시겠습니까?")
            .setPositiveButton("로그아웃") { _, _ ->
                lifecycleScope.launch {
                    try { GoogleAuthManager.signOut(this@MainActivity) } catch (_: Exception) {}
                    runOnUiThread { goToLogin() }
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun loadStats() {
        lifecycleScope.launch {
            try {
                val events = CalendarRepo.listEventsForToday(this@MainActivity)
                val counts = events.groupingBy { it.source }.eachCount()
                runOnUiThread {
                    // SMS tab counts both sms and rcs sources; Gmail tab counts gmail and naver.
                    tvTodaySms.text = ((counts["sms"] ?: 0) + (counts["rcs"] ?: 0)).toString()
                    tvTodayKakao.text = (counts["kakao"] ?: 0).toString()
                    tvTodayGmail.text = ((counts["gmail"] ?: 0) + (counts["naver"] ?: 0)).toString()

                    llSmsContainer.removeAllViews()
                    events.forEach { event ->
                        llSmsContainer.addView(createEventItemView(event))
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "loadStats failed: ${e.javaClass.simpleName} ${e.message}")
            }
        }
    }

    /**
     * Builds an event row that mirrors the neon palette used on the
     * stat cards above: each source maps to a card_event_* drawable
     * (dark surface + neon left stripe), with the meta line in the
     * channel's accent color and the title in primary white. Source
     * label and time are kept in monospace to echo the brand voice.
     */
    private fun createEventItemView(event: CalendarRepo.Event): View {
        val accentColor = ContextCompat.getColor(this, accentColorRes(event.source))
        val cardBg = cardBackgroundRes(event.source)
        val sourceLabel = sourceLabel(event.source)

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = ContextCompat.getDrawable(this@MainActivity, cardBg)
            // Inner padding; left padding is slightly larger to clear the
            // 2dp accent stripe drawn by the card_event_* layer-list.
            setPadding(dp(14), dp(12), dp(10), dp(12))
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            lp.bottomMargin = dp(8)
            layoutParams = lp
        }

        val textColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val timeStr = try {
            val inputFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.getDefault())
            val outputFormat = SimpleDateFormat("MM/dd HH:mm", Locale.KOREAN)
            outputFormat.format(inputFormat.parse(event.start)!!)
        } catch (e: Exception) { event.start }

        val metaView = TextView(this).apply {
            text = "› $sourceLabel · $timeStr"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(accentColor)
            typeface = Typeface.MONOSPACE
            letterSpacing = 0.05f
        }

        val titleView = TextView(this).apply {
            text = event.title
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTypeface(null, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).also { it.topMargin = dp(3) }
            layoutParams = lp
        }

        textColumn.addView(metaView)
        textColumn.addView(titleView)

        // Ghost-style delete: monospace × in the channel accent, no
        // background — sits visually quieter than a filled button but
        // stays discoverable next to the title.
        val deleteBtn = TextView(this).apply {
            text = "×"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_tertiary))
            setPadding(dp(14), dp(4), dp(8), dp(4))
            background = resources.getDrawable(
                android.R.drawable.list_selector_background, theme
            )
            isClickable = event.id.isNotEmpty()
            isEnabled = event.id.isNotEmpty()
            setOnClickListener { confirmAndDelete(event) }
        }

        card.addView(textColumn)
        card.addView(deleteBtn)
        return card
    }

    /** Maps message source to its neon accent color resource. */
    private fun accentColorRes(source: String): Int = when (source) {
        "sms", "rcs" -> R.color.neon_cyan
        "kakao" -> R.color.neon_magenta
        "gmail", "naver" -> R.color.neon_green
        else -> R.color.text_secondary
    }

    /** Maps message source to its event-card drawable (left stripe). */
    private fun cardBackgroundRes(source: String): Int = when (source) {
        "sms", "rcs" -> R.drawable.card_event_cyan
        "kakao" -> R.drawable.card_event_magenta
        "gmail", "naver" -> R.drawable.card_event_green
        else -> R.drawable.card_surface
    }

    /** Short uppercase source label shown in the meta line. */
    private fun sourceLabel(source: String): String = when (source) {
        "sms" -> "SMS"
        "rcs" -> "RCS"
        "kakao" -> "KAKAO"
        "gmail" -> "GMAIL"
        "naver" -> "NAVER"
        else -> source.uppercase()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun confirmAndDelete(event: CalendarRepo.Event) {
        AlertDialog.Builder(this)
            .setTitle("일정 삭제")
            .setMessage("Google 캘린더에서 영구 삭제됩니다.\n\n[${event.source.uppercase()}] ${event.title}\n\n삭제하시겠습니까?")
            .setPositiveButton("삭제") { _, _ ->
                lifecycleScope.launch {
                    try {
                        val ok = CalendarRepo.deleteEvent(this@MainActivity, event.id)
                        runOnUiThread {
                            Toast.makeText(
                                this@MainActivity,
                                if (ok) "삭제되었습니다" else "삭제 실패",
                                Toast.LENGTH_SHORT,
                            ).show()
                            loadStats()
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "deleteEvent failed: ${e.javaClass.simpleName}")
                        runOnUiThread {
                            Toast.makeText(this@MainActivity, "삭제 중 오류가 발생했습니다", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun goToLogin() {
        startActivity(Intent(this, LoginActivity::class.java))
        finish()
    }

    companion object {
        private const val TAG = "Planna"
    }
}
