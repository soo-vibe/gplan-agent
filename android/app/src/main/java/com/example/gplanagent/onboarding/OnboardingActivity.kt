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
        val grantedCount = cards.count { renderCard(it) }

        findViewById<TextView>(R.id.tvProgress).text = "$grantedCount / ${cards.size} 허용됨"
        findViewById<LinearProgressIndicator>(R.id.progressIndicator).setProgress(grantedCount, true)

        val all = grantedCount == cards.size
        val continueBtn = findViewById<MaterialButton>(R.id.btnContinue)
        continueBtn.isEnabled = all
        continueBtn.text = if (all) "✓  시작하기" else "권한을 모두 허용해주세요"
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
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
