package com.sysu.go.activities

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import com.sysu.go.R
import com.sysu.go.databinding.ActivityAssignmentBinding
import com.sysu.go.model.Assignment
import com.sysu.go.utils.SessionManager
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * My Trip / Assignment screen — single shipment view.
 *
 * Shows one shipment's VERIFIED pill, progress strip (Verified → Transit → Done),
 * shipment details (number / plate / truck type / helper), and a fixed
 * START TRIP / END TRIP button at the bottom.
 *
 * Tapping START TRIP calls performStartTrip() → updates Firestore → launches TransitActivity.
 * Tapping END TRIP delegates to TransitActivity which handles trip completion.
 *
 * Phase 1: only 1 shipment in the list → 1 screen, 1 button.
 * Phase 2+ (multiple ships_assignments docs): the first matching shipment fills the screen.
 */
class AssignmentActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAssignmentBinding
    private val db = FirebaseFirestore.getInstance()
    private val sessionManager by lazy { SessionManager(this) }

    // Current shipment being displayed on this screen
    private var currentAssignment: Assignment? = null

    // Firestore listeners for cleanup
    private var currentAssignmentListener: com.google.firebase.firestore.ListenerRegistration? = null

    // Bouncing circles animation state
    private val circleAnimators = mutableListOf<android.animation.Animator>()
    private val circleViews = mutableListOf<View>()

    companion object {
        private const val TAG = "AssignmentActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityAssignmentBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        setupUI()
        startCircleAnimation()
    }

    override fun onResume() {
        super.onResume()
        loadAssignments()
    }

    override fun onDestroy() {
        super.onDestroy()
        circleAnimators.forEach { it.cancel() }
        circleAnimators.clear()
        circleViews.forEach { binding.circleContainer.removeView(it) }
        circleViews.clear()
        currentAssignmentListener?.remove()
    }

    private fun setupUI() {
        // Back button
        binding.btnBack.setOnClickListener { finish() }

        // Bottom START TRIP / END TRIP button
        binding.btnStartTrip.setOnClickListener {
            val assignment = currentAssignment ?: return@setOnClickListener
            onButtonClicked(assignment)
        }
    }

    /**
     * Handles the single fixed button at the bottom of the screen.
     *
     * IN TRANSIT → END TRIP tapped → delegate to TransitActivity
     * VERIFIED + not yet started → START TRIP tapped → confirm dialog → performStartTrip
     * COMPLETED → button is disabled, no-op
     */
    private fun onButtonClicked(assignment: Assignment) {
        when {
            assignment.isInTransit && assignment.isActive(sessionManager.getActiveSapDocId()) -> {
                // END TRIP — delegate to TransitActivity
                launchTransitActivity(assignment.docId)
                return
            }
            assignment.guardVerified && assignment.canStartTrip -> {
                // START TRIP — confirm then perform
                AlertDialog.Builder(this)
                    .setTitle("Start Trip")
                    .setMessage("Start trip for shipment ${assignment.shipmentNumber}?")
                    .setPositiveButton("Start Trip") { _, _ -> performStartTrip(assignment) }
                    .setNegativeButton("Cancel", null)
                    .show()
                return
            }
            else -> {
                // Not ready — button is already hidden/disabled by updateButtonState
                return
            }
        }
    }

    private fun performStartTrip(assignment: Assignment) {
        val docId = assignment.docId

        // 1. Lock this shipment as the active one via SessionManager
        sessionManager.saveActiveSapDocId(docId)
        sessionManager.saveCurrentSapDocId(docId)
        sessionManager.setTripActive(true)

        // 2. Update Firestore: mark trip as in_transit
        lifecycleScope.launch {
            try {
                val updates = mapOf(
                    "tripStatus" to "in_transit",
                    "startedAt" to Timestamp.now()
                )
                db.collection("sap_assignments").document(docId)
                    .update(updates)
                    .addOnSuccessListener {
                        Log.d(TAG, "Trip started: $docId")
                        launchTransitActivity(docId)
                    }
                    .addOnFailureListener { e ->
                        Log.e(TAG, "Failed to start trip: ${e.message}")
                        sessionManager.saveActiveSapDocId(null)
                        sessionManager.clearCurrentSapDocId()
                        sessionManager.setTripActive(false)
                        Toast.makeText(
                            this@AssignmentActivity,
                            "Failed to start trip. Check connection.",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
            } catch (e: Exception) {
                Log.e(TAG, "startTrip error: ${e.message}")
                sessionManager.saveActiveSapDocId(null)
            }
        }
    }

    private fun launchTransitActivity(sapDocId: String) {
        sessionManager.saveCurrentSapDocId(sapDocId)
        val intent = Intent(this, TransitActivity::class.java)
        startActivity(intent)
        // Don't finish() — onResume will refresh the screen with updated trip status
    }

    /**
     * Loads the assignment for the current screen.
     *
     * Priority 1 — specific shipment_doc_id extra (passed from tab click):
     *   Query sap_assignments/{docId} directly => shows the exact shipment for that tab.
     *
     * Priority 2 — no extra (navigated via map icon, cardMyQueue, etc.):
     *   Load via queue + sap_assignment join (existing behavior, shows first/any shipment).
     *
     * Firestore listener is kept so onResume picks up updated tripStatus.
     */
    private fun loadAssignments() {
        currentAssignmentListener?.remove()
        val sessionDriverId = sessionManager.getDriverId() ?: return

        // Priority 1: specific shipment requested (from a dashboard tab)
        val explicitShipmentDocId = intent.getStringExtra("shipment_doc_id")
        if (!explicitShipmentDocId.isNullOrBlank()) {
            Log.d(TAG, "Loading specific shipment: $explicitShipmentDocId")
            loadSpecificShipment(explicitShipmentDocId)
            return
        }

        // Priority 2: generic load — shows whatever shipment exists (map icon, cardMyQueue, etc.)
        currentAssignmentListener = db.collection("queue")
            .whereEqualTo("driverId", sessionDriverId)
            .whereIn("status", listOf("assigned", "waiting", "called", "loading", "in_transit"))
            .addSnapshotListener { queueSnaps, queueError ->
                if (queueError != null || queueSnaps == null || queueSnaps.isEmpty) {
                    loadViaSapFallback(sessionDriverId)
                    return@addSnapshotListener
                }

                val queueDoc = queueSnaps.documents[0]
                val queueData = queueDoc.data ?: return@addSnapshotListener
                val queueId = queueDoc.id
                val driverId = queueData["driverId"] as? String ?: sessionDriverId
                val truckType = queueData["truckType"] as? String ?: ""
                val helper = queueData["helperName"] as? String ?: queueData["helper"] as? String ?: ""
                val plateNumber = queueData["plateNumber"] as? String ?: ""
                val status = queueData["status"] as? String ?: ""

                db.collection("sap_assignments")
                    .whereEqualTo("driverId", queueId)
                    .get()
                    .addOnSuccessListener { sapSnaps ->
                        if (!sapSnaps.isEmpty) {
                            val assignment = buildAssignment(sapSnaps.documents[0], null,
                                queueId, driverId, status, truckType, helper, plateNumber)
                            displayAssignment(assignment)
                        } else {
                            displayAssignment(Assignment(
                                docId = "", queueId = queueId, driverId = driverId,
                                status = status, tripStatus = null,
                                plateNumber = plateNumber, truckType = truckType,
                                helper = helper, guardVerified = false
                            ))
                        }
                    }
                    .addOnFailureListener {
                        displayAssignment(Assignment(
                            docId = "", queueId = queueId, driverId = driverId,
                            status = status, tripStatus = null,
                            plateNumber = plateNumber, truckType = truckType,
                            helper = helper, guardVerified = false
                        ))
                    }
            }
    }

    /**
     * Loads a specific shipment by its sap_assignments document ID.
     * Used when the driver taps a specific tab from the dashboard.
     */
    private fun loadSpecificShipment(docId: String) {
        db.collection("sap_assignments").document(docId)
            .get()
            .addOnSuccessListener { snap ->
                if (snap != null && snap.exists()) {
                    displayAssignment(buildAssignment(snap, null))
                } else {
                    // Doc doesn't exist (possible if it was deleted)
                    Log.w(TAG, "Shipment doc not found: $docId")
                    Toast.makeText(this, "Shipment not found", Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to load shipment $docId: ${e.message}")
                Toast.makeText(this, "Failed to load shipment", Toast.LENGTH_SHORT).show()
                finish()
            }
    }

    private fun loadViaSapFallback(sessionDriverId: String) {
        lifecycleScope.launch {
            try {
                val sapSnaps = db.collection("sap_assignments")
                    .whereEqualTo("driverId", sessionDriverId)
                    .get()
                    .await()
                if (sapSnaps.isEmpty) {
                    // Show a blank placeholder — no shipments at all
                    displayAssignment(Assignment(
                        docId = "",
                        queueId = "",
                        driverId = sessionDriverId,
                        status = "",
                        tripStatus = null,
                        plateNumber = "",
                        truckType = "",
                        helper = "",
                        guardVerified = false
                    ))
                    return@launch
                }
                val assignment = buildAssignment(sapSnaps.documents[0], null)
                displayAssignment(assignment)
            } catch (e: Exception) {
                Log.e(TAG, "sap fallback error: ${e.message}")
            }
        }
    }

    private fun buildAssignment(
        sapDoc: com.google.firebase.firestore.DocumentSnapshot,
        queueDoc: com.google.firebase.firestore.DocumentSnapshot?,
        queueIdOverride: String? = null,
        driverIdOverride: String? = null,
        queueStatusOverride: String? = null,
        truckTypeOverride: String? = null,
        helperOverride: String? = null,
        plateNumberOverride: String? = null
    ): Assignment {
        val queueData = queueDoc?.data
        val queueId = queueIdOverride ?: queueData?.get("driverId") as? String ?: ""
        val driverId = driverIdOverride ?: sessionManager.getDriverId() ?: ""
        val truckType = truckTypeOverride ?: queueData?.get("truckType") as? String
            ?: sapDoc.getString("truckCompany") ?: ""
        val helper = helperOverride ?: queueData?.get("helperName") as? String
            ?: queueData?.get("helper") as? String ?: ""
        val plateNumber = plateNumberOverride ?: queueData?.get("plateNumber") as? String
            ?: sapDoc.getString("truckPlate") ?: ""

        val tripStatus = sapDoc.getString("tripStatus")
        val guardVerified = sapDoc.getBoolean("guardVerified") ?: false
        val delTs = sapDoc.getTimestamp("actualDelDate")

        return Assignment(
            docId = sapDoc.id,
            queueId = queueId,
            driverId = driverId,
            status = queueStatusOverride ?: queueData?.get("status") as? String ?: "",
            tripStatus = tripStatus,
            plateNumber = plateNumber,
            truckType = truckType,
            helper = helper,
            guardVerified = guardVerified,
            deliveryTimestamp = delTs?.toDate()?.time ?: 0L
        )
    }

    /**
     * Populates all binding fields from a single Assignment object.
     * Called whenever a new assignment is loaded from Firestore.
     */
    private fun displayAssignment(assignment: Assignment) {
        if (isFinishing) return
        currentAssignment = assignment

        // ── Details fields ──
        // Shipment Number: truncated docId for display
        binding.tvShipmentNumber.text = if (assignment.docId.isNotEmpty()) {
            assignment.docId.take(8).uppercase()
        } else {
            "—"
        }

        binding.tvPlateNo.text = assignment.plateNumber.ifEmpty { "—" }
        binding.tvTruckType.text = assignment.truckType.ifEmpty { "—" }
        binding.tvHelper.text = assignment.helper.ifEmpty { "—" }

        // ── Progress strip + button state ──
        updateTripState(assignment)
    }

    /**
     * Updates the progress strip, subtitle, and bottom button
     * based on the current trip status of the assignment.
     *
     * tripStatus states:
     *   null / assigned / awaiting_verification → VERIFIED (step 1 active, button = START TRIP)
     *   "in_transit" + this doc is active       → TRANSIT (step 2 active, button = END TRIP)
     *   "completed"                             → DONE (step 3 done, button = disabled)
     */
    private fun updateTripState(assignment: Assignment) {
        val isThisActive = assignment.isActive(sessionManager.getActiveSapDocId())
        val tripStatus = assignment.tripStatus
        val isInTransit = tripStatus == "in_transit"
        val isCompleted = tripStatus == "completed"
        val isNotYetStarted = !isInTransit && !isCompleted

        // ── Progress dots ──
        // Step 1: VERIFIED
        if (isNotYetStarted) {
            // Verified, waiting to start
            binding.dotQueue.setBackgroundResource(R.drawable.bg_progress_step_active)
            binding.numQueue.visibility = View.VISIBLE
            binding.numQueue.text = "1"
            binding.checkQueue.visibility = View.GONE
            binding.lineQueueToLoading.setBackgroundColor(ContextCompat.getColor(this, R.color.divider))
        } else {
            // Already started or completed
            binding.dotQueue.setBackgroundResource(R.drawable.bg_progress_step_done)
            binding.numQueue.visibility = View.GONE
            binding.checkQueue.visibility = View.VISIBLE
            binding.lineQueueToLoading.setBackgroundColor(ContextCompat.getColor(this, R.color.transit_green))
        }

        // Step 2: TRANSIT
        if (isInTransit || isCompleted) {
            binding.dotLoading.setBackgroundResource(R.drawable.bg_progress_step_done)
            binding.checkLoading.visibility = View.VISIBLE
            binding.numLoading.visibility = View.GONE
            if (!isCompleted) {
                binding.lineLoadingToTransit.setBackgroundColor(ContextCompat.getColor(this, R.color.transit_green))
            }
        } else {
            binding.dotLoading.setBackgroundResource(R.drawable.bg_progress_step_pending)
            binding.checkLoading.visibility = View.GONE
            binding.numLoading.visibility = View.VISIBLE
            binding.numLoading.text = "2"
            binding.numLoading.setTextColor(ContextCompat.getColor(this, R.color.text_muted))
        }

        // Step 3: DONE
        if (isCompleted) {
            binding.dotTransit.setBackgroundResource(R.drawable.bg_progress_step_done)
            binding.checkTransit.visibility = View.VISIBLE
            binding.numTransit.visibility = View.GONE
        } else {
            binding.dotTransit.setBackgroundResource(R.drawable.bg_progress_step_pending)
            binding.checkTransit.visibility = View.GONE
            binding.numTransit.visibility = View.VISIBLE
            binding.numTransit.text = "3"
            binding.numTransit.setTextColor(ContextCompat.getColor(this, R.color.text_muted))
        }

        // ── Status label ──
        binding.tvSubStatus.text = when {
            isCompleted -> "Trip completed"
            isInTransit && isThisActive -> "Trip in progress — GPS tracking live"
            assignment.guardVerified -> "Ready to start"
            else -> "Waiting for guard verification"
        }

        // Header subtitle + tracking dot
        binding.tvHeaderSubtitle.text = when {
            isCompleted -> "Completed"
            isInTransit && isThisActive -> "In transit"
            assignment.guardVerified -> "Verified"
            else -> "Pending"
        }

        // Tracking dot color in header
        val (dotDrawable, dotColor) = when {
            isCompleted -> R.drawable.bg_status_dot_green to R.color.transit_green
            isInTransit && isThisActive -> R.drawable.bg_status_dot_green to R.color.transit_green
            assignment.guardVerified -> R.drawable.bg_status_dot_green to R.color.transit_green
            else -> R.drawable.bg_status_dot to R.color.text_muted
        }
        binding.trackingStateDot.setBackgroundResource(dotDrawable)
        binding.trackingStateDot.backgroundTintList = ContextCompat.getColorStateList(this, dotColor)

        // ── Bottom button ──
        updateButtonState(assignment, isThisActive, isInTransit, isCompleted)
    }

    private fun updateButtonState(assignment: Assignment, isThisActive: Boolean, isInTransit: Boolean, isCompleted: Boolean) {
        when {
            isCompleted -> {
                binding.btnStartTrip.text = "TRIP COMPLETED"
                binding.btnStartTrip.isEnabled = false
                binding.btnStartTrip.alpha = 0.5f
                binding.btnStartTrip.backgroundTintList = ContextCompat.getColorStateList(this, R.color.text_muted)
            }
            isInTransit && isThisActive -> {
                binding.btnStartTrip.text = "END TRIP"
                binding.btnStartTrip.isEnabled = true
                binding.btnStartTrip.alpha = 1f
                binding.btnStartTrip.backgroundTintList = ContextCompat.getColorStateList(this, R.color.transit_green)
            }
            assignment.guardVerified -> {
                binding.btnStartTrip.text = "START TRIP"
                binding.btnStartTrip.isEnabled = true
                binding.btnStartTrip.alpha = 1f
                binding.btnStartTrip.backgroundTintList = ContextCompat.getColorStateList(this, R.color.sysu_red)
            }
            else -> {
                binding.btnStartTrip.text = "VERIFICATION PENDING"
                binding.btnStartTrip.isEnabled = false
                binding.btnStartTrip.alpha = 0.5f
                binding.btnStartTrip.backgroundTintList = ContextCompat.getColorStateList(this, R.color.text_muted)
            }
        }
    }

    // ─── Bouncing Circles (same pattern as QueueDetailsActivity / TransitActivity) ───

    private fun startCircleAnimation() {
        binding.circleContainer.post {
            val containerW = binding.circleContainer.width
            val containerH = binding.circleContainer.height
            if (containerW == 0 || containerH == 0) return@post

            val random = java.util.Random()
            // 140dp height ÷ ~2 circles per row means ~3-6 small circles
            val count = random.nextInt(4) + 3

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
                    background = ContextCompat.getDrawable(this@AssignmentActivity, R.drawable.bg_circle_light_red)
                    this.alpha = alpha
                }
                binding.circleContainer.addView(circle)
                circleViews.add(circle)
                startBounceAnimation(circle, sizePx, containerW, containerH, minSpeed, maxSpeed, startX, startY, (i * 150L) + random.nextInt(500))
            }
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

        var velX = (random.nextFloat() * (maxSpeed - minSpeed) + minSpeed) *
            if (random.nextBoolean()) 1f else -1f
        var velY = (random.nextFloat() * (maxSpeed - minSpeed) + minSpeed) *
            if (random.nextBoolean()) 1f else -1f

        android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = Long.MAX_VALUE
            repeatCount = android.animation.ValueAnimator.INFINITE
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
            circleAnimators.add(this)
            start()
        }

        android.animation.ObjectAnimator.ofFloat(view, "scaleX", 1f, 1.08f).apply {
            duration = 4000L + random.nextInt(1500)
            repeatCount = android.animation.ObjectAnimator.INFINITE
            repeatMode = android.animation.ObjectAnimator.REVERSE
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            startDelay = startDelayMs
            circleAnimators.add(this)
            start()
        }

        android.animation.ObjectAnimator.ofFloat(view, "scaleY", 1f, 1.06f).apply {
            duration = 4500L + random.nextInt(1500)
            repeatCount = android.animation.ObjectAnimator.INFINITE
            repeatMode = android.animation.ObjectAnimator.REVERSE
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            startDelay = startDelayMs + 600L
            circleAnimators.add(this)
            start()
        }
    }
}