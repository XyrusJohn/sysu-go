package com.sysu.go.activities

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.text.Spannable
import android.text.SpannableString
import android.text.TextPaint
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.MetricAffectingSpan
import android.util.Patterns
import android.widget.*
import android.view.LayoutInflater
import android.view.WindowManager
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import com.google.android.material.button.MaterialButton
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.sysu.go.R
import com.sysu.go.firebase.FirebaseAuthManager
import com.sysu.go.utils.SessionManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class LoginActivity : AppCompatActivity() {

    private lateinit var etEmail: EditText
    private lateinit var etPassword: EditText
    private lateinit var btnLogin: Button
    private lateinit var btnGoogle: Button
    private lateinit var tvRegister: TextView
    private lateinit var cbShowPassword: CheckBox
    private lateinit var progressBar: ProgressBar

    private val RC_GOOGLE_SIGN_IN = 9001
    private lateinit var googleSignInClient: GoogleSignInClient

    @SuppressLint("MissingInflatedId")
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        
        // Fix for Notch/Status Bar white gap
        window.setFlags(
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )

        setContentView(R.layout.activity_login)

        val mainLayout = findViewById<android.view.View>(android.R.id.content)
        ViewCompat.setOnApplyWindowInsetsListener(mainLayout) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        FirebaseAuthManager.initialize(this)

        etEmail = findViewById(R.id.etEmail)
        etPassword = findViewById(R.id.etPassword)
        btnLogin = findViewById(R.id.btnLogin)
        btnGoogle = findViewById(R.id.btnGoogle)
        tvRegister = findViewById(R.id.btnRegister)
        cbShowPassword = findViewById(R.id.cbShowPassword)
        progressBar = findViewById(R.id.progressBar)

        startTypewriterAnimation()

        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(getString(R.string.default_web_client_id))
            .requestEmail()
            .build()
        googleSignInClient = GoogleSignIn.getClient(this, gso)

        cbShowPassword.setOnCheckedChangeListener { _, isChecked ->
            val cursorPos = etPassword.selectionStart
            if (isChecked) {
                etPassword.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            } else {
                etPassword.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            etPassword.setSelection(cursorPos)
        }

        btnLogin.setOnClickListener { loginWithEmail() }
        btnGoogle.setOnClickListener { signInWithGoogle() }
        tvRegister.setOnClickListener {
            // For this app, registration is handled via Google Sign-In
            // to ensure we have a valid account to attach driver details to.
            signInWithGoogle()
        }

        // Check if redirected because account was deleted
        val deletedMsg = intent.getStringExtra("EXTRA_ACCOUNT_DELETED_MSG")
        if (!deletedMsg.isNullOrBlank()) {
            AlertDialog.Builder(this)
                .setTitle("Account Deleted")
                .setMessage(deletedMsg)
                .setPositiveButton("OK", null)
                .setCancelable(false)
                .show()
        }

        // Check if user is already authenticated but missing session
        val currentUser = FirebaseAuth.getInstance().currentUser
        if (currentUser != null && deletedMsg.isNullOrBlank()) {
            handleLoginSuccess(currentUser.uid, currentUser.email ?: "")
        }
    }

    private fun startTypewriterAnimation() {
        val tvMainTitle = findViewById<TextView>(R.id.tvMainTitle)
        val fullText = getString(R.string.login_main_title) // "Sign Into Your\nFleet"
        
        val redColor = Color.parseColor("#c8102e")
        val typeface = ResourcesCompat.getFont(this, R.font.bacasime_antique)

        lifecycleScope.launch {
            while (true) {
                // Type forward
                for (i in 0..fullText.length) {
                    val currentText = fullText.substring(0, i)
                    val ssb = SpannableStringBuilder(currentText).append("|")
                    
                    applyTitleStyles(ssb, fullText, i, redColor, typeface)
                    
                    tvMainTitle.text = ssb
                    delay(100)
                }
                
                // Blink cursor at the end
                repeat(3) {
                    val ssbNoCursor = SpannableStringBuilder(fullText)
                    applyTitleStyles(ssbNoCursor, fullText, fullText.length, redColor, typeface)
                    tvMainTitle.text = ssbNoCursor
                    delay(500)
                    
                    val ssbWithCursor = SpannableStringBuilder(fullText).append("|")
                    applyTitleStyles(ssbWithCursor, fullText, fullText.length, redColor, typeface)
                    tvMainTitle.text = ssbWithCursor
                    delay(500)
                }

                // Backspace effect
                for (i in fullText.length downTo 0) {
                    val currentText = fullText.substring(0, i)
                    val ssb = SpannableStringBuilder(currentText).append("|")
                    
                    applyTitleStyles(ssb, fullText, i, redColor, typeface)
                    
                    tvMainTitle.text = ssb
                    delay(50)
                }
                delay(500)
            }
        }
    }

    private fun applyTitleStyles(ssb: SpannableStringBuilder, fullText: String, currentIndex: Int, color: Int, tf: Typeface?) {
        // Apply font to the entire length (including cursor) to prevent size jumping
        if (tf != null) {
            ssb.setSpan(CustomTypefaceSpan(tf), 0, ssb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        val targetWords = listOf("Your", "Fleet")
        
        for (word in targetWords) {
            val start = fullText.indexOf(word)
            if (start != -1 && currentIndex > start) {
                // Color even partial words as they are being typed
                val end = currentIndex.coerceAtMost(start + word.length)
                ssb.setSpan(ForegroundColorSpan(color), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
    }

    class CustomTypefaceSpan(private val typeface: Typeface) : MetricAffectingSpan() {
        override fun updateDrawState(ds: TextPaint) {
            applyCustomTypeFace(ds, typeface)
        }

        override fun updateMeasureState(paint: TextPaint) {
            applyCustomTypeFace(paint, typeface)
        }

        private fun applyCustomTypeFace(paint: Paint, tf: Typeface) {
            paint.typeface = tf
        }
    }

    private fun loginWithEmail() {
        val email = etEmail.text.toString().trim()
        val password = etPassword.text.toString().trim()

        if (email.isEmpty()) {
            etEmail.error = "Email is required"
            etEmail.requestFocus()
            return
        }
        if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            etEmail.error = "Enter a valid email address"
            etEmail.requestFocus()
            return
        }
        if (password.isEmpty()) {
            etPassword.error = "Password is required"
            etPassword.requestFocus()
            return
        }

        setLoading(true)
        lifecycleScope.launch {
            when (val result = FirebaseAuthManager.signInWithEmailPassword(email, password)) {
                is FirebaseAuthManager.AuthResult.Success -> {
                    handleLoginSuccess(result.user.uid, result.user.email ?: "")
                }
                is FirebaseAuthManager.AuthResult.Error -> {
                    setLoading(false)
                    showError(result.message)
                }
            }
        }
    }

    private fun signInWithGoogle() {
        googleSignInClient.signOut().addOnCompleteListener {
            val signInIntent = googleSignInClient.signInIntent
            startActivityForResult(signInIntent, RC_GOOGLE_SIGN_IN)
        }
    }

    @Deprecated("Deprecated in API 31")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == RC_GOOGLE_SIGN_IN) {
            val task = GoogleSignIn.getSignedInAccountFromIntent(data)
            try {
                val account = task.getResult(ApiException::class.java)
                val idToken = account.idToken
                if (idToken != null) {
                    setLoading(true)
                    val credential = GoogleAuthProvider.getCredential(idToken, null)
                    FirebaseAuth.getInstance().signInWithCredential(credential)
                        .addOnSuccessListener { authResult ->
                            val user = authResult.user
                            if (user != null) {
                                handleLoginSuccess(user.uid, user.email ?: "")
                            }
                        }
                        .addOnFailureListener { e ->
                            setLoading(false)
                            showError("Google sign-in failed: ${e.message}")
                        }
                }
            } catch (e: ApiException) {
                showError("Google sign-in failed: ${e.message}")
            }
        }
    }

    private fun handleLoginSuccess(uid: String, email: String) {
        lifecycleScope.launch {
            try {
                val db = com.google.firebase.firestore.FirebaseFirestore.getInstance()
                val doc = db.collection("users").document(uid).get().await()

                if (doc.exists()) {
                    val storedName = doc.getString("name") ?: ""
                    val storedPhone = doc.getString("phone") ?: ""
                    val storedFN = doc.getString("fn") ?: ""
                    val storedLN = doc.getString("ln") ?: ""
                    val storedMI = doc.getString("mi") ?: ""

                    // If profile is incomplete, force completion
                    if (storedFN.isEmpty() || storedLN.isEmpty() || storedPhone.isEmpty()) {
                        setLoading(false)
                        val intent = Intent(this@LoginActivity, RegistrationActivity::class.java)
                        intent.putExtra("email", email)
                        intent.putExtra("isGoogleSignIn", true)
                        intent.putExtra("name", storedName)
                        startActivity(intent)
                        // Removed finish() to allow back navigation
                        return@launch
                    }

                    // Rebuild clean display name from parts — Firestore "name" may
                    // still carry old double-dot MI from before the sanitizer fix.
                    val cleanName = buildString {
                        append(storedFN)
                        if (storedMI.isNotEmpty()) append(" ${storedMI.replace(".", "")}.")
                        if (storedLN.isNotEmpty()) append(" $storedLN")
                    }.trim().ifEmpty { storedName }

                    SessionManager(this@LoginActivity).saveDriverSessionWithSplit(
                        uid = uid,
                        fn = storedFN,
                        mi = storedMI,
                        ln = storedLN,
                        displayName = cleanName,
                        email = email,
                        phone = storedPhone
                    )
                    setLoading(false)
                    navigateToMain()
                } else {
                    setLoading(false)
                    // New user → route to registration
                    val intent = Intent(this@LoginActivity, RegistrationActivity::class.java)
                    intent.putExtra("email", email)
                    intent.putExtra("isGoogleSignIn", true)
                    intent.putExtra("name", FirebaseAuth.getInstance().currentUser?.displayName ?: "")
                    startActivity(intent)
                    // Removed finish() to allow back navigation
                }
            } catch (e: Exception) {
                setLoading(false)
                showError("Failed to verify account: ${e.message}")
            }
        }
    }

    private fun navigateToMain() {
        val intent = Intent(this, MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }

    private fun setLoading(loading: Boolean) {
        progressBar.visibility = if (loading) ProgressBar.VISIBLE else ProgressBar.GONE
        btnLogin.isEnabled = !loading
        btnGoogle.isEnabled = !loading
        etEmail.isEnabled = !loading
        etPassword.isEnabled = !loading
    }

    private fun showError(message: String) {
        val dlgView = LayoutInflater.from(this).inflate(R.layout.dialog_sysu_info, null)
        dlgView.findViewById<ImageView>(R.id.ivDialogIcon).setImageResource(R.drawable.ic_dialog_error)
        dlgView.findViewById<TextView>(R.id.tvDialogTitle).text = "Login Error"
        dlgView.findViewById<TextView>(R.id.tvDialogMessage).text = message
        val dialog = AlertDialog.Builder(this).setView(dlgView).create()
        dlgView.findViewById<MaterialButton>(R.id.btnDismiss).setOnClickListener { dialog.dismiss() }
        dialog.show()
    }
}
