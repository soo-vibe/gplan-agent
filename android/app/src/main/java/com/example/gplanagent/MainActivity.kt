package com.example.gplanagent

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.gplanagent.auth.AuthManager
import com.example.gplanagent.auth.GoogleAuthManager
import com.example.gplanagent.auth.LoginActivity
import com.example.gplanagent.onboarding.OnboardingActivity
import com.example.gplanagent.onboarding.PermissionStatus
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
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

        AuthManager.getEmail(this)?.let { supportActionBar?.subtitle = it }

        tvStatus = findViewById(R.id.tvStatus)
        tvTodaySms = findViewById(R.id.tvTodaySms)
        tvTodayKakao = findViewById(R.id.tvTodayKakao)
        tvTodayGmail = findViewById(R.id.tvTodayGmail)
        llSmsContainer = findViewById(R.id.llSmsContainer)
        tvPermissionWarning = findViewById(R.id.tvPermissionWarning)

        tvPermissionWarning.setOnClickListener {
            startActivity(Intent(this, OnboardingActivity::class.java))
        }

        loadStats()

        // RCS 폴링 — 백그라운드 15분 주기 + 한 번 캐치업
        RcsHelper.primeLastSeenIdIfUnset(this)
        RcsSyncWorker.schedulePeriodic(applicationContext)

        lifecycleScope.launch {
            ScheduleEventBus.events.collect { message ->
                runOnUiThread {
                    Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                    tvStatus.text = message
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
            tvPermissionWarning.text = "⚠  ${missing.joinToString(" · ")} 권한 누락 — 탭해서 설정하기"
            tvPermissionWarning.visibility = View.VISIBLE
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, MENU_PERMISSIONS, 0, "권한 설정")
        menu.add(0, MENU_LOGOUT, 1, "로그아웃")
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            MENU_PERMISSIONS -> {
                startActivity(Intent(this, OnboardingActivity::class.java))
                return true
            }
            MENU_LOGOUT -> {
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
                return true
            }
        }
        return super.onOptionsItemSelected(item)
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

    private fun createEventItemView(event: CalendarRepo.Event): View {
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 8, 0, 8)
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val sourceColor = when (event.source) {
            "sms", "rcs" -> 0xFF1565C0.toInt()
            "kakao" -> 0xFF6A1B9A.toInt()
            "gmail", "naver" -> 0xFF2E7D32.toInt()
            else -> 0xFF757575.toInt()
        }

        val textColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val titleView = TextView(this).apply {
            text = "[${event.source.uppercase()}] ${event.title}"
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(sourceColor)
        }

        val dateStr = try {
            val inputFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.getDefault())
            val outputFormat = SimpleDateFormat("MM/dd (E) HH:mm", Locale.KOREAN)
            outputFormat.format(inputFormat.parse(event.start)!!)
        } catch (e: Exception) { event.start }

        val dateView = TextView(this).apply {
            text = dateStr
            textSize = 12f
            setTextColor(resources.getColor(android.R.color.darker_gray, theme))
        }

        textColumn.addView(titleView)
        textColumn.addView(dateView)

        val deleteBtn = Button(this).apply {
            text = "🗑"
            textSize = 14f
            minWidth = 0
            minHeight = 0
            minimumWidth = 0
            minimumHeight = 0
            setPadding(20, 8, 20, 8)
            isEnabled = event.id.isNotEmpty()
            setOnClickListener { confirmAndDelete(event) }
        }

        row.addView(textColumn)
        row.addView(deleteBtn)

        val divider = View(this).apply {
            setBackgroundColor(resources.getColor(android.R.color.darker_gray, theme))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1
            ).also { it.topMargin = 8 }
        }

        outer.addView(row)
        outer.addView(divider)
        return outer
    }

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
        private const val TAG = "GPlanAgent"
        private const val MENU_PERMISSIONS = 1
        private const val MENU_LOGOUT = 2
    }
}
