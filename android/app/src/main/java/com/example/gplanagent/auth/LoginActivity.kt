package com.example.gplanagent.auth

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.example.gplanagent.MainActivity
import com.example.gplanagent.R
import com.example.gplanagent.onboarding.OnboardingActivity
import com.example.gplanagent.onboarding.PermissionStatus
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.common.api.ApiException

class LoginActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView

    private val signInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        try {
            task.getResult(ApiException::class.java)
            goNext()
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
