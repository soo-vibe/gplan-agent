package com.example.gplanagent.auth

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.gplanagent.ApiService
import com.example.gplanagent.MainActivity
import com.example.gplanagent.R
import com.example.gplanagent.onboarding.OnboardingActivity
import com.example.gplanagent.onboarding.PermissionStatus
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.common.api.ApiException
import kotlinx.coroutines.launch

class LoginActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView

    private val signInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        try {
            val account = task.getResult(ApiException::class.java)
            handleSignedIn(account)
        } catch (e: ApiException) {
            tvStatus.text = "로그인 실패 (코드 ${e.statusCode})"
            Log.w(TAG, "GoogleSignIn ApiException: ${e.statusCode}")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        tvStatus = findViewById(R.id.tvLoginStatus)
        findViewById<TextView>(R.id.tvLoginEmail).text = AuthManager.getEmail(this) ?: ""

        findViewById<Button>(R.id.btnSignIn).setOnClickListener {
            tvStatus.text = "Google 계정 선택 중..."
            signInLauncher.launch(GoogleAuthManager.client(this).signInIntent)
        }

        if (AuthManager.isLoggedIn(this)) {
            goNext()
        }
    }

    private fun handleSignedIn(account: GoogleSignInAccount) {
        val idToken = account.idToken
        if (idToken.isNullOrEmpty()) {
            tvStatus.text = "ID 토큰을 받지 못했습니다"
            return
        }
        tvStatus.text = "백엔드 인증 중..."
        lifecycleScope.launch {
            try {
                val session = ApiService.googleSignIn(this@LoginActivity, idToken)
                AuthManager.saveSession(this@LoginActivity, session.token, session.email)
                runOnUiThread { goNext() }
            } catch (e: Exception) {
                Log.w(TAG, "googleSignIn failed: ${e.javaClass.simpleName} ${e.message}")
                runOnUiThread { tvStatus.text = "백엔드 인증 실패: ${e.javaClass.simpleName}" }
            }
        }
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

    companion object {
        private const val TAG = "GPlanAgent"
    }
}
