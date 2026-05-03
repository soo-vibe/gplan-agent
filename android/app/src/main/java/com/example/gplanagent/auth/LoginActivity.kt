package com.example.gplanagent.auth

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.browser.customtabs.CustomTabsIntent
import com.example.gplanagent.BuildConfig
import com.example.gplanagent.MainActivity
import com.example.gplanagent.R
import com.example.gplanagent.onboarding.OnboardingActivity
import com.example.gplanagent.onboarding.PermissionStatus

class LoginActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        tvStatus = findViewById(R.id.tvLoginStatus)
        findViewById<TextView>(R.id.tvLoginEmail).text = AuthManager.getEmail(this) ?: ""

        findViewById<Button>(R.id.btnSignIn).setOnClickListener {
            tvStatus.text = "브라우저로 이동 중..."
            val url = Uri.parse("${BuildConfig.BASE_URL}/oauth/login")
            CustomTabsIntent.Builder().build().launchUrl(this, url)
        }

        findViewById<Button>(R.id.btnRequestAccess).setOnClickListener {
            startActivity(Intent(this, AccessRequestActivity::class.java))
        }

        if (intent?.data == null && AuthManager.isLoggedIn(this)) {
            goNext()
            return
        }
        handleAuthIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAuthIntent(intent)
    }

    private fun handleAuthIntent(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme != "gplanagent" || data.host != "login") return

        val err = data.getQueryParameter("error")
        if (err != null) {
            tvStatus.text = "로그인 실패: $err"
            return
        }
        val token = data.getQueryParameter("token")
        if (token.isNullOrEmpty()) {
            tvStatus.text = "토큰을 받지 못했습니다"
            return
        }
        val email = data.getQueryParameter("email") ?: ""
        AuthManager.saveSession(this, token, email)
        goNext()
    }

    private fun goNext() {
        val target = if (PermissionStatus.allGranted(this)) {
            MainActivity::class.java
        } else {
            OnboardingActivity::class.java
        }
        startActivity(Intent(this, target))
        finish()
    }
}
