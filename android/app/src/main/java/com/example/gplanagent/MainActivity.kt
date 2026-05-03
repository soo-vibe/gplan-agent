package com.example.gplanagent

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.gplanagent.auth.AuthManager
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
                        loadStats()
                    }
                }
            }
        }

    }

    override fun onResume() {
        super.onResume()
        refreshPermissionBanner()
        // 앱이 포그라운드로 올 때마다 RCS 캐치업
        lifecycleScope.launch {
            try {
                val ctx = this@MainActivity.applicationContext
                val msgs = RcsHelper.queryNewMessages(ctx)
                var maxId = 0L
                for (m in msgs) {
                    try {
                        val contact = ContactLookup.lookupByPhone(ctx, m.address)
                        ApiService.parseAndSave(
                            ctx, m.body,
                            source = "rcs",
                            sender = contact.name.ifBlank { m.address },
                            senderOrg = contact.organization,
                        )
                    } catch (e: Exception) { e.printStackTrace() }
                    if (m.id > maxId) maxId = m.id
                }
                if (maxId > 0) {
                    RcsHelper.updateLastSeenId(ctx, maxId)
                    runOnUiThread { loadStats() }
                }
            } catch (e: Exception) { e.printStackTrace() }
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
                            ApiService.logout(this@MainActivity)
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
                val stats = ApiService.getStats(this@MainActivity)
                runOnUiThread {
                    tvTodaySms.text = stats.todayAdded.sms.toString()
                    tvTodayKakao.text = stats.todayAdded.kakao.toString()
                    tvTodayGmail.text = stats.todayAdded.gmail.toString()

                    llSmsContainer.removeAllViews()
                    stats.todayList.forEach { event ->
                        llSmsContainer.addView(createEventItemView(event.title, event.start, event.source))
                    }
                }
            } catch (e: SessionExpiredException) {
                runOnUiThread { goToLogin() }
            } catch (e: NotLoggedInException) {
                runOnUiThread { goToLogin() }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun createEventItemView(title: String, start: String, source: String): View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 8, 0, 8)
        }

        val sourceColor = when (source) {
            "sms", "rcs" -> 0xFF1565C0.toInt()
            "kakao" -> 0xFF6A1B9A.toInt()
            "gmail" -> 0xFF2E7D32.toInt()
            else -> 0xFF757575.toInt()
        }

        val titleView = TextView(this).apply {
            text = "[${ source.uppercase()}] $title"
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(sourceColor)
        }

        val dateStr = try {
            val inputFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.getDefault())
            val outputFormat = SimpleDateFormat("MM/dd (E) HH:mm", Locale.KOREAN)
            outputFormat.format(inputFormat.parse(start)!!)
        } catch (e: Exception) { start }

        val dateView = TextView(this).apply {
            text = dateStr
            textSize = 12f
            setTextColor(resources.getColor(android.R.color.darker_gray, theme))
        }

        val divider = View(this).apply {
            setBackgroundColor(resources.getColor(android.R.color.darker_gray, theme))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1
            ).also { it.topMargin = 8 }
        }

        container.addView(titleView)
        container.addView(dateView)
        container.addView(divider)
        return container
    }

    private fun goToLogin() {
        startActivity(Intent(this, LoginActivity::class.java))
        finish()
    }

    companion object {
        private const val MENU_PERMISSIONS = 1
        private const val MENU_LOGOUT = 2
    }
}
