package com.sysu.go.activities

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import com.sysu.go.R
import com.sysu.go.activities.TransitActivity
import com.sysu.go.adapter.MyTripTabAdapter
import com.sysu.go.databinding.ActivityMyTripBinding
import com.sysu.go.model.Assignment
import com.sysu.go.utils.SessionManager
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * MY TRIP — shows all confirmed shipments (guardVerified && driverConfirmed)
 * as cards with START TRIP buttons. Mirrors AssignmentActivity's structure.
 *
 * Red banner: bouncing circles + pulse rings (same animation as AssignmentActivity).
 * RecyclerView: one card per confirmed shipment using item_my_trip_tab_card.
 * Tapping START TRIP on a card → confirmShipment() + startSingleShipment()
 * → saves activeSapDocId → launches MyTripActivity (OBD delivery execution).
 * Tapping "VIEW OBD ITEMS" on an in_transit card → launches MyTripActivity
 * scoped to that shipment.
 *
 * Bouncing circles and pulse rings are cleaned up in onDestroy.
 */
class MyTripActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMyTripBinding
    private val db = FirebaseFirestore.getInstance()
    private val sessionManager by lazy { SessionManager(this) }

    /** Shared adapter — created once in onCreate */
    private lateinit var shipmentAdapter: MyTripTabAdapter

    /** Firestore snapshot listener removed onDestroy */
    private var assignmentsListener: com.google.firebase.firestore.ListenerRegistration? = null

    /** Bouncing circles — cancelled onDestroy */
    private val circleAnimators = mutableListOf<android.animation.Animator>()
    private val circleViews = mutableListOf<View>()

    /** Prevents double-init of bouncing circles */
    private var circlesInitialized = false

    companion object {
        private const val TAG = "MyTripActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityMyTripBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        // Create adapter once — stored as class field so we can update state without re-creating
        shipmentAdapter = MyTripTabAdapter(hasActiveTrip = false, activeShipmentId = null) { assignment ->
            onTripActionClicked(assignment)
        }

        setupRecyclerView()
        setupUI()
        startCircleAnimation()
        startPulseRings()
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
        assignmentsListener?.remove()
    }

    // ─── UI setup ──────────────────────────────────────────────────────────────

    private fun setupUI() {
        binding.btnBack.setOnClickListener { finish() }
    }

    private fun setupRecyclerView() {
        val rv = binding.rvShipments
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = shipmentAdapter
        rv.overScrollMode = RecyclerView.OVER_SCROLL_ALWAYS
        rv.itemAnimator = androidx.recyclerview.widget.DefaultItemAnimator().apply {
            addDuration = 200
            removeDuration = 200
            moveDuration = 200
            changeDuration = 200
        }
    }

    // ─── Firestore loading ─────────────────────────────────────────────────────

    /**
     * Loads all confirmed shipments for this driver.
     * Two-approach pattern matching AssignmentActivity:
     *   1. Try queue doc ID (sap_assignments.driverId = queue doc ID)
     *   2. Fall back to Firebase UID
     *
     * If an in_transit shipment exists or a shipment_doc_id was passed in the
     * intent, delegates to the in-transit delivery screen automatically.
     */
    private fun loadAssignments() {
        assignmentsListener?.remove()
        val sessionDriverId = sessionManager.getDriverId() ?: return
        val intentDocId = intent.getStringExtra("shipment_doc_id")

        val queueDocId = sessionManager.getCurrentQueueDocId()
        if (!queueDocId.isNullOrBlank()) {
            assignmentsListener = db.collection("sap_assignments")
                .whereEqualTo("driverId", queueDocId)
                .addSnapshotListener { snaps, err ->
                    if (err != null || snaps == null) {
                        loadAssignmentsFallback(sessionDriverId, intentDocId)
                        return@addSnapshotListener
                    }
                    if (isFinishing) return@addSnapshotListener
                    val list = snaps.documents.mapNotNull { buildAssignment(it) }
                    // Enrich with driver doc data (truckType/truckingCompany + requirements + helper)
                    fetchDriverData(sessionDriverId, list) { enriched ->
                        handleAssignmentList(enriched, intentDocId)
                    }
                }
        } else {
            loadAssignmentsFallback(sessionDriverId, intentDocId)
        }
    }

    private fun loadAssignmentsFallback(sessionDriverId: String, intentDocId: String?) {
        assignmentsListener = db.collection("sap_assignments")
            .whereEqualTo("driverId", sessionDriverId)
            .addSnapshotListener { snaps, err ->
                if (err != null || snaps == null) {
                    Log.e(TAG, "loadAssignments fallback error: ${err?.message}")
                    return@addSnapshotListener
                }
                if (isFinishing) return@addSnapshotListener
                val list = snaps.documents.mapNotNull { buildAssignment(it) }
                // Enrich with driver doc data (truckType/truckingCompany + requirements + helper)
                fetchDriverData(sessionDriverId, list) { enriched ->
                    handleAssignmentList(enriched, intentDocId)
                }
            }
    }

    /**
     * Fetches driver metadata from the drivers/{driverId} document:
     *   - requirements: Map<String, Boolean>  (aircon, reflectorizedVest, etc.)
     *   - truckingCompany: String             → Assignment.truckType
     *   - helper: String?                      → Assignment.helper (optional field in driver doc)
     *
     * The driver doc is the source of truth for truckType (truckingCompany)
     * and requirements; sap_assignments only has driverId reference.
     */
    private fun fetchDriverData(
        driverId: String,
        assignments: List<Assignment>,
        onReady: (List<Assignment>) -> Unit
    ) {
        db.collection("drivers").document(driverId)
            .get()
            .addOnSuccessListener { doc ->
                val reqMap = doc.get("requirements") as? Map<String, Boolean> ?: emptyMap()
                val truckingCompany = doc.getString("truckingCompany") ?: ""
                // helper may live in the driver doc or fallback to sap_assignments (already set by buildAssignment)
                val driverHelper = doc.getString("helper") ?: ""

                // Enrich each assignment with driver-sourced fields
                val enriched = assignments.map { assignment ->
                    assignment.copy(
                        requirements = reqMap,
                        truckType = truckingCompany.ifBlank { assignment.truckType },
                        // Helper: driver doc wins if present, else keep what buildAssignment extracted
                        helper = driverHelper.ifBlank { assignment.helper }
                    )
                }
                onReady(enriched)
            }
            .addOnFailureListener {
                // Driver doc not found — proceed with sap_assignments data as-is
                onReady(assignments)
            }
    }

    private fun handleAssignmentList(list: List<Assignment>, intentDocId: String?) {
        // If launched with a specific shipment_doc_id — delegate to that shipment
        if (!intentDocId.isNullOrBlank()) {
            val target = list.find { it.docId == intentDocId }
            if (target != null) {
                loadSpecificShipment(target)
                return
            }
        }

        // Auto-navigate if any shipment is already in_transit
        val inTransit = list.find { it.tripStatus == "in_transit" }
        if (inTransit != null) {
            launchDeliveryActivity(inTransit.docId)
            return
        }

        // Show confirmed-but-not-in-transit shipments as cards
        val confirmed = list.filter {
            it.guardVerified && it.driverConfirmed && it.tripStatus != "in_transit" && it.tripStatus != "completed"
        }

        shipmentAdapter.hasActiveTrip = false
        shipmentAdapter.activeShipmentId = null
        shipmentAdapter.submitList(confirmed.toList())

        updateAggregateBadge(confirmed)
        updateEmptyState(confirmed.isEmpty(), list.isEmpty())
        binding.tvBadgeText.text = getString(R.string.my_trip_badge_trips_ready)
    }

    /**
     * Shows the shipment's card in MyTripActivity.
     * Does NOT auto-navigate — the card's action button (START TRIP or VIEW OBD ITEMS)
     * handles navigation. This prevents double-navigation when confirmAndStartTrip saves
     * tripStatus=in_transit and re-launches MyTripActivity via Intent with shipment_doc_id.
     */
    private fun loadSpecificShipment(shipment: Assignment) {
        val isInTransit = shipment.tripStatus == "in_transit"

        shipmentAdapter.hasActiveTrip = isInTransit
        shipmentAdapter.activeShipmentId = if (isInTransit) shipment.docId else null
        shipmentAdapter.submitList(listOf(shipment))

        // Do NOT auto-navigate here — let the card's action button handle it.
        // Auto-navigation only happens in handleAssignmentList for non-intentDocId cases.
        updateAggregateBadge(listOf(shipment))
        binding.tvBadgeText.text = if (isInTransit) {
            getString(R.string.cta_status_transit)
        } else {
            shipment.shipmentNumber.ifBlank { shipment.docId }
        }
        binding.emptyState.visibility = View.GONE
    }

    private fun updateEmptyState(listEmpty: Boolean, totalEmpty: Boolean) {
        if (totalEmpty) {
            // No assignments at all
            binding.rvShipments.visibility = View.GONE
            binding.emptyState.visibility = View.VISIBLE
            binding.badgeVerified.visibility = View.GONE
            binding.aggregateBadgeContainer.visibility = View.GONE
        } else if (listEmpty) {
            // Assignments exist but none confirmed yet
            binding.rvShipments.visibility = View.GONE
            binding.emptyState.visibility = View.VISIBLE
        } else {
            binding.rvShipments.visibility = View.VISIBLE
            binding.emptyState.visibility = View.GONE
        }
    }

    // ─── Per-card action ───────────────────────────────────────────────────────

    private fun onTripActionClicked(assignment: Assignment) {
        when {
            assignment.tripStatus == "in_transit" -> {
                // Open delivery execution for this shipment
                launchDeliveryActivity(assignment.docId)
            }
            assignment.guardVerified && assignment.driverConfirmed -> {
                // Confirmed but no active trip — show START TRIP dialog
                AlertDialog.Builder(this)
                    .setTitle(R.string.confirm_start_trip_title)
                    .setMessage(R.string.confirm_start_trip_message)
                    .setPositiveButton(R.string.btn_start_trip) { _, _ ->
                        confirmAndStartTrip(assignment)
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
            assignment.guardVerified && !assignment.driverConfirmed -> {
                // Guard verified but driver not yet confirmed → confirm first
                AlertDialog.Builder(this)
                    .setTitle(R.string.confirm_shipment_title)
                    .setMessage(getString(R.string.confirm_shipment_message, assignment.shipmentNumber))
                    .setPositiveButton(R.string.btn_confirm_shipment) { _, _ ->
                        confirmShipment(assignment) {
                            confirmAndStartTrip(assignment)
                        }
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
            else -> {
                // Not yet guard verified — redirect to AssignmentActivity
                Toast.makeText(this, "Please complete guard verification first.", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    private fun confirmShipment(assignment: Assignment, onSuccess: () -> Unit) {
        lifecycleScope.launch {
            try {
                val updates = mapOf("driverConfirmed" to true)
                db.collection("sap_assignments").document(assignment.docId)
                    .update(updates)
                    .await()
                Log.d(TAG, "Shipment confirmed: ${assignment.docId}")
                onSuccess()
            } catch (e: Exception) {
                Log.e(TAG, "confirmShipment error: ${e.message}")
                Toast.makeText(this@MyTripActivity, "Failed to confirm. Try again.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun confirmAndStartTrip(assignment: Assignment) {
        lifecycleScope.launch {
            try {
                // Save as active trip in session FIRST
                sessionManager.saveActiveSapDocId(assignment.docId)
                sessionManager.saveCurrentSapDocId(assignment.docId)
                sessionManager.setTripActive(true)

                val updates = mapOf(
                    "tripStatus" to "in_transit",
                    "startedAt" to Timestamp.now()
                )
                db.collection("sap_assignments").document(assignment.docId)
                    .update(updates)
                    .await()

                Log.d(TAG, "startTrip: ${assignment.docId} → in_transit")
                Toast.makeText(this@MyTripActivity, "Trip started!", Toast.LENGTH_SHORT).show()

                // Navigate to TransitActivity (live map / delivery tracking screen) — NOT MyTripActivity
                val intent = Intent(this@MyTripActivity, TransitActivity::class.java).apply {
                    putExtra("shipment_doc_id", assignment.docId)
                    flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
                startActivity(intent)
                finish()
            } catch (e: Exception) {
                Log.e(TAG, "confirmAndStartTrip error: ${e.message}")
                sessionManager.saveActiveSapDocId(null)
                sessionManager.clearCurrentSapDocId()
                sessionManager.setTripActive(false)
                Toast.makeText(this@MyTripActivity, "Failed to start trip. Check connection.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun launchDeliveryActivity(sapDocId: String) {
        sessionManager.saveCurrentSapDocId(sapDocId)
        // Launch the live map / tracking screen — NOT MyTripActivity (prevents loop)
        val intent = Intent(this, TransitActivity::class.java).apply {
            putExtra("shipment_doc_id", sapDocId)
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        startActivity(intent)
        finish()
    }

    // ─── Assignment builder ────────────────────────────────────────────────────

    private fun buildAssignment(snap: com.google.firebase.firestore.DocumentSnapshot): Assignment? {
        return try {
            val queueId = snap.getString("driverId") ?: ""
            val tripStatus = snap.getString("tripStatus")
            val guardVerified = snap.getBoolean("guardVerified") ?: false
            val driverConfirmed = snap.getBoolean("driverConfirmed") ?: false
            val guardFirstName = snap.getString("guardFirstName") ?: ""
            val guardLastName = snap.getString("guardLastName") ?: ""
            val guardSignature = snap.getString("guardSignature") ?: ""
            val shipmentNumberRaw = snap.getString("shipmentNumber") ?: ""

            val lineItems = snap.get("lineItems") as? List<*> ?: emptyList<Any>()
            val firstItem = lineItems.filterIsInstance<Map<String, Any?>>().firstOrNull()
            val delTsRaw = firstItem?.get("actualDelDate") as? String
            val deliveryTs = try {
                delTsRaw?.removePrefix("/Date(")?.removeSuffix(")/")?.toLong() ?: 0L
            } catch (e: Exception) { 0L }

            val obdCount = lineItems.filterIsInstance<Map<String, Any?>>().size
            val obdNumber = firstItem?.get("obdNumber") as? String ?: ""

            Assignment(
                docId = snap.id,
                queueId = queueId,
                driverId = sessionManager.getDriverId() ?: "",
                status = snap.getString("status") ?: "",
                tripStatus = tripStatus,
                plateNumber = snap.getString("truckPlate") ?: "",
                truckType = snap.getString("truckCompany") ?: snap.getString("truckType") ?: "",
                helper = snap.getString("helperName") ?: snap.getString("helper") ?: "",
                guardVerified = guardVerified,
                driverConfirmed = driverConfirmed,
                guardFirstName = guardFirstName,
                guardLastName = guardLastName,
                guardSignature = guardSignature,
                deliveryTimestamp = deliveryTs,
                obdCount = obdCount,
                requirements = emptyMap(),
                lineItems = lineItems.filterIsInstance<Map<String, Any?>>() as List<Map<String, Any>>,
                obdNumber = obdNumber,
                shipmentNumberRaw = shipmentNumberRaw
            )
        } catch (e: Exception) {
            Log.e(TAG, "buildAssignment error: ${e.message}")
            null
        }
    }

    // ─── Badge updates ──────────────────────────────────────────────────────────

    private fun updateAggregateBadge(list: List<Assignment>) {
        if (list.isEmpty()) {
            binding.aggregateBadgeContainer.visibility = View.GONE
            binding.badgeVerified.visibility = View.GONE
            return
        }
        binding.aggregateBadgeContainer.visibility = View.VISIBLE
        binding.badgeVerified.visibility = View.VISIBLE

        // Count any in_transit shipment
        val inTransitCount = list.count { it.tripStatus == "in_transit" }

        if (inTransitCount > 0) {
            // Active trip running
            binding.aggregateBadgeContainer.setBackgroundResource(R.drawable.bg_status_pill_green)
            binding.aggregateBadgeDot.setBackgroundResource(R.drawable.bg_status_dot_green)
            binding.tvAggregateBadge.text = "$inTransitCount in transit"
            binding.tvAggregateBadge.setTextColor(ContextCompat.getColor(this, R.color.white))

            binding.badgeVerified.setBackgroundResource(R.drawable.bg_status_pill_green)
            binding.badgeDot.setBackgroundResource(R.drawable.bg_status_dot_green)
            binding.tvBadgeText.text = getString(R.string.cta_status_transit)
        } else {
            // Confirmed, ready to start (list already filtered to confirmed-only)
            binding.aggregateBadgeContainer.setBackgroundResource(R.drawable.bg_status_pill_green)
            binding.aggregateBadgeDot.setBackgroundResource(R.drawable.bg_status_dot_green)
            binding.tvAggregateBadge.text = "${list.size} ready"
            binding.tvAggregateBadge.setTextColor(ContextCompat.getColor(this, R.color.white))

            binding.badgeVerified.setBackgroundResource(R.drawable.bg_status_pill_green)
            binding.badgeDot.setBackgroundResource(R.drawable.bg_status_dot_green)
            binding.tvBadgeText.text = getString(R.string.my_trip_badge_trips_ready)
        }
    }

    // ═══ Bouncing Circles + Pulse Rings (AssignmentActivity pattern) ═══════

    private fun startCircleAnimation() {
        if (circlesInitialized) return
        circlesInitialized = true

        binding.circleContainer.post {
            val containerW = binding.circleContainer.width
            val containerH = binding.circleContainer.height
            if (containerW == 0 || containerH == 0) return@post

            val random = java.util.Random()
            val count = random.nextInt(4) + 3  // 3–6 circles

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
                    background = ContextCompat.getDrawable(this@MyTripActivity, R.drawable.bg_circle_light_red)
                    this.alpha = alpha
                }
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

    private fun startPulseRings() {
        startPulseRing(binding.pulseRing1, 0L)
        startPulseRing(binding.pulseRing2, 1200L)
    }

    private fun startPulseRing(ring: View, delayMs: Long) {
        ring.visibility = View.VISIBLE
        ring.alpha = 0f

        val scaleUp = android.animation.ObjectAnimator.ofFloat(ring, "scaleX", 0.3f, 1.5f).apply {
            duration = 2000L
            repeatMode = android.animation.ObjectAnimator.RESTART
            repeatCount = android.animation.ValueAnimator.INFINITE
            startDelay = delayMs
        }
        val scaleUpY = android.animation.ObjectAnimator.ofFloat(ring, "scaleY", 0.3f, 1.5f).apply {
            duration = 2000L
            repeatMode = android.animation.ObjectAnimator.RESTART
            repeatCount = android.animation.ValueAnimator.INFINITE
            startDelay = delayMs
        }
        val fadeOut = android.animation.ObjectAnimator.ofFloat(ring, "alpha", 0.6f, 0f).apply {
            duration = 2000L
            repeatMode = android.animation.ObjectAnimator.RESTART
            repeatCount = android.animation.ValueAnimator.INFINITE
            startDelay = delayMs
        }

        circleAnimators.addAll(listOf(scaleUp, scaleUpY, fadeOut))
        scaleUp.start()
        scaleUpY.start()
        fadeOut.start()
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

    // Stub for compatibility — the original MyTripActivity had these UI helpers
    // but the new design doesn't need them in this screen
    private val isActivityFinished: Boolean
        get() = isFinishing || isDestroyed
}