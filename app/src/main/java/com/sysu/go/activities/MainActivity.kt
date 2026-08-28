// MainActivity
package com.sysu.go.activities

import android.annotation.SuppressLint
import android.Manifest
import android.util.Log
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ImageView
import android.widget.ImageButton
import android.widget.Toast
import com.sysu.go.utils.Sanitizer
import com.google.android.material.button.MaterialButton
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts

import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.DocumentSnapshot
import com.sysu.go.R
import com.sysu.go.databinding.ActivityMainBinding
import com.sysu.go.activities.TransitActivity
import com.sysu.go.firebase.FirebaseAuthManager
import com.sysu.go.network.SocketManager
import com.sysu.go.services.LocationServices
import com.sysu.go.utils.ConnectivityMonitor
import com.sysu.go.utils.NetworkUtils // Import NetworkUtils
import com.sysu.go.utils.PermissionHelper
import com.sysu.go.utils.SessionManager
import com.sysu.go.utils.TrackingStateMonitor
import com.sysu.go.model.Assignment
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import com.google.firebase.Timestamp
import java.util.Locale
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkInfo
import android.os.Handler
import android.os.Looper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val db = FirebaseFirestore.getInstance()
    private var isInQueue = false
    private var hasShipment = false  // true when driver has an assigned shipment
    private var queueListener: com.google.firebase.firestore.ListenerRegistration? = null
    private var positionListener: com.google.firebase.firestore.ListenerRegistration? = null
    private var assignmentListener: com.google.firebase.firestore.ListenerRegistration? = null
    private var pulseAnimator1: ObjectAnimator? = null
    private var pulseAnimator2: ObjectAnimator? = null
    private val pulseHandler = Handler(Looper.getMainLooper())
    private var pulseRing1Runnable: Runnable? = null
    private var pulseRing2Runnable: Runnable? = null
    private var statusDotRunnable: Runnable? = null
    private val statusDotHandler = Handler(Looper.getMainLooper())
    private var isStatusDotPulsing = false

    private val trackingStateListener = object : TrackingStateMonitor.Listener {
        override fun onTrackingStateChanged(state: TrackingStateMonitor.State) {
            runOnUiThread { updateTrackingBadge(state) }
        }
    }

    /** Tracks the current queue document ID for cancel operations */
    private var currentQueueDocId: String? = null

    // Heartbeat monitoring variables
    private val heartbeatHandler = Handler(Looper.getMainLooper())
    private val HEARTBEAT_INTERVAL_MS = 5000L // Check every 5 seconds
    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            if (!NetworkUtils.isInternetAvailable(this@MainActivity)) {
                Log.d(TAG, "I'm losing internet connection")
            }
            heartbeatHandler.postDelayed(this, HEARTBEAT_INTERVAL_MS)
        }
    }

    private var isGuardVerified = false

    /** Tracks bounce circle animators so they can be cancelled onDestroy */
    private val circleAnimators = mutableListOf<android.animation.Animator>()
    /** Tracks views injected into circleContainer so they can be removed onDestroy */
    private val circleViews = mutableListOf<View>()
    /** Tracks My Trip tab's own circle animators — separate from home banner */
    private val myTripCircleAnimators = mutableListOf<android.animation.Animator>()
    /** Tracks My Trip tab's own circle views — separate from home banner */
    private val myTripCircleViews = mutableListOf<View>()
    /** Prevents double-init of My Trip circle animations */
    private var myTripCirclesInitialized = false
    /** Tracks the bounce arrow animator so it can be cancelled onDestroy */
    private var bounceArrowAnimator: ObjectAnimator? = null
    /** Connection loss popup guard — prevents double-dialog if network flaps */
    private var isOfflineDialogShowing = false
    /** BroadcastReceiver for connectivity changes — registered in onResume, unregistered in onPause */
    private var connectionReceiver: BroadcastReceiver? = null
    /** Adapter driving rvShipmentCards.
        * Phase 1 (0-1 shipments): ShipmentCardAdapter (vertical, shown in cardMyQueue).
        * Phase 2 (2+ shipments): ShipmentCardHorizontalAdapter (horizontal scroll carousel).
        * Kept as ShipmentCardAdapter? but a local horizontal adapter is cast and assigned
        * here at runtime — only submitList is called on this field, which both types share. */
    private var shipmentCardAdapter: com.sysu.go.adapter.ShipmentCardAdapter? = null
    /** In-memory list of queue tabs — used to determine 0 / 1 / 2+ shipment visibility */
    private val currentQueueEntries = mutableListOf<com.sysu.go.adapter.QueueTab>()
    /** In-memory list of loaded Assignment objects — used to populate vertical shipment cards */
    private val currentShipments = mutableListOf<com.sysu.go.model.Assignment>()

    companion object {
        private const val TAG = "MainActivity"
        private const val PREFS_NAME = "go_prefs"
        private const val KEY_QR_TUTORIAL_SEEN = "qr_tutorial_seen"
        private const val KEY_NAV_TUTORIAL_SEEN = "nav_tutorial_seen"
        private const val KEY_TUTORIALS_ENABLED = "tutorials_enabled"
    }

    private val prefs: SharedPreferences by lazy {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
    }

    // Runtime permission request for POST_NOTIFICATIONS (Android 13+).
    // Without this, the foreground service notification is silently dropped.
    // On Android < 13 this is NO-OP — permission is auto-granted at manifest install.
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            Log.i(TAG, "POST_NOTIFICATIONS granted — foreground notification will show")
        } else {
            Log.w(TAG, "POST_NOTIFICATIONS denied — notification may not appear. Please enable in Settings.")
        }
        // Always start the service — even if denied, the service runs (just silently)
        startForegroundService(Intent(this, LocationServices::class.java))
    }

    @SuppressLint("MissingInflatedId")
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // ── Permission setup gate ─────────────────────────────────────────
        // If driver hasn't completed the permission wizard or is missing required
        // permissions (location / notification / battery), show the setup screen
        // instead of the dashboard. Mark as seen immediately so a back-press
        // doesn't loop back to the setup screen.
        if (PermissionHelper.needsSetup(this)) {
            PermissionHelper.markSetupCompleted(this)
            startActivity(Intent(this, PermissionSetupActivity::class.java))
            // Do NOT call finish() — let the system manage the back stack
            return
        }
        // ─────────────────────────────────────────────────────────────────

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        FirebaseAuthManager.initialize(this)
        // Request notification permission (Android 13+) first, then start service in callback.
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)

        android.widget.Toast.makeText(this, "Maligayang pagdating, driver! I-tap ang QR button sa ibaba para mag-scan at makapasok sa pila.", android.widget.Toast.LENGTH_LONG).show()

        loadDriverInfo()
        setupUI()
        setupBottomNav()
        setupTutorial()
        listenToQueueStatus()
        startCircleAnimation()
        startPulseAnimation()
        TrackingStateMonitor.addListener(trackingStateListener)
    }

    private fun setupTutorial() {
        // Always show tutorials every app load — never skip based on "seen" flags
        if (binding.qrTutorialOverlay.visibility == View.VISIBLE ||
            binding.navTutorialOverlay.visibility == View.VISIBLE) return

        val tutorialsEnabled = prefs.getBoolean(KEY_TUTORIALS_ENABLED, true)
        if (!tutorialsEnabled) return

        showQrTutorial { showNavTutorial() }
    }

    private fun showQrTutorial(onDismiss: () -> Unit) {
        binding.qrTutorialOverlay.visibility = View.VISIBLE
        binding.qrTutorialOverlay.alpha = 0f
        binding.cbQrTutorialDontShow.isChecked = false
        ObjectAnimator.ofFloat(binding.qrTutorialOverlay, "alpha", 0f, 1f).apply {
            duration = 300L
            start()
        }
        binding.btnGotIt.setOnClickListener {
            val dontShowAgain = binding.cbQrTutorialDontShow.isChecked
            if (dontShowAgain) {
                prefs.edit()
                    .putBoolean(KEY_TUTORIALS_ENABLED, false)
                    .apply()
            }
            ObjectAnimator.ofFloat(binding.qrTutorialOverlay, "alpha", 1f, 0f).apply {
                duration = 300L
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        binding.qrTutorialOverlay.visibility = View.GONE
                        if (!dontShowAgain) {
                            onDismiss()
                        }
                        // If "Don't show again" was checked, suppress nav tutorial too
                    }
                })
                start()
            }
        }
    }

    private fun showNavTutorial() {
        val tutorialsEnabled = prefs.getBoolean(KEY_TUTORIALS_ENABLED, true)
        if (!tutorialsEnabled) return

        binding.navTutorialOverlay.visibility = View.VISIBLE
        binding.navTutorialOverlay.alpha = 0f
        binding.cbNavTutorialDontShow.isChecked = false
        ObjectAnimator.ofFloat(binding.navTutorialOverlay, "alpha", 0f, 1f).apply {
            duration = 300L
            start()
        }
        binding.btnNavTutorialDone.setOnClickListener {
            val dontShowAgain = binding.cbNavTutorialDontShow.isChecked
            if (dontShowAgain) {
                prefs.edit()
                    .putBoolean(KEY_TUTORIALS_ENABLED, false)
                    .apply()
            }
            ObjectAnimator.ofFloat(binding.navTutorialOverlay, "alpha", 1f, 0f).apply {
                duration = 300L
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        binding.navTutorialOverlay.visibility = View.GONE
                    }
                })
                start()
            }
        }
    }

    private fun startCircleAnimation() {
        binding.circleContainer.post {
            val containerW = binding.circleContainer.width
            val containerH = binding.circleContainer.height
            val random = java.util.Random()

            val count = random.nextInt(3) + 3
            for (i in 0 until count) {
                val sizeDp = 40 + random.nextInt(101)
                val sizePx = (sizeDp * resources.displayMetrics.density).toInt()
                val startX = random.nextInt(containerW).toFloat()
                val startY = random.nextInt(containerH).toFloat()
                val minSpeed = 0.2f + random.nextFloat() * 0.2f
                val maxSpeed = minSpeed + 0.2f + random.nextFloat() * 0.3f
                val alpha = 0.35f + random.nextFloat() * 0.35f

                val circle = createBounceCircle(sizePx, alpha)
                binding.circleContainer.addView(circle)
                circleViews.add(circle)

                startBounceAnimation(
                    view = circle,
                    viewSize = sizePx,
                    containerWidth = containerW,
                    containerHeight = containerH,
                    minSpeed = minSpeed,
                    maxSpeed = maxSpeed,
                    initialX = startX,
                    initialY = startY,
                    startDelayMs = (i * 150L) + random.nextInt(500)
                )
            }
        }
    }

    private fun createBounceCircle(sizePx: Int, alpha: Float): View {
        return View(this).apply {
            layoutParams = FrameLayout.LayoutParams(sizePx, sizePx)
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_circle_light_red)
            this.alpha = alpha
        }
    }

    private fun startBounceAnimation(
        view: View,
        viewSize: Int,
        containerWidth: Int,
        containerHeight: Int,
        minSpeed: Float,
        maxSpeed: Float,
        initialX: Float,
        initialY: Float,
        startDelayMs: Long
    ) {
        val random = java.util.Random()
        val maxX = (containerWidth - viewSize).toFloat().coerceAtLeast(0f)
        val maxY = (containerHeight - viewSize).toFloat().coerceAtLeast(0f)

        view.translationX = initialX
        view.translationY = initialY

        var velX = (random.nextFloat() * (maxSpeed - minSpeed) + minSpeed) * if (random.nextBoolean()) 1f else -1f
        var velY = (random.nextFloat() * (maxSpeed - minSpeed) + minSpeed) * if (random.nextBoolean()) 1f else -1f

        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 16L
            repeatCount = ValueAnimator.INFINITE
            startDelay = startDelayMs
            addUpdateListener {
                if (random.nextFloat() < 0.02f) {
                    velX += (random.nextFloat() - 0.5f) * 0.4f
                    velX = velX.coerceIn(-maxSpeed, maxSpeed)
                }
                if (random.nextFloat() < 0.02f) {
                    velY += (random.nextFloat() - 0.5f) * 0.3f
                    velY = velY.coerceIn(-maxSpeed, maxSpeed)
                }

                var newX = view.translationX + velX
                var newY = view.translationY + velY

                if (newX <= 0) {
                    newX = 0f
                    velX = kotlin.math.abs(velX).coerceIn(minSpeed, maxSpeed)
                } else if (newX >= maxX) {
                    newX = maxX
                    velX = -kotlin.math.abs(velX).coerceIn(minSpeed, maxSpeed)
                }

                if (newY <= 0) {
                    newY = 0f
                    velY = kotlin.math.abs(velY).coerceIn(minSpeed, maxSpeed)
                } else if (newY >= maxY) {
                    newY = maxY
                    velY = -kotlin.math.abs(velY).coerceIn(minSpeed, maxSpeed)
                }

                view.translationX = newX
                view.translationY = newY
            }
            circleAnimators.add(this)
            start()
        }

        ObjectAnimator.ofFloat(view, "scaleX", 1f, 1.08f).apply {
            duration = 4000L + random.nextInt(1500)
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            startDelay = startDelayMs
            circleAnimators.add(this)
            start()
        }

        ObjectAnimator.ofFloat(view, "scaleY", 1f, 1.06f).apply {
            duration = 4500L + random.nextInt(1500)
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            startDelay = startDelayMs + 600L
            circleAnimators.add(this)
            start()
        }
    }

    private fun startPulseAnimation() {
        bounceArrowAnimator = ObjectAnimator.ofFloat(binding.ivBounceArrow, "translationY", 0f, 12f).apply {
            duration = 800L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }

        startPulseRing(binding.pulseRing1, 0L)
        startPulseRing(binding.pulseRing2, 1000L)
    }

    private fun startPulseRing(ring: View, delayMs: Long) {
        val runnable = Runnable {
            if (ring == binding.pulseRing1) {
                pulseAnimator1 = ObjectAnimator.ofFloat(ring, "alpha", 0f, 0.6f).apply {
                    duration = 2000L
                    start()
                }
                pulseAnimator2 = ObjectAnimator.ofFloat(ring, "scaleX", 1f, 1.5f).apply {
                    duration = 2000L
                    start()
                }
            } else {
                pulseAnimator2 = ObjectAnimator.ofFloat(ring, "alpha", 0f, 0.6f).apply {
                    duration = 2000L
                    start()
                }
                pulseAnimator1 = ObjectAnimator.ofFloat(ring, "scaleX", 1f, 1.5f).apply {
                    duration = 2000L
                    start()
                }
            }
            ObjectAnimator.ofFloat(ring, "scaleY", 1f, 1.5f).apply {
                duration = 2000L
                interpolator = AccelerateDecelerateInterpolator()
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        ring.scaleX = 1f
                        ring.scaleY = 1f
                        ring.alpha = 0f
                        startPulseRing(ring, 0L)
                    }
                })
                start()
            }
        }
        pulseHandler.postDelayed(runnable, delayMs)
        if (ring == binding.pulseRing1) {
            pulseRing1Runnable = runnable
        } else {
            pulseRing2Runnable = runnable
        }
    }

    private fun stopPulseRings() {
        pulseRing1Runnable?.let { pulseHandler.removeCallbacks(it) }
        pulseRing2Runnable?.let { pulseHandler.removeCallbacks(it) }
        pulseRing1Runnable = null
        pulseRing2Runnable = null
        pulseAnimator1?.cancel()
        pulseAnimator2?.cancel()
        pulseAnimator1 = null
        pulseAnimator2 = null
        binding.pulseRing1.scaleX = 1f
        binding.pulseRing1.scaleY = 1f
        binding.pulseRing1.alpha = 0f
        binding.pulseRing2.scaleX = 1f
        binding.pulseRing2.scaleY = 1f
        binding.pulseRing2.alpha = 0f
    }

    /**
     * Updates the vertical shipment cards row.
     * Shows the row only when there are 2 or more assigned shipments.
     * Called from syncShipmentCards whenever the shipment list changes.
     *
     * Phase 1 (0 or 1 shipment): cardMyQueue shows. rvShipmentCards stays GONE.
     * Phase 2+ (2+ shipments): cardMyQueue hidden. rvShipmentCards shows vertical list
     * of full-width shipment cards, each tappable → QueueDetailsActivity for that shipment.
     */
    private fun updateQueueTabs() {
        when {
            currentShipments.isEmpty() -> {
                // No shipments — hide both shipment displays
                binding.rvShipmentCards.visibility = View.GONE
                binding.cardMyQueue.visibility = View.GONE
                binding.myQueueSection.root.visibility = View.GONE
                return
            }
            else -> {
                // 1 OR MORE shipments — always show rvShipmentCards with cards
                binding.cardMyQueue.visibility = View.GONE
                binding.myQueueSection.root.visibility = View.GONE
                binding.rvShipmentCards.visibility = View.VISIBLE
            }
        }
        // ── Vertical stacking: one full-width card per shipment, scrollable ──
        binding.rvShipmentCards.layoutManager =
            androidx.recyclerview.widget.LinearLayoutManager(this)

        // Re-create adapter — uses ShipmentCardAdapter (vertical layout, item_shipment_card.xml)
        shipmentCardAdapter = com.sysu.go.adapter.ShipmentCardAdapter(
            sessionActiveDocId = SessionManager(this).getActiveSapDocId()
        ) { assignment ->
            // Card tapped → navigate based on trip status
            when {
                assignment.isInTransit -> {
                    // Active trip in progress → open MyTripActivity for POD execution
                    val intent = Intent(this, MyTripActivity::class.java)
                    intent.putExtra("shipment_doc_id", assignment.docId)
                    startActivity(intent)
                }
                assignment.isCompleted -> {
                    // Completed → read-only per-shipment detail view
                    val intent = Intent(this, QueueDetailsActivity::class.java)
                    intent.putExtra("shipment_doc_id", assignment.docId)
                    startActivity(intent)
                }
                else -> {
                    // Pre-transit → QueueDetailsActivity for review
                    val intent = Intent(this, QueueDetailsActivity::class.java)
                    intent.putExtra("shipment_doc_id", assignment.docId)
                    startActivity(intent)
                }
            }
        }
        binding.rvShipmentCards.adapter = shipmentCardAdapter
        shipmentCardAdapter?.submitList(currentShipments.toList())
    }

    private fun updateScanButtonState() {
        val tripStatus = currentShipments.firstOrNull()?.tripStatus
        when {
            // ── IN TRANSIT — active trip in progress, map icon shows live tracking ──
            tripStatus == "in_transit" -> {
                binding.btnScanQR.setImageResource(R.drawable.map)
                binding.tvStatusPill?.text = "IN TRANSIT"
                binding.tvStatusPill?.setTextColor(ContextCompat.getColor(this, R.color.white))
                binding.tvCTAHeader?.text = "Trip In Progress"
                binding.tvCTABody?.text = "Your delivery is on the way. Tap to open tracking."
                binding.statusPill?.setBackgroundResource(R.drawable.bg_status_pill_red)
                binding.ctaStatusDot?.setBackgroundResource(R.drawable.bg_status_dot_green)

                updateReadyBadge("IN TRANSIT", R.color.white, R.color.frosted_green)
            }
            // ── LOADED — verified, ready to start trip ──
            tripStatus == "loaded" -> {
                binding.btnScanQR.setImageResource(R.drawable.map)
                binding.tvStatusPill?.text = "LOADED (START TRIP)"
                binding.tvStatusPill?.setTextColor(ContextCompat.getColor(this, R.color.white))
                binding.tvCTAHeader?.text = "Cargo Verified & Loaded"
                binding.tvCTABody?.text = "Guard has confirmed. Ready to start your trip."
                binding.statusPill?.setBackgroundResource(R.drawable.bg_status_pill_green)
                startStatusDotPulse()
                binding.ctaStatusDot?.setBackgroundResource(R.drawable.bg_status_dot_green)

                updateReadyBadge("LOADED (START TRIP)", R.color.white, R.color.frosted_green)
            }
            // ── LOADING — cargo is being loaded at bay ──
            tripStatus == "loading" -> {
                binding.btnScanQR.setImageResource(R.drawable.map)
                binding.tvStatusPill?.text = "LOADING CARGO"
                binding.tvStatusPill?.setTextColor(ContextCompat.getColor(this, R.color.white))
                binding.tvCTAHeader?.text = "Loading In Progress"
                binding.tvCTABody?.text = "Your cargo is being loaded. Stay present until fully loaded."
                binding.statusPill?.setBackgroundResource(R.drawable.bg_status_pill_amber_solid)
                startStatusDotPulse()
                binding.ctaStatusDot?.setBackgroundResource(R.drawable.bg_status_dot_amber)

                updateReadyBadge("LOADING CARGO", R.color.white, R.color.frosted_amber)
            }
            // ── FULLY LOADED — guard verification needed before trip ──
            tripStatus == "fully_loaded" -> {
                binding.tvStatusPill?.text = "GUARD VERIFICATION"
                binding.tvStatusPill?.setTextColor(ContextCompat.getColor(this, R.color.white))
                binding.tvCTAHeader?.text = "Guard Verification Required"
                binding.tvCTABody?.text = "Loading is complete. Verify with a guard before you can start your trip."
                binding.statusPill?.setBackgroundResource(R.drawable.bg_status_pill_red)
                startStatusDotPulse()
                binding.ctaStatusDot?.setBackgroundResource(R.drawable.bg_status_dot_red)

                updateReadyBadge("GUARD VERIFICATION", R.color.white, R.color.frosted_red)
            }
            // ── Guard verified, awaiting driver confirmation ──
            currentShipments.any { it.guardVerified } &&
                !currentShipments.all { it.guardVerified && it.driverConfirmed } -> {
                binding.btnScanQR.setImageResource(R.drawable.map)
                binding.tvStatusPill?.text = "GUARD VERIFIED"
                binding.tvStatusPill?.setTextColor(ContextCompat.getColor(this, R.color.white))
                binding.tvCTAHeader?.text = "Guard Verified — Confirm Shipment"
                binding.tvCTABody?.text = "Guard has signed off. Confirm your shipment to start the trip."
                binding.statusPill?.setBackgroundResource(R.drawable.bg_status_pill_green)
                startStatusDotPulse()
                binding.ctaStatusDot?.setBackgroundResource(R.drawable.bg_status_dot_green)

                updateReadyBadge("GUARD VERIFIED", R.color.white, R.color.frosted_green)
            }
            // ── All shipments verified AND confirmed — START TRIP ready ──
            currentShipments.isNotEmpty() &&
                currentShipments.all { it.guardVerified && it.driverConfirmed } -> {
                binding.btnScanQR.setImageResource(R.drawable.map)
                binding.tvStatusPill?.text = getString(R.string.cta_status_start_trip)
                binding.tvStatusPill?.setTextColor(ContextCompat.getColor(this, R.color.transit_green))
                binding.tvCTAHeader?.text = getString(R.string.cta_header_start_trip)
                binding.tvCTABody?.text = getString(R.string.cta_body_start_trip)
                binding.statusPill?.setBackgroundResource(R.drawable.bg_status_pill_green)
                startStatusDotPulse()
                binding.ctaStatusDot?.setBackgroundResource(R.drawable.bg_status_dot_green)

                updateReadyBadge("START TRIP", R.color.white, R.color.frosted_green)
            }
            hasShipment -> {
                // ASSIGNED — shipment assigned, guard verification happens in AssignmentActivity
                binding.btnScanQR.setImageResource(R.drawable.ticket_1)
                binding.tvStatusPill?.text = getString(R.string.cta_status_assigned)
                binding.tvStatusPill?.setTextColor(ContextCompat.getColor(this, R.color.white))
                binding.tvCTAHeader?.text = getString(R.string.cta_header_assigned)
                binding.tvCTABody?.text = getString(R.string.cta_body_assigned)
                binding.statusPill?.setBackgroundResource(R.drawable.bg_status_pill_blue)
                startStatusDotPulse()
                binding.ctaStatusDot?.setBackgroundResource(R.drawable.bg_status_dot_gold)

                updateReadyBadge("ASSIGNED", R.color.white, R.color.frosted_blue)
            }
            isInQueue -> {
                // IN QUEUE — waiting in queue to be called by booth
                binding.btnScanQR.setImageResource(R.drawable.ticket_1)
                binding.tvStatusPill?.text = getString(R.string.cta_status_queue)
                binding.tvStatusPill?.setTextColor(ContextCompat.getColor(this, R.color.sysu_gold))
                binding.tvCTAHeader?.text = getString(R.string.cta_header_queue)
                binding.tvCTABody?.text = getString(R.string.cta_body_queue)
                binding.statusPill?.setBackgroundResource(R.drawable.bg_status_pill_queued)
                stopStatusDot()
                binding.ctaStatusDot?.setBackgroundResource(R.drawable.bg_status_dot_gold)

                updateReadyBadge("ON QUEUE", R.color.white, R.color.frosted_gold)
            }
            else -> {
                // NOT ON QUEUE — scan QR to join queue
                binding.btnScanQR.setImageResource(R.drawable.qr_icon)
                binding.tvStatusPill?.text = getString(R.string.cta_status_scan)
                binding.tvStatusPill?.setTextColor(ContextCompat.getColor(this, R.color.sysu_red))
                binding.tvCTAHeader?.text = getString(R.string.cta_header_scan)
                binding.tvCTABody?.text = getString(R.string.cta_body_scan)
                binding.statusPill?.setBackgroundResource(R.drawable.bg_status_pill)
                startStatusDotPulse()
                binding.ctaStatusDot?.setBackgroundResource(R.drawable.bg_status_dot)

                updateReadyBadge("READY TO SCAN", R.color.white, R.color.frosted_white)
            }
        }
    }

    private fun updateReadyBadge(text: String, dotColor: Int, bgColor: Int) {
        binding.tvReadyBadgeText?.text = text
        binding.readyBadgeDot?.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(this, dotColor)
        )
        binding.readyBadge?.setCardBackgroundColor(
            ColorStateList.valueOf(ContextCompat.getColor(this, bgColor))
        )
    }

    private fun startStatusDotPulse() {
        if (isStatusDotPulsing) return
        isStatusDotPulsing = true

        val dot = binding.ctaStatusDot
        statusDotRunnable = object : Runnable {
            override fun run() {
                if (!isStatusDotPulsing) return
                ObjectAnimator.ofFloat(dot, "alpha", 1f, 0.2f).apply {
                    duration = 800L
                    interpolator = AccelerateDecelerateInterpolator()
                    addListener(object : android.animation.AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: android.animation.Animator) {
                            dot.alpha = 1f
                            if (isStatusDotPulsing) {
                                statusDotHandler.postDelayed(statusDotRunnable!!, 1200L)
                            }
                        }
                    })
                    start()
                }
            }
        }
        statusDotHandler.postDelayed(statusDotRunnable!!, 0L)
    }

    // ─── My Trip Tab Animations ────────────────────────────────────────────────

    /**
     * Starts bouncing circles + pulse rings inside the My Trip tab's red banner.
     * Called once when the tab first appears; guarded by myTripCirclesInitialized.
     */
    private fun initMyTripTabAnimations() {
        if (myTripCirclesInitialized) return
        myTripCirclesInitialized = true

        val tabContainer = binding.myTripTabContainer
        val circleContainer = tabContainer.findViewById<FrameLayout>(R.id.circleContainer) ?: return
        val pulseRing1 = tabContainer.findViewById<View>(R.id.pulseRing1)
        val pulseRing2 = tabContainer.findViewById<View>(R.id.pulseRing2)

        circleContainer.post {
            val containerW = circleContainer.width
            val containerH = circleContainer.height
            if (containerW == 0 || containerH == 0) return@post

            val random = java.util.Random()
            val count = random.nextInt(3) + 3

            for (i in 0 until count) {
                val sizeDp = 28 + random.nextInt(61)
                val sizePx = (sizeDp * resources.displayMetrics.density).toInt()
                val startX = random.nextInt(containerW).toFloat()
                val startY = random.nextInt(containerH).toFloat()
                val minSpeed = 0.12f + random.nextFloat() * 0.15f
                val maxSpeed = minSpeed + 0.15f + random.nextFloat() * 0.2f
                val alpha = 0.25f + random.nextFloat() * 0.3f

                val circle = View(this).apply {
                    layoutParams = FrameLayout.LayoutParams(sizePx, sizePx)
                    background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_circle_light_red)
                    this.alpha = alpha
                }
                circleContainer.addView(circle)
                myTripCircleViews.add(circle)

                startMyTripBounceAnimation(
                    circle, sizePx, containerW, containerH,
                    minSpeed, maxSpeed, startX, startY,
                    (i * 150L) + random.nextInt(500)
                )
            }
        }

        // Pulse rings for My Trip tab
        startMyTripPulseRing(pulseRing1, 0L)
        startMyTripPulseRing(pulseRing2, 1200L)
    }

    private fun startMyTripBounceAnimation(
        view: View,
        viewSize: Int,
        containerWidth: Int,
        containerHeight: Int,
        minSpeed: Float,
        maxSpeed: Float,
        initialX: Float,
        initialY: Float,
        startDelayMs: Long
    ) {
        val random = java.util.Random()
        val maxX = (containerWidth - viewSize).toFloat().coerceAtLeast(0f)
        val maxY = (containerHeight - viewSize).toFloat().coerceAtLeast(0f)

        view.translationX = initialX
        view.translationY = initialY

        var velX = (random.nextFloat() * (maxSpeed - minSpeed) + minSpeed) *
            if (random.nextBoolean()) 1f else -1f
        var velY = (random.nextFloat() * (maxSpeed - minSpeed) + minSpeed) *
            if (random.nextBoolean()) 1f else -1f

        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = Long.MAX_VALUE
            repeatCount = ValueAnimator.INFINITE
            startDelay = startDelayMs
            addUpdateListener {
                if (random.nextFloat() < 0.02f) {
                    velX += (random.nextFloat() - 0.5f) * 0.4f
                    velX = velX.coerceIn(-maxSpeed, maxSpeed)
                }
                if (random.nextFloat() < 0.02f) {
                    velY += (random.nextFloat() - 0.3f) * 0.4f
                    velY = velY.coerceIn(-maxSpeed, maxSpeed)
                }
                var newX = view.translationX + velX
                var newY = view.translationY + velY
                if (newX <= 0) { newX = 0f; velX = kotlin.math.abs(velX).coerceIn(minSpeed, maxSpeed) }
                else if (newX >= maxX) { newX = maxX; velX = -kotlin.math.abs(velX).coerceIn(minSpeed, maxSpeed) }
                if (newY <= 0) { newY = 0f; velY = kotlin.math.abs(velY).coerceIn(minSpeed, maxSpeed) }
                else if (newY >= maxY) { newY = maxY; velY = -kotlin.math.abs(velY).coerceIn(minSpeed, maxSpeed) }
                view.translationX = newX
                view.translationY = newY
            }
            myTripCircleAnimators.add(this)
            start()
        }

        ObjectAnimator.ofFloat(view, "scaleX", 1f, 1.08f).apply {
            duration = 4000L + random.nextInt(1500)
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            startDelay = startDelayMs
            myTripCircleAnimators.add(this)
            start()
        }

        ObjectAnimator.ofFloat(view, "scaleY", 1f, 1.06f).apply {
            duration = 4500L + random.nextInt(1500)
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            startDelay = startDelayMs + 600L
            myTripCircleAnimators.add(this)
            start()
        }
    }

    private fun startMyTripPulseRing(ring: View?, delayMs: Long) {
        ring ?: return
        ring.visibility = View.VISIBLE
        ring.alpha = 0f

        val scaleUp = ObjectAnimator.ofFloat(ring, "scaleX", 0.3f, 1.5f).apply {
            duration = 2000L
            repeatMode = ValueAnimator.RESTART
            repeatCount = ValueAnimator.INFINITE
            startDelay = delayMs
        }
        val scaleUpY = ObjectAnimator.ofFloat(ring, "scaleY", 0.3f, 1.5f).apply {
            duration = 2000L
            repeatMode = ValueAnimator.RESTART
            repeatCount = ValueAnimator.INFINITE
            startDelay = delayMs
        }
        val fadeOut = ObjectAnimator.ofFloat(ring, "alpha", 0.6f, 0f).apply {
            duration = 2000L
            repeatMode = ValueAnimator.RESTART
            repeatCount = ValueAnimator.INFINITE
            startDelay = delayMs
        }

        myTripCircleAnimators.addAll(listOf(scaleUp, scaleUpY, fadeOut))
        scaleUp.start()
        scaleUpY.start()
        fadeOut.start()
    }

    private fun stopCircleAnimations() {
        circleAnimators.forEach { it.cancel() }
        circleAnimators.clear()
        circleViews.forEach { binding.circleContainer.removeView(it) }
        circleViews.clear()
        // Also clean up My Trip tab circles
        myTripCircleAnimators.forEach { it.cancel() }
        myTripCircleAnimators.clear()
        myTripCircleViews.forEach {
            binding.myTripTabContainer.findViewById<FrameLayout>(R.id.circleContainer)?.removeView(it)
        }
        myTripCircleViews.clear()
    }

    private fun stopStatusDot() {
        isStatusDotPulsing = false
        val runnable = statusDotRunnable
        statusDotRunnable = null
        statusDotHandler.removeCallbacksAndMessages(null)
        runnable?.let { binding.ctaStatusDot.alpha = 1f }
            ?: run { binding.ctaStatusDot.alpha = 1f }
    }

    private fun openQueueDetails() {
        if (currentQueueEntries.isEmpty() && currentShipments.isEmpty()) {
            Toast.makeText(this, "Wala ka pa sa pila. Mag-scan ng QR code para makapasok.", Toast.LENGTH_SHORT).show()
            return
        }

        // Shipment assigned → open AssignmentActivity (shows all assignments with live guard status)
        if (hasShipment) {
            startActivity(Intent(this, AssignmentActivity::class.java))
            return
        }

        // Otherwise, open the standard QueueDetails screen
        startActivity(Intent(this, QueueDetailsActivity::class.java))
    }

    override fun onResume() {
        super.onResume()
        listenToQueueStatus()
        updateScanButtonState()
        registerConnectionMonitor()
        startHeartbeatMonitoring()
        updateTrackingBadge(TrackingStateMonitor.getCurrentState())
        // Re-check tutorials — user may have re-enabled them in Profile
        setupTutorial()
    }

    override fun onPause() {
        super.onPause()
        unregisterConnectionMonitor()
        isOfflineDialogShowing = false
    }

    private fun setupUI() {
        val session = SessionManager(this)
        binding.tvDriverName.text = session.getDriverName()

        binding.btnScanQR.setOnClickListener {
            // SCENARIO B: There is an active in_transit trip — go directly to live tracking screen.
            // Check both in-memory list (fast) and SharedPreferences (survives app restart after START TRIP).
            val sessionMgr = SessionManager(this)
            val hasActiveTrip = currentShipments.any { it.tripStatus == "in_transit" }
                || (sessionMgr.getActiveSapDocId() != null && sessionMgr.isTripActive())

            // ═══ RETURN PANEL takes priority over all other routes ═══
            // Checked BEFORE hasActiveTrip because returning_to_base / completed
            // shipments are no longer "in_transit" and would miss the gate.

            // ── Route 0: all shipments completed → ARRIVED AT WAREHOUSE (finalize) ──
            // Driver has already END SHIPMENT/END TRIP on every shipment.
            // Show the return panel to clear the session and release the driver.
            if (currentShipments.isNotEmpty() &&
                currentShipments.all { it.isCompleted }) {
                val docIds = currentShipments.joinToString(",") { it.docId }
                ReturnToBaseBottomSheet.newInstance(docIds)
                    .show(supportFragmentManager, "ReturnToBaseBottomSheet")
                return@setOnClickListener
            }

            // ── Route 1: returning_to_base → ARRIVED AT WAREHOUSE ──
            val returningShipments = currentShipments.filter {
                it.tripStatus == "returning_to_base"
            }
            if (returningShipments.isNotEmpty()) {
                val docIds = returningShipments.joinToString(",") { it.docId }
                ReturnToBaseBottomSheet.newInstance(docIds)
                    .show(supportFragmentManager, "ReturnToBaseBottomSheet")
                return@setOnClickListener
            }

            // ── Route 2 REMOVED ──
            // Previously: all PODs complete → START RETURNING TO BASE
            // This used to fire the floating panel as soon as every shipment had
            // its POD items captured, even before the user pressed END SHIPMENT.
            // Per the agreed flow, the map icon must only enable return-to-base
            // AFTER all shipments have been END SHIPMENT'd (Route 0 above).
            // Mid-delivery (all PODs done but not all END'd), the map icon falls
            // through to TransitActivity, where the in-screen
            // "START RETURNING TO BASE" button handles the transition per spec.

            if (hasActiveTrip) {
                val activeDocId = sessionMgr.getActiveSapDocId()

                // Normal transit — open TransitActivity tracking screen
                activeDocId?.let { docId ->
                    startActivity(Intent(this, TransitActivity::class.java).apply {
                        putExtra("shipment_doc_id", docId)
                    })
                } ?: startActivity(Intent(this, TransitActivity::class.java))
                return@setOnClickListener
            }

            when {
                // SCENARIO A: All confirmed — show MyTripActivity card list with START TRIP buttons
                currentShipments.isNotEmpty() &&
                    currentShipments.all { it.guardVerified && it.driverConfirmed } -> {
                    startActivity(Intent(this, GeneralTrackingActivity::class.java))
                }
                // Shipment assigned → open AssignmentActivity (shows all assignments)
                hasShipment -> {
                    startActivity(Intent(this, AssignmentActivity::class.java))
                }
                // In queue, no shipment yet → show queue details
                isInQueue -> {
                    openQueueDetails()
                }
                else -> {
                    // NOT IN QUEUE — icon shows QR, open QRScanActivity
                    openQRScan()
                }
            }
        }

        binding.cardMyQueue.setOnClickListener {
            // Tapping the home card → show per-shipment detail (banner + card stack).
            // Pass the first shipment's docId so QueueDetailsActivity loads correctly.
            if (currentShipments.isNotEmpty()) {
                val intent = Intent(this, com.sysu.go.activities.QueueDetailsActivity::class.java)
                intent.putExtra("shipment_doc_id", currentShipments.first().docId)
                startActivity(intent)
            } else if (isInQueue) {
                openQueueDetails()
            }
        }

        // Logout removed from main activity layout as requested
        // binding.btnLogout.setOnClickListener { logout() }

        // Initialize My Trip tab as hidden (Home tab is shown at startup)
        binding.myTripTabContainer.visibility = View.GONE

        // Bottom nav — Home icon: shows home content, hides My Trip tab
        binding.btnHome.setOnClickListener {
            updateBottomNavSelectionForTab(isHomeTab = true)
            // Show home content
            binding.cardScanCTA.visibility = View.VISIBLE
            binding.tvScanHint.visibility = View.GONE
            binding.myTripTabContainer.visibility = View.GONE
        }

        // Bottom nav — Profile icon: opens ProfileActivity (driver info, requirements, logout)
        binding.btnProfile.setOnClickListener {
            startActivity(Intent(this, ProfileActivity::class.java))
        }

        // Back button inside My Trip tab — returns to Home tab
        // (kept for backwards compatibility; actual back navigation is now in MyTripActivity)
        binding.myTripTabContainer.findViewById<ImageButton>(R.id.btnBack)?.setOnClickListener {
            updateBottomNavSelectionForTab(isHomeTab = true)
            binding.cardScanCTA.visibility = View.VISIBLE
            binding.tvScanHint.visibility = View.GONE
            binding.myTripTabContainer.visibility = View.GONE
        }

        binding.btnCancelQueue.setOnClickListener { showCancelQueueDialog() }

        // Dismiss / close the CTA notification card
        binding.btnCloseScanCTA.setOnClickListener {
            binding.cardScanCTA.visibility = View.GONE
        }

        // ── Vertical Shipment Cards RecyclerView — one full-width card per shipment.
        // Phase 1: hidden (cardMyQueue handles display).
        // Phase 2 (2+ shipments): visible. LayoutManager set via updateQueueTabs().
        // default canScrollVertically=true — scroll works via constraintHeight_max in XML.
        binding.rvShipmentCards.layoutManager =
            androidx.recyclerview.widget.LinearLayoutManager(this)

        // ── Scroll quality: rubber-band over-scroll ──
        binding.rvShipmentCards.overScrollMode = androidx.recyclerview.widget.RecyclerView.OVER_SCROLL_ALWAYS

        // ── Scroll quality: smooth item animations ──
        binding.rvShipmentCards.itemAnimator = androidx.recyclerview.widget.DefaultItemAnimator().apply {
            addDuration = 200
            removeDuration = 200
            moveDuration = 200
            changeDuration = 200
        }

        // ── Scroll quality: uniform 8dp vertical spacing between cards ──
        binding.rvShipmentCards.addItemDecoration(object : androidx.recyclerview.widget.RecyclerView.ItemDecoration() {
            override fun getItemOffsets(
                outRect: android.graphics.Rect,
                view: View,
                parent: androidx.recyclerview.widget.RecyclerView,
                state: androidx.recyclerview.widget.RecyclerView.State
            ) {
                val position = parent.getChildAdapterPosition(view)
                val spacing = android.util.TypedValue.applyDimension(
                    android.util.TypedValue.COMPLEX_UNIT_DIP, 8f,
                    parent.context.resources.displayMetrics
                ).toInt()
                outRect.top = if (position == 0) 0 else spacing
                outRect.bottom = spacing
            }
        })
    }

    private fun setupBottomNav() {
        binding.ivHome.setColorFilter(ContextCompat.getColor(this, R.color.sysu_red))
        binding.ivProfile.setColorFilter(ContextCompat.getColor(this, R.color.text_muted))
    }

    /** Updates the header badge based on current tracking state.
     * Pill badge (statusBadge) appears only when a TRIP IS ACTIVE.
     * Default badge (readyBadge) shows when no trip. */
    private fun updateTrackingBadge(state: TrackingStateMonitor.State) {
        val isTracking = SessionManager(this).isTrackingActive()

        if (isTracking) {
            // Show pill badge, hide "Ready to Scan"
            binding.statusBadge.visibility = View.VISIBLE
            binding.readyBadge.visibility = View.GONE

            val (pillDrawable, dotDrawable, labelText) = when (state) {
                TrackingStateMonitor.State.ONLINE -> Triple(
                    R.drawable.bg_status_pill_green,
                    R.drawable.bg_status_dot_green,
                    "Online"
                )
                TrackingStateMonitor.State.STOPPED -> Triple(
                    R.drawable.bg_status_pill_gold,
                    R.drawable.bg_status_dot_gold,
                    "Stopped"
                )
                TrackingStateMonitor.State.OFFLINE -> Triple(
                    R.drawable.bg_status_pill_red,
                    R.drawable.bg_status_dot_red,
                    "Offline"
                )
            }

            // Find the pill background LinearLayout inside statusBadge and set its background
            val pillLayout = binding.statusBadge.getChildAt(0) as? android.widget.LinearLayout
            pillLayout?.setBackgroundResource(pillDrawable)
            // Update dot
            binding.statusDot.setBackgroundResource(dotDrawable)
            // Update text
            binding.tvStatusBadgeText.text = labelText
        } else {
            // No active trip — show default "Ready to Scan", hide pill
            binding.statusBadge.visibility = View.GONE
            binding.readyBadge.visibility = View.VISIBLE
        }
    }

    private fun loadDriverInfo() {
        val session = SessionManager(this)
        val uid = session.getDriverId() ?: return

        lifecycleScope.launch {
            try {
                val doc = db.collection("drivers").document(uid).get().await()
                if (doc.exists()) {
                    val storedName = doc.getString("name") ?: session.getDriverName()
                    val storedEmail = session.getDriverEmail() ?: ""
                    val storedPhone = doc.getString("phone") ?: ""
                    val storedCompany = Sanitizer.sanitizeCompanyName(doc.getString("truckingCompany") ?: "")

                    val storedFN = doc.getString("fn") ?: ""
                    val storedMI = doc.getString("mi") ?: ""
                    val storedLN = doc.getString("ln") ?: ""

                    // Rebuild clean display name from parts — Firestore "name" field
                    // may still carry the old double-dot MI from before the sanitizer fix.
                    val cleanName = buildString {
                        append(storedFN)
                        if (storedMI.isNotEmpty()) append(" ${storedMI.replace(".", "")}.")
                        if (storedLN.isNotEmpty()) append(" $storedLN")
                    }.trim().ifEmpty { storedName }

                    session.saveDriverSessionWithSplit(
                        uid = uid,
                        fn = storedFN,
                        mi = storedMI,
                        ln = storedLN,
                        displayName = cleanName,
                        email = storedEmail,
                        phone = storedPhone,
                        company = storedCompany
                    )

                    binding.tvDriverName.text = cleanName
                }
            } catch (e: Exception) {
                // Silently fail
            }
        }
    }

    private fun openQRScan() {
        startActivity(Intent(this, QRScanActivity::class.java))
    }

    private fun listenToQueueStatus() {
        val session = SessionManager(this)
        val driverId = session.getDriverId() ?: return

        queueListener?.remove()
        queueListener = db.collection("queue")
            .whereEqualTo("driverId", driverId)
            .whereIn("status", listOf("assigned", "waiting", "called", "loading", "in_transit"))
            .addSnapshotListener { snapshots, error ->
                if (error != null) return@addSnapshotListener

                if (snapshots != null && !snapshots.isEmpty) {
                    val entry = snapshots.documents[0]
                    val data = entry.data ?: return@addSnapshotListener
                    val truckType = data["truckType"] as? String ?: ""
                    val status = data["status"] as? String ?: ""
                    val joinedAt = data["joinedAt"] as? com.google.firebase.Timestamp

                    isInQueue = true
                    currentQueueDocId = entry.id
                    session.saveCurrentQueueDocId(entry.id)
                    // ── Queue Tabs: add entry immediately, hasShipment updated later in listenToAssignment ──
                    val tab = com.sysu.go.adapter.QueueTab(
                        queueDocId = entry.id,
                        status = status,
                        hasShipment = false      // updated to true in listenToAssignment when sap doc found
                    )
                    currentQueueEntries.clear()
                    currentQueueEntries.add(tab)
                    updateQueueTabs()
                    positionListener?.remove()

                    val queueId = entry.id
                    val sessionDriverId = session.getDriverId() ?: ""
                    if (status == "assigned") {
                        // Driver has been assigned a shipment — check sap_assignments for details
                        listenToAssignment(queueId, truckType, sessionDriverId)
                        updateQueueUI(null, status, truckType, null)
                        updateScanButtonState()
                    } else if (status == "in_transit") {
                        // Driver is in transit — keep listening for updates
                        assignmentListener?.remove()
                        listenToAssignment(queueId, truckType, sessionDriverId)
                        updateQueueUI(null, status, truckType, null)
                        updateScanButtonState()
                    } else if (status == "loading") {
                        // Check guardVerified from sap_assignments and ALWAYS call listenToAssignment
                        // to show the shipment assignment regardless of guard verified state.
                        db.collection("sap_assignments")
                            .whereEqualTo("driverId", queueId)
                            .addSnapshotListener { assignSnap, assignError ->
                                if (assignError != null) {
                                    Log.e(TAG, "guardVerified query error: ${assignError.message}")
                                    return@addSnapshotListener
                                }
                                val guardVerified = assignSnap?.documents?.firstOrNull()
                                    ?.getBoolean("guardVerified") ?: false
                                isGuardVerified = guardVerified
                                // Always call listenToAssignment for status=loading to show shipment + badge
                                listenToAssignment(queueId, truckType, sessionDriverId)
                                updateScanButtonState()
                                // Also update badge/queue card immediately even before shipment loads
                                updateQueueUI(null, status, truckType, null)
                            }
                    } else if (joinedAt != null) {
                        assignmentListener?.remove()
                        positionListener = db.collection("queue")
                            .whereEqualTo("truckType", truckType)
                            .whereIn("status", listOf("waiting", "called"))
                            .whereEqualTo("registrationComplete", true)
                            .whereLessThan("joinedAt", joinedAt)
                            .addSnapshotListener { posSnapshots, posError ->
                                if (posError != null) return@addSnapshotListener
                                val position = (posSnapshots?.size() ?: 0) + 1
                                updateQueueUI(position, status, truckType, null)
                                updateScanButtonState()
                            }
                    } else {
                        assignmentListener?.remove()
                        updateQueueUI(null, status, truckType, null)
                    }
                    updateScanButtonState()
                } else {
                    isInQueue = false
                    hasShipment = false
                    currentQueueDocId = null
                    currentShipments.clear()
                    currentQueueEntries.clear()
                    shipmentCardAdapter?.submitList(emptyList())
                    binding.rvShipmentCards.visibility = View.GONE
                    positionListener?.remove()

                    // Even with no queue doc, check for assigned shipments in sap_assignments
                    listenToAssignmentViaSessionDriverId(driverId, "")

                    binding.tvStatusBadgeText.text = "Ready to Scan"
                    binding.statusBadge.setCardBackgroundColor(android.graphics.Color.parseColor("#33FFFFFF"))
                    binding.tvScanHint.text = "Show QR code at booth to check in"
                    binding.tvScanHint.visibility = View.VISIBLE
                    binding.tvQueueStatus.text = "NOT IN QUEUE"
                    binding.tvQueueStatus.textSize = 24f
                    binding.tvQueueStatusLabel.text = ""
                    binding.tvQueueStatusLabel.visibility = View.GONE
                    binding.tvQueueDetails.text = ""
                    binding.tvQueueDetails.visibility = View.GONE
                    // Restore queue icon to default
                    binding.ivQueueIcon.setImageResource(R.drawable.ticket_1)
                    binding.ivQueueIcon.setColorFilter(
                        ContextCompat.getColor(this, R.color.sysu_red)
                    )
                    // Hide cancel button when out of queue
                    binding.dividerCancelQueue.visibility = View.GONE
                    binding.cancelQueueContainer.visibility = View.GONE
                    updateScanButtonState()
                }
            }
    }

    private fun listenToAssignment(queueId: String, truckType: String, sessionDriverId: String) {
        Log.d(TAG, ">> listenToAssignment() called — queueId=$queueId sessionDriverId=$sessionDriverId")
        assignmentListener?.remove()

        /**
         * Attempts to load ALL sap_assignments for this driver and populate
         * currentQueueEntries with one tab per shipment.
         *
         * Approach 1: query by queueId — matches older docs where driverId == queueDocId
         * Approach 2: query by sessionDriverId — matches newer docs where driverId == Firebase UID
         *
         * Both approaches are listeners so new shipments are picked up automatically.
         * Syncs currentQueueEntries from ALL found sap_assignment docs, then calls updateQueueTabs().
         */
        assignmentListener = db.collection("sap_assignments")
            .whereEqualTo("driverId", queueId)
            .addSnapshotListener { allQueueSnapshots, qErr ->
                if (qErr != null) {
                    Log.e(TAG, ">> Approach1 error: ${qErr.message} — trying sessionDriverId fallback")
                    listenToAssignmentViaSessionDriverId(sessionDriverId, truckType)
                    return@addSnapshotListener
                }

                if (allQueueSnapshots != null && !allQueueSnapshots.isEmpty) {
                    // Approach 1 matched — found sap_assignment(s) using queueId as driverId
                    syncQueueTabsFromSapSnapshots(allQueueSnapshots, sessionDriverId, truckType)
                } else {
                    // Approach 1 empty — fall through to Approach 2
                    Log.d(TAG, ">> Approach1 empty — trying Approach2 (sessionDriverId listener)")
                    listenToAssignmentViaSessionDriverId(sessionDriverId, truckType)
                }
            }
    }

    /**
     * Approach 2: Listen to ALL sap_assignments where driverId == Firebase UID.
     * Catches all new-style docs and handles multiple shipments at once.
     */
    private fun listenToAssignmentViaSessionDriverId(sessionDriverId: String, truckType: String) {
        // Remove old approach-2 listener if any, then listen fresh
        assignmentListener = db.collection("sap_assignments")
            .whereEqualTo("driverId", sessionDriverId)
            .addSnapshotListener { snap, err ->
                if (err != null) {
                    Log.e(TAG, ">> Approach2 error: ${err.message}")
                    return@addSnapshotListener
                }
                Log.d(TAG, ">> Approach2 syncing — ${snap?.size() ?: 0} sap_assignment(s) found for sessionDriverId=$sessionDriverId")
                if (snap != null && !snap.isEmpty) {
                    syncQueueTabsFromSapSnapshots(snap, sessionDriverId, truckType)
                }
            }
    }

    /**
     * Core sync: converts all sap_assignment docs into Assignment objects and updates
     * currentShipments. Each assignment carries its docId so tapping a card opens
     * AssignmentActivity for that specific shipment.
     *
     * - Adds entries for ALL found shipments (handles 1, 2, 3... shipments)
     * - Removes entries that no longer have a matching sap_assignment
     * - Updates cardMyQueue with first shipment's summary
     * - Calls updateQueueTabs() which handles: 1 shipment → cardMyQueue, 2+ → vertical cards
     */
    private fun syncQueueTabsFromSapSnapshots(
        sapSnapshots: com.google.firebase.firestore.QuerySnapshot,
        sessionDriverId: String,
        truckType: String
    ) {
        if (isFinishing) return

        // hasShipment flag reflects whether any shipments exist at all
        hasShipment = sapSnapshots.size() > 0

        // Build replacement list with Assignment objects (includes shipment details)
        val newShipments = mutableListOf<com.sysu.go.model.Assignment>()

        for (doc in sapSnapshots.documents) {
            val docId = doc.id  // document ID IS the shipment number (e.g. "0010128715")
            val tripStatus = doc.getString("tripStatus")
            val guardVerified = doc.getBoolean("guardVerified") ?: false
            val driverConfirmed = doc.getBoolean("driverConfirmed") ?: false

            // All fields are at ROOT level of sap_assignments — no nested "data" map
            val truckPlate   = doc.getString("truckPlate")   ?: ""
            val truckCompany = doc.getString("truckCompany") ?: ""
            val helperName   = doc.getString("helperName")   ?: ""

            // Delivery date: actualDelDate lives in lineItems[0] as OData string "/Date(millis)/"
            val lineItems = doc.get("lineItems") as? List<Map<String, Any?>> ?: emptyList()
            val firstItem = lineItems.firstOrNull()
            val deliveryMillis = parseODataDate(firstItem?.get("actualDelDate") as? String)

            // docId (shipment number) is used as the OBD reference
            val assignment = com.sysu.go.model.Assignment(
                docId = docId,              // e.g. "0010128715" — IS the shipment number
                queueId = doc.getString("driverId") ?: docId,
                driverId = sessionDriverId,
                status = if (tripStatus == "in_transit") "in_transit" else "loading",
                tripStatus = tripStatus,
                plateNumber = truckPlate,
                truckType = truckCompany,
                helper = helperName,
                guardVerified = guardVerified,
                driverConfirmed = driverConfirmed,
                deliveryTimestamp = deliveryMillis,
                obdNumber = docId           // shipment number doubles as OBD reference
            )
            newShipments.add(assignment)
            Log.d(TAG, ">> Synced shipment: docId=$docId, plate=$truckPlate, lineItems=${lineItems.size}, tripStatus=$tripStatus")
        }

        // Replace currentShipments completely — removed shipments vanish immediately
        currentShipments.clear()
        currentShipments.addAll(newShipments)

        // ── Optimistic bridge: CONFIRM SHIPMENT → MainActivity live update ──────────
        // When the user confirms a shipment in AssignmentActivity, the Firestore write
        // (driverConfirmed=true) takes ~900ms. By the time they press back and onResume
        // fires, the cached snapshot from addSnapshotListener still shows false.
        // This flag bridges the gap: AssignmentActivity sets it on success,
        // and we consume it here to optimistically mark the shipment confirmed locally
        // until the authoritative snapshot catches up.
        SessionManager(this).consumePendingConfirmedDocId()?.let { pendingDocId ->
            val pendingIdx = currentShipments.indexOfFirst { it.docId == pendingDocId }
            if (pendingIdx >= 0) {
                val optimistic = currentShipments[pendingIdx].copy(driverConfirmed = true)
                currentShipments[pendingIdx] = optimistic
                Log.d(TAG, "Optimistic: forced driverConfirmed=true for docId=$pendingDocId")
            }
        }
        // ── End optimistic bridge ─────────────────────────────────────────────────────

        // Update the static cardMyQueue with first shipment's summary
        val firstShipmentId = sapSnapshots.documents.firstOrNull()?.id
        updateQueueUI(null, "loading", truckType, firstShipmentId)
        updateScanButtonState()

        // updateQueueTabs() reads currentShipments.size:
        //   < 2 → cardMyQueue visible, rvShipmentCards gone
        //   >= 2 → cardMyQueue gone, rvShipmentCards shows vertical list
        updateQueueTabs()
    }

    /**
     * Parses OData-style date strings like "/Date(1780444800000)/"
     * Returns epoch millis as Long, or 0L if unparseable.
     */
    private fun parseODataDate(raw: String?): Long {
        if (raw == null) return 0L
        return try {
            raw.removePrefix("/Date(").removeSuffix(")/").toLong()
        } catch (e: Exception) {
            0L
        }
    }

    private fun updateQueueUI(position: Int?, queueStatus: String, truckType: String, shipmentId: String?) {
        runOnUiThread {
            binding.tvScanHint.text = ""
            binding.tvScanHint.visibility = View.GONE

            // ── Derive display status from tripStatus (sap_assignments) ──────
            // Check ALL shipments to determine the aggregate status
            val allTripStatuses = currentShipments.mapNotNull { it.tripStatus }
            val firstShipmentId = shipmentId ?: currentShipments.firstOrNull()?.docId

            // Determine the canonical display status
            val displayStatus: String = when {
                allTripStatuses.any { it == "in_transit" } -> "IN TRANSIT"
                allTripStatuses.all { it == "loaded" || it == "fully_loaded" }
                    && allTripStatuses.isNotEmpty() -> "LOADED"
                allTripStatuses.any { it == "loading" || it == "fully_loaded" } -> "LOADING"
                else -> when {
                    hasShipment && firstShipmentId != null -> "ASSIGNED"
                    position != null -> String.format(Locale.US, "No. %d", position)
                    else -> "IN QUEUE"
                }
            }

            binding.tvQueueStatus.text = displayStatus
            binding.tvQueueStatus.textSize = when (displayStatus) {
                "NOT IN QUEUE" -> 24f
                else -> 28f
            }

            // ── Badge (frosted pill at top) ──────────────────────────────────
            binding.tvStatusBadgeText.text = when (displayStatus) {
                "IN TRANSIT" -> "In Transit"
                "LOADED" -> "Loaded"
                "LOADING" -> "Loading"
                "ASSIGNED" -> "Assigned"
                else -> "In Queue"
            }
            binding.statusBadge.setCardBackgroundColor(
                when (displayStatus) {
                    "IN TRANSIT" -> ContextCompat.getColor(this, R.color.sysu_blue)
                    "LOADED" -> ContextCompat.getColor(this, R.color.sysu_green)
                    "LOADING" -> ContextCompat.getColor(this, R.color.sysu_amber)
                    "ASSIGNED" -> ContextCompat.getColor(this, R.color.sysu_gold)
                    else -> ContextCompat.getColor(this, android.R.color.transparent)
                }
            )

            // ── Ticket icon — ticket, tint varies by status ──────────────────
            binding.ivQueueIcon.setImageResource(R.drawable.ticket_1)
            binding.ivQueueIcon.setColorFilter(
                ContextCompat.getColor(this, when (displayStatus) {
                    "IN TRANSIT" -> R.color.sysu_blue
                    "LOADED" -> R.color.sysu_green
                    "LOADING" -> R.color.sysu_amber
                    "ASSIGNED" -> R.color.sysu_blue
                    else -> R.color.sysu_red
                })
            )

            // ── Label ────────────────────────────────────────────────────────
            binding.tvQueueStatusLabel.text = when (displayStatus) {
                "IN TRANSIT" -> "On the road"
                "LOADED" -> "Ready"
                "LOADING" -> "Loading"
                "ASSIGNED" -> "Ready"
                else -> when (queueStatus) {
                    "waiting" -> position?.toString() ?: "Waiting"
                    "called" -> "Called!"
                    else -> ""
                }
            }
            binding.tvQueueStatusLabel.visibility = if (binding.tvQueueStatusLabel.text.isNotEmpty()) View.VISIBLE else View.GONE

            // ── Detail line ──────────────────────────────────────────────────
            binding.tvQueueDetails.text = when (displayStatus) {
                "IN TRANSIT" -> "Shipment #${firstShipmentId ?: "—"} • On the road"
                "LOADED" -> "Shipment #${firstShipmentId ?: "—"} • Verified & ready"
                "LOADING" -> if (firstShipmentId != null) {
                    "Shipment #$firstShipmentId • Being loaded"
                } else {
                    "LOADING... • Please wait"
                }
                "ASSIGNED" -> "Shipment #${firstShipmentId ?: "—"} • $truckType"
                else -> when (queueStatus) {
                    "waiting" -> "Waiting in Queue • $truckType"
                    "called" -> "YOU ARE CALLED! • Proceed to bay"
                    else -> "Status: ${queueStatus.uppercase()} • $truckType"
                }
            }
            binding.tvQueueDetails.visibility = View.VISIBLE

            // Show cancel queue only when driver is still waiting (not yet assigned/loading/loaded/transit)
            val canCancel = isInQueue && !hasShipment &&
                displayStatus !in listOf("ASSIGNED", "LOADING", "LOADED", "IN TRANSIT")
            binding.dividerCancelQueue.visibility = if (canCancel) View.VISIBLE else View.GONE
            binding.cancelQueueContainer.visibility = if (canCancel) View.VISIBLE else View.GONE
        }
    }

    // ─── Cancel Queue ─────────────────────────────────────────────────────────

    /** Shows the dual-confirmation cancel queue dialog with professional styling. */
    private fun showCancelQueueDialog() {
        val inflater = LayoutInflater.from(this)

        // ── Step 1: Soft confirmation ──────────────────────────────────────
        val confirmView = inflater.inflate(R.layout.dialog_cancel_confirm, null)
        val confirmDialog = AlertDialog.Builder(this)
            .setView(confirmView)
            .setCancelable(true)
            .create()

        confirmView.findViewById<View>(R.id.btn_cancel_1).setOnClickListener {
            confirmDialog.dismiss()
        }

        confirmView.findViewById<View>(R.id.btn_confirm_1).setOnClickListener {
            confirmDialog.dismiss()
            // ── Step 2: Final warning ─────────────────────────────────────
            val finalView = inflater.inflate(R.layout.dialog_cancel_final, null)
            val finalDialog = AlertDialog.Builder(this)
                .setView(finalView)
                .setCancelable(true)
                .create()

            finalView.findViewById<View>(R.id.btn_cancel_2).setOnClickListener {
                finalDialog.dismiss()
            }

            finalView.findViewById<View>(R.id.btn_confirm_2).setOnClickListener {
                finalDialog.dismiss()
                performQueueCancellation()
            }

            finalDialog.show()
        }

        confirmDialog.show()
    }

    /** Deletes the driver's queue document (and linked sap_assignment) from Firestore. */
    private fun performQueueCancellation() {
        val queueId = currentQueueDocId
        if (queueId.isNullOrBlank()) {
            Toast.makeText(this, R.string.cancel_queue_error, Toast.LENGTH_SHORT).show()
            return
        }

        // Lock out the cancel button immediately so double-tap can't re-enter
        currentQueueDocId = null
        isInQueue = false
        binding.dividerCancelQueue.visibility = View.GONE
        binding.cancelQueueContainer.visibility = View.GONE

        val session = SessionManager(this)

        // Step 1: Delete the queue document from Firestore
        db.collection("queue").document(queueId)
            .delete()
            .addOnSuccessListener {
                Log.d(TAG, ">>> Queue document $queueId deleted")
                // Step 2: Clean up linked sap_assignment
                db.collection("sap_assignments")
                    .whereEqualTo("driverId", queueId)
                    .get()
                    .addOnSuccessListener { sapSnaps ->
                        for (doc in sapSnaps.documents) {
                            doc.reference.delete()
                                .addOnSuccessListener {
                                    Log.d(TAG, ">>> sap_assignment ${doc.id} deleted")
                                }
                        }
                        // Notify dispatch so driver disappears from their map
                        SocketManager.emitOfflineStatus(
                            FirebaseAuth.getInstance().currentUser?.uid,
                            session.getDriverName() ?: "Driver"
                        )
                    }
                    .addOnFailureListener { e ->
                        Log.e(TAG, ">>> sap_assignments cleanup failed: ${e.message}")
                    }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, ">>> Queue cancellation failed: ${e.message}")
                // Attempt to restore state so driver can try again
                if (!isFinishing) {
                    Toast.makeText(this, R.string.cancel_queue_error, Toast.LENGTH_SHORT).show()
                }
            }
    }

    // ─── Connection Monitor for offline popup ───────────────────────────────

    private fun registerConnectionMonitor() {
        connectionReceiver?.let { return }  // already registered
        connectionReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != ConnectivityManager.CONNECTIVITY_ACTION) return
                val activeNetwork: NetworkInfo? = (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).activeNetworkInfo
                val isConnected = activeNetwork?.isConnected == true
                Log.d(TAG, "[ConnectionMonitor] isConnected=$isConnected")
                if (!isConnected && !isOfflineDialogShowing) {
                    isOfflineDialogShowing = true
                    SocketManager.emitOfflineStatus(
                        FirebaseAuth.getInstance().currentUser?.uid ?: return,
                        SessionManager(this@MainActivity).getDriverName() ?: "Driver"
                    )
                    runOnUiThread { showOfflineDialog() }
                }
            }
        }
        val filter = IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION)
        registerReceiver(connectionReceiver, filter)
    }

    private fun unregisterConnectionMonitor() {
        connectionReceiver?.let {
            try { unregisterReceiver(it) } catch (_: Exception) {}
        }
        connectionReceiver = null
    }

    private fun showOfflineDialog() {
        val dlgView = LayoutInflater.from(this).inflate(R.layout.dialog_sysu_info, null)
        dlgView.findViewById<ImageView>(R.id.ivDialogIcon).setImageResource(R.drawable.ic_dialog_internet)
        dlgView.findViewById<TextView>(R.id.tvDialogTitle).text = "Walang Internet Connection"
        dlgView.findViewById<TextView>(R.id.tvDialogMessage).text = "Ikaw ay OFFLINE na sa tracker. Maaring makita ka ng dispatch bilang OFFLINE (pula) sa kanilang mapa. Babalik ka lang sa GREEN kapag may internet ka na."
        val dialog = AlertDialog.Builder(this).setView(dlgView).setCancelable(false).create()
        dlgView.findViewById<MaterialButton>(R.id.btnDismiss).setOnClickListener {
            isOfflineDialogShowing = false
            dialog.dismiss()
        }
        dialog.show()
    }

    // ─── Start Trip (Batch execution after all shipments confirmed) ────────────

    /** Shows the final confirmation dialog before starting the trip. */
    private fun showStartTripDialog() {
        val dlgView = LayoutInflater.from(this).inflate(R.layout.dialog_sysu_confirmation, null)
        dlgView.findViewById<ImageView>(R.id.ivDialogIcon).setImageResource(R.drawable.ic_dialog_confirm)
        dlgView.findViewById<TextView>(R.id.tvDialogTitle).text = getString(R.string.confirm_start_trip_title)
        dlgView.findViewById<TextView>(R.id.tvDialogMessage).text = getString(R.string.confirm_start_trip_message)
        val dialog = AlertDialog.Builder(this).setView(dlgView).create()
        dlgView.findViewById<MaterialButton>(R.id.btnConfirm).apply {
            text = getString(R.string.btn_start_trip)
            backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this@MainActivity, R.color.sysu_red))
            setOnClickListener { dialog.dismiss(); performBatchStartTrip() }
        }
        dlgView.findViewById<MaterialButton>(R.id.btnCancel).setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    /**
     * Batch-updates all confirmed shipments (guardVerified && driverConfirmed)
     * to tripStatus = "in_transit", then launches MyTripActivity for the first one.
     */
    private fun performBatchStartTrip() {
        val confirmed = currentShipments.filter { it.guardVerified && it.driverConfirmed }
        if (confirmed.isEmpty()) {
            Toast.makeText(this, "Walang kumpirmadong shipment na ma-i-start.", Toast.LENGTH_SHORT).show()
            return
        }

        val session = SessionManager(this)
        val firstDocId = confirmed.first().docId
        // Mark first shipment as active in session ( MyTripActivity reads this )
        session.saveActiveSapDocId(firstDocId)
        session.saveCurrentSapDocId(firstDocId)
        session.setTripActive(true)

        val batch = db.batch()
        val timestamp = com.google.firebase.Timestamp.now()
        val updates = mapOf(
            "tripStatus" to "in_transit",
            "startedAt" to timestamp
        )

        confirmed.forEach { shipment ->
            val ref = db.collection("sap_assignments").document(shipment.docId)
            batch.update(ref, updates)
        }

        batch.commit()
            .addOnSuccessListener {
                Log.d(TAG, "Batch startTrip: ${confirmed.size} shipment(s) → in_transit")
                // Navigate to MyTripActivity for the first (primary) shipment
                val intent = Intent(this, MyTripActivity::class.java)
                startActivity(intent)
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Batch startTrip failed: ${e.message}")
                session.saveActiveSapDocId(null)
                session.clearCurrentSapDocId()
                session.setTripActive(false)
                Toast.makeText(this, "Hindi masimulan ang biyahe. Suriin ang koneksyon.", Toast.LENGTH_SHORT).show()
            }
    }

    // Heartbeat monitoring methods
    private fun startHeartbeatMonitoring() {
        heartbeatHandler.post(heartbeatRunnable)
    }

    private fun stopHeartbeatMonitoring() {
        heartbeatHandler.removeCallbacks(heartbeatRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterConnectionMonitor()
        TrackingStateMonitor.removeListener(trackingStateListener)
        queueListener?.remove()
        positionListener?.remove()
        assignmentListener?.remove()
        stopCircleAnimations()
        bounceArrowAnimator?.cancel()
        bounceArrowAnimator = null
        stopPulseRings()
        stopStatusDot()
        stopHeartbeatMonitoring()
        // Defensive: clear queue doc reference so stale IDs can't be used post-destroy
        currentQueueDocId = null
    }

    // ─── My Trip Tab ─────────────────────────────────────────────────────────

    /**
     * Updates bottom nav icon tints to reflect the active tab.
     * isHomeTab=true  → Home tab selected (red highlight on ivHome, muted on ivProfile)
     * isHomeTab=false → Profile/My Trip tab selected (red highlight on ivProfile, muted on ivHome)
     */
    private fun updateBottomNavSelectionForTab(isHomeTab: Boolean) {
        if (isHomeTab) {
            binding.ivHome.setColorFilter(ContextCompat.getColor(this, R.color.sysu_red))
            binding.ivProfile.setColorFilter(ContextCompat.getColor(this, R.color.text_muted))
        } else {
            binding.ivHome.setColorFilter(ContextCompat.getColor(this, R.color.text_muted))
            binding.ivProfile.setColorFilter(ContextCompat.getColor(this, R.color.sysu_red))
        }
    }

    /**
     * Fetches all sap_assignments for the current driver and populates the My Trip tab
     * RecyclerView using MyTripTabAdapter. Also updates the trip count and handles
     * empty state visibility.
     */
    private fun fetchAndSetupMyTripCards() {
        initMyTripTabAnimations()
        val driverId = SessionManager(this).getDriverId() ?: return

        val tabContainer = binding.myTripTabContainer
        val tabRv = tabContainer.findViewById<RecyclerView>(R.id.rvShipments)
        val emptyState = tabContainer.findViewById<LinearLayout>(R.id.emptyState)
        val aggregateBadgeContainer = tabContainer.findViewById<LinearLayout>(R.id.aggregateBadgeContainer)
        val tvAggregateBadge = tabContainer.findViewById<TextView>(R.id.tvAggregateBadge)
        val badgeVerified = tabContainer.findViewById<LinearLayout>(R.id.badgeVerified)
        val tvBadgeText = tabContainer.findViewById<TextView>(R.id.tvBadgeText)

        db.collection("sap_assignments")
            .whereEqualTo("driverId", driverId)
            .get()
            .addOnSuccessListener { snaps ->
                val assignments = mutableListOf<Assignment>()
                for (doc in snaps.documents) {
                    doc.toObject(Assignment::class.java)?.let { assignments.add(it) }
                }
                // Enrich with driver + queue data, then render
                enrichMyTripAssignments(driverId, assignments) { enriched ->
                val inTransit = enriched.find { it.tripStatus == "in_transit" }
                val hasActiveTrip = inTransit != null

                val shipmentAdapter = com.sysu.go.adapter.MyTripTabAdapter(
                    initialHasActiveTrip = hasActiveTrip,
                    activeShipmentId = inTransit?.docId,
                    onTripActionClicked = { assignment, action ->
                        when (action) {
                            "START_LOADING" -> {
                                lifecycleScope.launch {
                                    try {
                                        db.collection("sap_assignments").document(assignment.docId)
                                            .update(
                                                mapOf(
                                                    "tripStatus" to "loading",
                                                    "loadingStartedAt" to Timestamp.now()
                                                )
                                            )
                                            .await()
                                        fetchAndSetupMyTripCards()
                                    } catch (e: Exception) {
                                        Log.e(TAG, "START_LOADING error: ${e.message}")
                                    }
                                }
                            }
                            "FULLY_LOADED" -> {
                                lifecycleScope.launch {
                                    try {
                                        db.collection("sap_assignments").document(assignment.docId)
                                            .update(
                                                mapOf(
                                                    "tripStatus" to "fully_loaded",
                                                    "fullyLoadedAt" to Timestamp.now()
                                                )
                                            )
                                            .await()
                                        // Notify backend via socket: truck is fully loaded
                                        SocketManager.emitDriverFull(true)
                                        fetchAndSetupMyTripCards()
                                    } catch (e: Exception) {
                                        Log.e(TAG, "FULLY_LOADED error: ${e.message}")
                                    }
                                }
                            }
                            "VERIFY_GUARD" -> {
                                val intent = Intent(this, MyTripActivity::class.java).apply {
                                    putExtra("shipment_doc_id", assignment.docId)
                                }
                                startActivity(intent)
                            }
                            "OPEN_ACTIVE_TRIP" -> {
                                val intent = Intent(this, MyTripActivity::class.java)
                                intent.putExtra("shipment_doc_id", assignment.docId)
                                startActivity(intent)
                            }
                            "START_TRIP" -> {
                                val dlgView = LayoutInflater.from(this).inflate(R.layout.dialog_sysu_confirmation, null)
                                dlgView.findViewById<ImageView>(R.id.ivDialogIcon).setImageResource(R.drawable.ic_dialog_confirm)
                                dlgView.findViewById<TextView>(R.id.tvDialogTitle).text = getString(R.string.confirm_start_trip_title)
                                dlgView.findViewById<TextView>(R.id.tvDialogMessage).text = getString(R.string.confirm_start_trip_message)
                                val dialog = AlertDialog.Builder(this).setView(dlgView).create()
                                dlgView.findViewById<MaterialButton>(R.id.btnConfirm).apply {
                                    text = getString(R.string.btn_start_trip)
                                    backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this@MainActivity, R.color.sysu_red))
                                    setOnClickListener { dialog.dismiss(); startSingleShipment(assignment.docId) }
                                }
                                dlgView.findViewById<MaterialButton>(R.id.btnCancel).setOnClickListener { dialog.dismiss() }
                                dialog.show()
                            }
                        }
                    },
                    onObdTap = { lineItems, docId ->
                        openObdBottomSheet(lineItems, docId)
                    },
                    onShowVerification = { _ -> }
                )

                tabRv.layoutManager = LinearLayoutManager(this)
                tabRv.adapter = shipmentAdapter

                // Update aggregate badge: show green "All Confirmed" if all trips ready
                val allConfirmed = enriched.isNotEmpty() && enriched.all { it.guardVerified && it.driverConfirmed }
                if (allConfirmed) {
                    aggregateBadgeContainer?.visibility = View.VISIBLE
                    aggregateBadgeContainer?.setBackgroundResource(R.drawable.bg_status_pill_green)
                    tvAggregateBadge?.text = getString(R.string.assignment_badge_all_verified)
                    tvAggregateBadge?.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.white))
                    // Red banner badge: "TRIPS READY"
                    badgeVerified?.visibility = View.VISIBLE
                    tvBadgeText?.text = getString(R.string.my_trip_badge_trips_ready)
                } else if (enriched.isNotEmpty()) {
                    val pending = enriched.count { !it.guardVerified || !it.driverConfirmed }
                    aggregateBadgeContainer?.visibility = View.VISIBLE
                    aggregateBadgeContainer?.setBackgroundResource(R.drawable.bg_status_pill_amber)
                    tvAggregateBadge?.text = "$pending pending"
                    tvAggregateBadge?.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_amber))
                    badgeVerified?.visibility = View.GONE
                }

                emptyState.visibility = if (enriched.isEmpty()) View.VISIBLE else View.GONE
                tabRv.visibility = if (enriched.isEmpty()) View.GONE else View.VISIBLE
                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "fetchAndSetupMyTripCards: ${e.message}")
            }
    }

    /**
     * First pass fetches driver data (requirements, truckingCompany).
     * Second pass fetches queue docs (truckType, helper) via assignedShipment.
     * Mirrors MyTripActivity.fetchDriverData + enrichWithQueueData pattern.
     */
    private fun enrichMyTripAssignments(
        driverId: String,
        assignments: List<Assignment>,
        onReady: (List<Assignment>) -> Unit
    ) {
        if (assignments.isEmpty()) { onReady(assignments); return }

        // First pass: get driver requirements + trucking company
        db.collection("drivers").document(driverId).get()
            .addOnSuccessListener { driverDoc ->
                val requirements = driverDoc.get("requirements") as? Map<String, Boolean> ?: emptyMap()
                val truckCompany = Sanitizer.sanitizeCompanyName(driverDoc.getString("truckingCompany") ?: "")

                val withDriver = assignments.map { a ->
                    a.copy(requirements = requirements, truckCompany = truckCompany)
                }

                // Second pass: get queue documents for truckType and helper
                val mutableList = withDriver.toMutableList()
                var pending = withDriver.size

                for (idx in withDriver.indices) {
                    val a = withDriver[idx]
                    db.collection("queue")
                        .whereEqualTo("assignedShipment", a.shipmentNumber)
                        .get()
                        .addOnSuccessListener { queueDocs ->
                            if (!queueDocs.isEmpty) {
                                val q = queueDocs.documents[0]
                                mutableList[idx] = a.copy(
                                    truckType = q.getString("truckType") ?: a.truckType,
                                    helper = q.getString("helper") ?: a.helper
                                )
                            }
                            pending--;
                            if (pending <= 0) onReady(mutableList.toList())
                        }
                        .addOnFailureListener {
                            pending--;
                            if (pending <= 0) onReady(mutableList.toList())
                        }
                }
            }
            .addOnFailureListener { onReady(assignments) }
    }

    /**
     * Opens the OBD items bottom sheet for a given shipment.
     */
    private fun openObdBottomSheet(lineItems: List<Map<String, Any>>, shipmentDocId: String) {
        if (lineItems.isEmpty()) {
            Toast.makeText(this, "Walang available na OBD items", Toast.LENGTH_SHORT).show()
            return
        }
        ObdItemsBottomSheet.newInstance(
            lineItems = lineItems,
            shipmentDocId = shipmentDocId,
            rowLayoutType = RowLayoutType.DETAIL,
            podCaptureEnabled = false,  // read-only in My Queue tab
            onItemClick = null,
            onObdCameraTap = null
        ).show(supportFragmentManager, ObdItemsBottomSheet.TAG)
    }

    /**
     * Marks a single shipment as in_transit in Firestore and launches MyTripActivity.
     * Used when the driver taps START TRIP on a confirmed-but-not-yet-active shipment
     * from the My Trip tab.
     */
    private fun startSingleShipment(docId: String) {
        val ref = db.collection("sap_assignments").document(docId)
        val batch = db.batch()
        batch.update(ref, mapOf(
            "tripStatus" to "in_transit",
            "startedAt" to com.google.firebase.Timestamp.now()
        ))
        batch.commit().addOnSuccessListener {
            Toast.makeText(this, "Nagsimula na ang biyahe!", Toast.LENGTH_SHORT).show()
            // Navigate to MyTripActivity
            val intent = Intent(this, MyTripActivity::class.java)
            intent.putExtra("shipment_doc_id", docId)
            startActivity(intent)
        }.addOnFailureListener { e ->
            Log.e(TAG, "startSingleShipment failed: ${e.message}")
            Toast.makeText(this, "Hindi masimulan ang biyahe.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun logout() {
        val dlgView = LayoutInflater.from(this).inflate(R.layout.dialog_sysu_confirmation, null)
        dlgView.findViewById<ImageView>(R.id.ivDialogIcon).setImageResource(R.drawable.ic_dialog_destructive)
        dlgView.findViewById<TextView>(R.id.tvDialogTitle).text = "Logout"
        dlgView.findViewById<TextView>(R.id.tvDialogMessage).text = "Are you sure you want to sign out?"
        val dialog = AlertDialog.Builder(this).setView(dlgView).create()
        dlgView.findViewById<MaterialButton>(R.id.btnConfirm).apply {
            text = "Sign Out"
            backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this@MainActivity, R.color.sysu_red))
            setOnClickListener { dialog.dismiss()
                // Stop foreground service so app is no longer tracked
                stopService(Intent(this@MainActivity, LocationServices::class.java))
                FirebaseAuth.getInstance().signOut()
                SessionManager(this@MainActivity).clearSession()
                FirebaseAuthManager.signOut(this@MainActivity)
                val intent = Intent(this@MainActivity, LoginActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                startActivity(intent)
                finish()
            }
        }
        dlgView.findViewById<MaterialButton>(R.id.btnCancel).setOnClickListener { dialog.dismiss() }
        dialog.show()
    }
}