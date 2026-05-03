package com.example.gplanagent.auth

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Patterns
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.gplanagent.ApiService
import com.example.gplanagent.R
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch

class AccessRequestActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_access_request)

        val emailField = findViewById<EditText>(R.id.etEmail)
        val nameField = findViewById<EditText>(R.id.etName)
        val submitBtn = findViewById<MaterialButton>(R.id.btnSubmit)
        val statusView = findViewById<TextView>(R.id.tvStatus)

        fun validate() {
            val email = emailField.text.toString().trim()
            submitBtn.isEnabled = Patterns.EMAIL_ADDRESS.matcher(email).matches()
        }
        emailField.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = validate()
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
        validate()

        submitBtn.setOnClickListener {
            val email = emailField.text.toString().trim()
            val name = nameField.text.toString().trim()
            submitBtn.isEnabled = false
            statusView.text = "전송 중..."
            statusView.visibility = View.VISIBLE
            lifecycleScope.launch {
                try {
                    val message = ApiService.requestAccess(this@AccessRequestActivity, email, name)
                    runOnUiThread {
                        statusView.text = "✓  $message"
                        Toast.makeText(this@AccessRequestActivity, "요청 접수됨", Toast.LENGTH_LONG).show()
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    runOnUiThread {
                        statusView.text = "오류: ${e.message ?: "전송 실패"}"
                        submitBtn.isEnabled = true
                    }
                }
            }
        }

        findViewById<MaterialButton>(R.id.btnBack).setOnClickListener { finish() }
    }
}
