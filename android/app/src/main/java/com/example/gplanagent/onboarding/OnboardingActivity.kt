package com.example.gplanagent.onboarding

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.example.gplanagent.MainActivity
import com.example.gplanagent.R
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator

class OnboardingActivity : AppCompatActivity() {

    private val smsPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { refreshAll() }

    private val settingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { refreshAll() }

    private data class Card(
        val statusId: Int,
        val actionId: Int,
        val accent: Int,
        val actionLabel: String,
        val granted: () -> Boolean,
        val onAction: () -> Unit,
        val optional: Boolean = false,
    )

    private lateinit var cards: List<Card>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_onboarding)

        cards = listOf(
            Card(
                statusId = R.id.tvSmsStatus,
                actionId = R.id.btnSmsAction,
                accent = 0xFF1565C0.toInt(),
                actionLabel = "권한 요청",
                granted = { PermissionStatus.smsGranted(this) },
                onAction = {
                    smsPermissionLauncher.launch(arrayOf(
                        Manifest.permission.RECEIVE_SMS,
                        Manifest.permission.READ_SMS,
                    ))
                }
            ),
            Card(
                statusId = R.id.tvNotificationStatus,
                actionId = R.id.btnNotificationAction,
                accent = 0xFF6A1B9A.toInt(),
                actionLabel = "설정 열기",
                granted = { PermissionStatus.notificationListenerGranted(this) },
                onAction = {
                    settingsLauncher.launch(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                }
            ),
            Card(
                statusId = R.id.tvBatteryStatus,
                actionId = R.id.btnBatteryAction,
                accent = 0xFFEF6C00.toInt(),
                actionLabel = "예외 등록",
                granted = { PermissionStatus.batteryUnrestricted(this) },
                onAction = { requestBatteryException() }
            ),
            Card(
                statusId = R.id.tvContactsStatus,
                actionId = R.id.btnContactsAction,
                accent = 0xFF2E7D32.toInt(),
                actionLabel = "권한 요청",
                granted = { PermissionStatus.contactsGranted(this) },
                onAction = {
                    smsPermissionLauncher.launch(arrayOf(Manifest.permission.READ_CONTACTS))
                },
                optional = true,
            ),
        )

        cards.forEach { card ->
            findViewById<MaterialButton>(card.actionId).setOnClickListener { card.onAction() }
        }

        findViewById<MaterialButton>(R.id.btnContinue).setOnClickListener { goToMain() }
        findViewById<MaterialButton>(R.id.btnSkip).setOnClickListener { goToMain() }
    }

    override fun onResume() {
        super.onResume()
        refreshAll()
    }

    @SuppressLint("BatteryLife")
    private fun requestBatteryException() {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:$packageName")
        }
        try {
            settingsLauncher.launch(intent)
        } catch (e: Exception) {
            settingsLauncher.launch(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun refreshAll() {
        val results = cards.map { renderCard(it) to it.optional }
        val requiredCards = results.filter { !it.second }
        val requiredGranted = requiredCards.count { it.first }
        val requiredTotal = requiredCards.size

        findViewById<TextView>(R.id.tvProgress).text = "$requiredGranted / $requiredTotal 허용됨"
        findViewById<LinearProgressIndicator>(R.id.progressIndicator).max = requiredTotal
        findViewById<LinearProgressIndicator>(R.id.progressIndicator).setProgress(requiredGranted, true)

        val allRequired = requiredGranted == requiredTotal
        val continueBtn = findViewById<MaterialButton>(R.id.btnContinue)
        continueBtn.isEnabled = allRequired
        continueBtn.text = if (allRequired) "✓  시작하기" else "필수 권한을 모두 허용해주세요"
    }

    /** Returns true if granted. */
    private fun renderCard(card: Card): Boolean {
        val granted = card.granted()

        val status = findViewById<TextView>(card.statusId)
        if (granted) {
            status.text = "✓ 허용됨"
            status.setTextColor(0xFF2E7D32.toInt())
            status.setBackgroundResource(R.drawable.badge_granted)
        } else {
            status.text = "필요"
            status.setTextColor(0xFFE65100.toInt())
            status.setBackgroundResource(R.drawable.badge_needed)
        }

        val btn = findViewById<MaterialButton>(card.actionId)
        if (granted) {
            btn.text = "완료됨"
            btn.isEnabled = false
            btn.setBackgroundColor(0xFFE0E0E0.toInt())
            btn.setTextColor(0xFF9E9E9E.toInt())
        } else {
            btn.text = card.actionLabel
            btn.isEnabled = true
            btn.setBackgroundColor(card.accent)
            btn.setTextColor(0xFFFFFFFF.toInt())
        }
        return granted
    }

    private fun goToMain() {
        // 메뉴에서 진입했으면 기존 MainActivity가 스택에 있음 → 그것을 onNewIntent로 깨우고
        // 위에 쌓인 OnboardingActivity는 자동 정리. 첫 로그인 직후 진입이면 새로 띄움.
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivity(intent)
        finish()
    }
}
