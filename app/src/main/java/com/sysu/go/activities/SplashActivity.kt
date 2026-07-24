package com.sysu.go.activities

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.firebase.auth.FirebaseAuth
import com.sysu.go.R
import com.sysu.go.utils.SessionManager
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class SplashActivity : AppCompatActivity() {

    private val TAG = "SplashActivity"

    @SuppressLint("CustomSplashScreen")
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        startFerrisWheelAnimation()

        // ALWAYS clear stale isTripActive flag on cold start.
        // This fixes: app was hard-killed during a trip → isTripActive=true stuck in SharedPrefs →
        // SplashActivity wrongly routes to TransitActivity (red-banner My Trip) instead of MainActivity.
        SessionManager(this).setTripActive(false)

        Handler(Looper.getMainLooper()).postDelayed({
            checkUserSession()
        }, 2000)
    }

    private val logo by lazy { findViewById<android.widget.ImageView>(R.id.ivLogo) }

    private var animationRunnable: Runnable? = null

    private fun startFerrisWheelAnimation() {
        var currentRotation = 0f
        var currentSpeed = 2.5f // Slightly faster start

        animationRunnable = object : Runnable {
            override fun run() {
                currentSpeed *= 1.004f // Faster acceleration
                if (currentSpeed > 400f) currentSpeed = 400f

                currentRotation += currentSpeed
                logo.rotation = currentRotation

                logo.postOnAnimationDelayed(this, 16)
            }
        }

        logo.post(animationRunnable!!)
    }

    private fun checkUserSession() {
        val session = SessionManager(this)
        if (!session.hasActiveSession()) {
            navigateTo(LoginActivity::class.java)
            return
        }

        // User is logged in — route based on trip state
        val destination = if (session.isTripActive()) {
            Log.d(TAG, "[SPLASH] Active session + active trip → TransitActivity")
            TransitActivity::class.java
        } else {
            Log.d(TAG, "[SPLASH] Active session, no trip → MainActivity")
            MainActivity::class.java
        }
        navigateTo(destination)
    }

    private fun navigateTo(cls: Class<*>) {
        val intent = Intent(this, cls)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        startActivity(intent)
        finish()
        // Premium fade animation like Uber/MoveIt
        overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
    }
}