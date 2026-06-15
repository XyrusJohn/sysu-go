package com.sysu.go.activities

import android.annotation.SuppressLint
import android.Manifest
import android.util.Log
import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
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
import com.sysu.go.R
import com.sysu.go.databinding.ActivityMainBinding
import com.sysu.go.firebase.FirebaseAuthManager
import com.sysu.go.network.SocketManager
import com.sysu.go.services.LocationServices
import com.sysu.go.utils.ConnectivityMonitor
import com.sysu.go.utils.NetworkUtils // Import NetworkUtils
import com.sysu.go.utils.PermissionHelper
import com.sysu.go.utils.SessionManager
import com.sysu.go.utils.TrackingStateMonitor
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.Locale
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.BroadcastReceiver
import android.content.Context
import android.content.res.ColorStateList
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkInfo
import android.os.Handler
import android.os.Looper

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

        android.widget.Toast.makeText(this, "Welcome Driver! Tap the QR button below to scan and join the queue.", android.widget.Toast.LENGTH_LONG).show()

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
        val hasSeenTutorial = prefs.getBoolean(KEY_QR_TUTORIAL_SEEN, false)
        if (!hasSeenTutorial) {
            binding.qrTutorialOverlay.visibility = View.VISIBLE
            binding.qrTutorialOverlay.alpha = 0f
            ObjectAnimator.ofFloat(binding.qrTutorialOverlay, "alpha", 0f, 1f).apply {
                duration = 300L
                start()
            }

            binding.btnGotIt.setOnClickListener {
                prefs.edit().putBoolean(KEY_QR_TUTORIAL_SEEN, true).apply()
                ObjectAnimator.ofFloat(binding.qrTutorialOverlay, "alpha", 1f, 0f).apply {
                    duration = 300L
                    addListener(object : android.animation.AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: android.animation.Animator) {
                            binding.qrTutorialOverlay.visibility = View.GONE
                        }
                    })
                    start()
                }
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
            currentShipments.size < 2 -> {
                // 0 or 1 shipment — hide vertical list, cardMyQueue handles display
                binding.rvShipmentCards.visibility = View.GONE
                binding.cardMyQueue.visibility = View.VISIBLE
                return
            }
            else -> {
                // 2+ shipments — hide cardMyQueue, show vertical non-scrollable shipment cards
                binding.cardMyQueue.visibility = View.GONE
                binding.rvShipmentCards.visibility = View.VISIBLE
            }
        }
        // ── Vertical stacking: one full-width card per shipment, scrollable ──
        //    constraintHeight_max="240dp" in XML caps height at ~3 cards;
        //    LinearLayoutManager (default canScrollVertically=true) handles scroll.
        binding.rvShipmentCards.layoutManager =
            androidx.recyclerview.widget.LinearLayoutManager(this)

        // Re-create adapter — uses ShipmentCardAdapter (vertical layout, item_shipment_card.xml)
        shipmentCardAdapter = com.sysu.go.adapter.ShipmentCardAdapter(
            sessionActiveDocId = SessionManager(this).getActiveSapDocId()
        ) { assignment ->
            // Card tapped → open AssignmentActivity for this specific shipment
            val intent = Intent(this, AssignmentActivity::class.java)
            intent.putExtra("shipment_doc_id", assignment.docId)
            startActivity(intent)
        }
        binding.rvShipmentCards.adapter = shipmentCardAdapter
        shipmentCardAdapter?.submitList(currentShipments.toList())
    }

    private fun updateScanButtonState() {
        // 4-state pill: NOT ON QUEUE → ON QUEUE → ASSIGNED → ON TRANSIT
        when {
            hasShipment && isGuardVerified -> {
                // ON TRANSIT — driver tapped Start Trip, GPS tracking live
                binding.btnScanQR.setImageResource(R.drawable.map)
                binding.tvStatusPill?.text = getString(R.string.cta_status_transit)
                binding.tvStatusPill?.setTextColor(ContextCompat.getColor(this, R.color.transit_green))
                binding.tvCTAHeader?.text = getString(R.string.cta_header_transit)
                binding.tvCTABody?.text = getString(R.string.cta_body_transit)
                binding.statusPill?.setBackgroundResource(R.drawable.bg_status_pill_queued)
                stopStatusDot()
                binding.ctaStatusDot?.setBackgroundResource(R.drawable.bg_status_dot_green)
            }
            hasShipment -> {
                // ASSIGNED — shipment assigned to driver, waiting for guard or Start Trip tap
                binding.btnScanQR.setImageResource(R.drawable.ticket_1)
                binding.tvStatusPill?.text = getString(R.string.cta_status_assigned)
                binding.tvStatusPill?.setTextColor(ContextCompat.getColor(this, R.color.sysu_blue))
                binding.tvCTAHeader?.text = getString(R.string.cta_header_assigned)
                binding.tvCTABody?.text = getString(R.string.cta_body_assigned)
                binding.statusPill?.setBackgroundResource(R.drawable.bg_status_pill_queued)
                startStatusDotPulse()
                binding.ctaStatusDot?.setBackgroundResource(R.drawable.bg_status_dot_gold)
            }
            isInQueue && isGuardVerified -> {
                // Guard verified but no shipment yet — can transition when shipment assigned
                binding.btnScanQR.setImageResource(R.drawable.ticket_1)
                binding.tvStatusPill?.text = getString(R.string.cta_status_transit)
                binding.tvStatusPill?.setTextColor(ContextCompat.getColor(this, R.color.transit_green))
                binding.tvCTAHeader?.text = getString(R.string.cta_header_transit)
                binding.tvCTABody?.text = getString(R.string.cta_body_transit)
                binding.statusPill?.setBackgroundResource(R.drawable.bg_status_pill_queued)
                stopStatusDot()
                binding.ctaStatusDot?.setBackgroundResource(R.drawable.bg_status_dot_green)
            }
            isInQueue -> {
                // ON QUEUE — waiting in queue to be called by booth
                binding.btnScanQR.setImageResource(R.drawable.ticket_1)
                binding.tvStatusPill?.text = getString(R.string.cta_status_queue)
                binding.tvStatusPill?.setTextColor(ContextCompat.getColor(this, R.color.sysu_gold))
                binding.tvCTAHeader?.text = getString(R.string.cta_header_queue)
                binding.tvCTABody?.text = getString(R.string.cta_body_queue)
                binding.statusPill?.setBackgroundResource(R.drawable.bg_status_pill_queued)
                stopStatusDot()
                binding.ctaStatusDot?.setBackgroundResource(R.drawable.bg_status_dot_gold)
            }
            else -> {
                // NOT ON QUEUE — default scan QR to join queue
                binding.btnScanQR.setImageResource(R.drawable.qr_icon)
                binding.tvStatusPill?.text = getString(R.string.cta_status_scan)
                binding.tvStatusPill?.setTextColor(ContextCompat.getColor(this, R.color.sysu_red))
                binding.tvCTAHeader?.text = getString(R.string.cta_header_scan)
                binding.tvCTABody?.text = getString(R.string.cta_body_scan)
                binding.statusPill?.setBackgroundResource(R.drawable.bg_status_pill)
                startStatusDotPulse()
                binding.ctaStatusDot?.setBackgroundResource(R.drawable.bg_status_dot)
            }
        }
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

    private fun stopCircleAnimations() {
        circleAnimators.forEach { it.cancel() }
        circleAnimators.clear()
        circleViews.forEach { binding.circleContainer.removeView(it) }
        circleViews.clear()
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
            Toast.makeText(this, "You are not in the queue yet. Scan a QR code to join.", Toast.LENGTH_SHORT).show()
            return
        }

        // If guard has verified and shipment is assigned, open Assignment screen (My Trip)
        // which shows all assignments + Start/End Trip buttons per shipment
        if (hasShipment && isGuardVerified) {
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
            when {
                // Guard verified + shipment assigned → open My Trip screen (AssignmentActivity)
                // which shows shipment details + proper Start/End Trip button
                hasShipment && isGuardVerified -> {
                    startActivity(Intent(this, AssignmentActivity::class.java))
                }
                // ON QUEUE or ASSIGNED — icon shows ticket, open QueueDetailsActivity
                // (handles: hasShipment but not verified yet, or isInQueue without shipment yet)
                isInQueue || hasShipment -> {
                    openQueueDetails()
                }
                else -> {
                    // NOT IN QUEUE — icon shows QR, open QRScanActivity
                    openQRScan()
                }
            }
        }

        binding.cardMyQueue.setOnClickListener {
            openQueueDetails()
        }

        // Logout removed from main activity layout as requested
        // binding.btnLogout.setOnClickListener { logout() }

        binding.btnProfile.setOnClickListener {
            startActivity(Intent(this, ProfileActivity::class.java))
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
                    val storedCompany = doc.getString("truckingCompany") ?: ""

                    val storedFN = doc.getString("fn") ?: ""
                    val storedMI = doc.getString("mi") ?: ""
                    val storedLN = doc.getString("ln") ?: ""

                    session.saveDriverSessionWithSplit(
                        uid = uid,
                        fn = storedFN,
                        mi = storedMI,
                        ln = storedLN,
                        displayName = storedName,
                        email = storedEmail,
                        phone = storedPhone,
                        company = storedCompany
                    )

                    binding.tvDriverName.text = storedName
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
                        // Driver tapped Start Trip — GPS tracking live. Show ON TRANSIT.
                        // Don't check sap_assignments — driver is already in transit.
                        assignmentListener?.remove()
                        if (isGuardVerified) {
                            listenToAssignment(queueId, truckType, sessionDriverId)
                        }
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
                    binding.tvQueueStatus.text = "Not In Queue"
                    binding.tvQueueStatus.textSize = 24f
                    binding.tvQueueStatusLabel.text = ""
                    binding.tvQueueStatusLabel.visibility = View.GONE
                    binding.tvQueueDetails.text = ""
                    binding.tvQueueDetails.visibility = View.GONE
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
            val docId = doc.id
            val data = doc.get("data") as? Map<String, Any?>
            val tripStatus = doc.getString("tripStatus")
            val guardVerified = doc.getBoolean("guardVerified") ?: false
            // truckPlate / truckCompany live at root level of sap_assignments
            val truckPlate = doc.getString("truckPlate") ?: data?.get("truckPlate") as? String ?: ""
            val truckCompany = doc.getString("truckCompany") ?: data?.get("truckCompany") as? String ?: ""
            val helperName = doc.getString("helperName") ?: ""
            val delTs = doc.getTimestamp("actualDelDate")
            // OBD number lives inside data nested map
            val obdNumber = data?.get("obdNumber") as? String ?: ""

            val assignment = com.sysu.go.model.Assignment(
                docId = docId,
                queueId = docId,   // for Phase 2, queueId == docId since tabs come from sap_assignments
                driverId = sessionDriverId,
                status = if (tripStatus == "in_transit") "in_transit" else "loading",
                tripStatus = tripStatus,
                plateNumber = truckPlate,
                truckType = truckCompany,
                helper = helperName,
                guardVerified = guardVerified,
                deliveryTimestamp = delTs?.toDate()?.time ?: 0L,
                obdNumber = obdNumber
            )
            newShipments.add(assignment)
            Log.d(TAG, ">> Synced shipment: docId=$docId, obd=$obdNumber, plate=$truckPlate, tripStatus=$tripStatus")
        }

        // Replace currentShipments completely — removed shipments vanish immediately
        currentShipments.clear()
        currentShipments.addAll(newShipments)

        // Update the static cardMyQueue with first shipment's summary
        val firstShipmentId = sapSnapshots.documents.firstOrNull()?.id
        updateQueueUI(null, "loading", truckType, firstShipmentId)
        updateScanButtonState()

        // updateQueueTabs() reads currentShipments.size:
        //   < 2 → cardMyQueue visible, rvShipmentCards gone
        //   >= 2 → cardMyQueue gone, rvShipmentCards shows vertical list
        updateQueueTabs()
    }

    private fun updateQueueUI(position: Int?, status: String, truckType: String, shipmentId: String?) {
        runOnUiThread {
            binding.tvScanHint.text = ""
            binding.tvScanHint.visibility = View.GONE

            val isAssigned = status == "loading" && shipmentId != null
            binding.tvStatusBadgeText.text = if (isAssigned) "Assigned" else "In Queue"
            binding.statusBadge.setCardBackgroundColor(
                if (isAssigned)
                    ContextCompat.getColor(this, R.color.sysu_gold)
                else android.graphics.Color.parseColor("#33FFFFFF")
            )

            // When assigned (shipment received), show LOADING instead of position number
            binding.tvQueueStatus.text = when {
                isAssigned -> "ASSIGNED"
                position != null -> String.format(Locale.US, "No. %d", position)
                status == "loading" -> "LOADING"
                else -> "IN QUEUE"
            }

            binding.tvQueueStatus.textSize = when {
                binding.tvQueueStatus.text == "LOADING" -> 28f
                binding.tvQueueStatus.text == "ASSIGNED" -> 28f
                binding.tvQueueStatus.text == "IN QUEUE" -> 28f
                else -> 42f
            }

            binding.tvQueueStatusLabel.text = when (status) {
                "waiting" -> position?.toString() ?: "Waiting"
                "called" -> "Called!"
                "loading" -> if (shipmentId != null) "Ready" else "Loading"
                else -> ""
            }
            binding.tvQueueStatusLabel.visibility = if (binding.tvQueueStatusLabel.text.isNotEmpty()) View.VISIBLE else View.GONE

            binding.tvQueueDetails.text = when (status) {
                "loading" -> {
                    if (shipmentId != null) {
                        "Shipment #$shipmentId • Being loaded"
                    } else {
                        "LOADING... • Please wait"
                    }
                }
                "waiting" -> "Waiting in Queue • $truckType"
                "called" -> "YOU ARE CALLED! • Proceed to bay"
                else -> "Status: ${status.uppercase()} • $truckType"
            }
            binding.tvQueueDetails.visibility = View.VISIBLE

            // Show cancel queue button only for early-stage statuses (not yet assigned/loading)
            val canCancel = isInQueue && !isAssigned && !hasShipment
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
        AlertDialog.Builder(this)
            .setTitle("Walang Internet Connection")
            .setMessage("Ikaw ay OFFLINE na sa tracker. Maaring makita ka ng dispatch bilang OFFLINE (pula) sa kanilang mapa. Babalik ka lang sa GREEN kapag may internet ka na.")
            .setCancelable(false)
            .setPositiveButton("OK") { dialog, _ ->
                isOfflineDialogShowing = false
                dialog.dismiss()
            }
            .show()
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

    private fun logout() {
        AlertDialog.Builder(this)
            .setTitle("Logout")
            .setMessage("Are you sure you want to sign out?")
            .setPositiveButton("Sign Out") { _, _ ->
                // Stop foreground service so app is no longer tracked
                stopService(Intent(this, LocationServices::class.java))
                FirebaseAuth.getInstance().signOut()
                SessionManager(this).clearSession()
                FirebaseAuthManager.signOut(this)
                val intent = Intent(this, LoginActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                startActivity(intent)
                finish()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}