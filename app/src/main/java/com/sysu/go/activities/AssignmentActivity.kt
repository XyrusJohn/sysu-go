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
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import com.sysu.go.R
import com.sysu.go.adapter.AssignmentAdapter
import com.sysu.go.databinding.ActivityAssignmentBinding
import com.sysu.go.model.Assignment
import com.sysu.go.model.SecurityGuard
import com.sysu.go.repository.SecurityGuardRepository
import com.sysu.go.utils.SessionManager
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * MY ASSIGNMENTS — shows all of the driver's shipments in a single scrollable list.
 *
 * Top bar: "MY ASSIGNMENTS" + aggregate verification badge (green "All Verified" /
 * amber "X Pending").
 * Red banner: bouncing circles + pulse rings (same animation as MainActivity).
 * RecyclerView: one AssignmentCard per shipment, each showing all detail fields.
 * Each card's btnTripAction opens a guard verification flow or launches TransitActivity.
 *
 * Phase 2: All shipment loading via Firestore addSnapshotListener so badges
 * and card states update live.
 */
class AssignmentActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAssignmentBinding
    private val db = FirebaseFirestore.getInstance()
    private val sessionManager by lazy { SessionManager(this) }

    // Current list of assignments shown in rvAssignments
    private var assignments: List<Assignment> = emptyList()

    // Requirements snapshot loaded once from drivers/{driverId}/requirements
    private var requirementsSnapshot: Map<String, Boolean> = emptyMap()

    // Firestore listener — kept so we remove it in onDestroy
    private var assignmentsListener: com.google.firebase.firestore.ListenerRegistration? = null

    // Bouncing circles
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

        setupRecyclerView()
        setupUI()
        startCircleAnimation()
        startPulseRings()
    }

    override fun onResume() {
        super.onResume()
        loadRequirements()
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

    private fun setupRecyclerView() {
        val adapter = AssignmentAdapter(
            onTripActionClick = { assignment -> onAssignmentAction(assignment) },
            onGuardPillTap = { assignment -> openGuardVerification(assignment) },
            onObdTap = { assignment -> openObdBottomSheet(assignment) },
            onVerificationAction = { assignment, guardVerified ->
                if (guardVerified) showGuardVerificationSheet(assignment)
                else openGuardVerification(assignment)
            }
        )

        binding.rvAssignments.apply {
            layoutManager = LinearLayoutManager(this@AssignmentActivity)
            this.adapter = adapter
            overScrollMode = androidx.recyclerview.widget.RecyclerView.OVER_SCROLL_ALWAYS
            itemAnimator = androidx.recyclerview.widget.DefaultItemAnimator().apply {
                addDuration = 200
                removeDuration = 200
                moveDuration = 200
                changeDuration = 200
            }
        }
    }

    private fun setupUI() {
        binding.btnBack.setOnClickListener { finish() }
    }

    /**
     * Handles the action button on an assignment card.
     * Delegates to same AlertDialog / performStartTrip flow from the old screen.
     */
    private fun onAssignmentAction(assignment: Assignment) {
        when {
            assignment.isLocked(sessionManager.getActiveSapDocId()) -> {
                Toast.makeText(this, "Another trip is in progress", Toast.LENGTH_SHORT).show()
            }
            assignment.isInTransit -> {
                launchTransitActivity(assignment.docId)
            }
            assignment.isCompleted -> {
                Toast.makeText(this, "This trip has already been completed", Toast.LENGTH_SHORT).show()
            }
            assignment.canConfirmTrip -> {
                // Guard verified, driver NOT yet confirmed — show confirm dialog
                AlertDialog.Builder(this)
                    .setTitle(R.string.confirm_shipment_title)
                    .setMessage(getString(R.string.confirm_shipment_message, assignment.shipmentNumber))
                    .setPositiveButton(R.string.btn_confirm_shipment) { _, _ -> confirmShipment(assignment) }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
            assignment.guardVerified && assignment.canStartTrip -> {
                // Guard verified AND driver confirmed — direct to TransitActivity
                launchTransitActivity(assignment.docId)
            }
            else -> {
                openGuardVerification(assignment)
            }
        }
    }

    private fun openGuardVerification(assignment: Assignment) {
        lifecycleScope.launch {
            val repo = SecurityGuardRepository(this@AssignmentActivity)
            val guards = repo.refreshGuards().ifEmpty { repo.getCachedGuards() }
            if (guards.isEmpty()) {
                Toast.makeText(this@AssignmentActivity, "No guards available", Toast.LENGTH_SHORT).show()
                return@launch
            }

            // Step 1: Guard selection
            GuardSelectionBottomSheet.newInstance(guards) { guard ->
                // Step 2: Open signature pad
                SignaturePadBottomSheet().apply {
                    onSignatureConfirmed = { signatureBase64 ->
                        submitGuardVerification(assignment, guard, signatureBase64)
                    }
                }.show(supportFragmentManager, SignaturePadBottomSheet.TAG)
            }.show(supportFragmentManager, GuardSelectionBottomSheet.TAG)
        }
    }

    private fun submitGuardVerification(
        assignment: Assignment,
        guard: SecurityGuard,
        signatureBase64: String
    ) {
        // Step 3: Show verification result sheet with Submit button
        val verifiedAt = System.currentTimeMillis()
        GuardVerificationBottomSheet.newInstance(
            firstName = guard.firstName,
            lastName = guard.lastName,
            mi = guard.middleInitial,
            signature = signatureBase64,
            verifiedAt = verifiedAt
        ).apply {
            onSubmitConfirmed = {
                saveGuardVerificationToFirestore(assignment, guard, signatureBase64, verifiedAt)
            }
        }.show(supportFragmentManager, GuardVerificationBottomSheet.TAG)
    }

    private fun saveGuardVerificationToFirestore(
        assignment: Assignment,
        guard: SecurityGuard,
        signatureBase64: String,
        verifiedAtMillis: Long
    ) {
        lifecycleScope.launch {
            try {
                val updates = mapOf(
                    "guardVerified" to true,
                    "guardFirstName" to guard.firstName,
                    "guardLastName" to guard.lastName,
                    "guardMI" to guard.middleInitial,
                    "guardSignatureBase64" to signatureBase64,
                    "guardVerifiedAt" to Timestamp.now()
                )
                db.collection("sap_assignments").document(assignment.docId)
                    .update(updates)
                    .addOnSuccessListener {
                        Log.d(TAG, "Guard verification saved for ${assignment.docId}")
                        Toast.makeText(
                            this@AssignmentActivity,
                            "Guard verification complete!",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    .addOnFailureListener { e ->
                        Log.e(TAG, "Guard verification save failed: ${e.message}")
                        Toast.makeText(
                            this@AssignmentActivity,
                            "Verification failed. Try again.",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
            } catch (e: Exception) {
                Log.e(TAG, "saveGuardVerificationToFirestore error: ${e.message}")
            }
        }
    }

    /**
     * Re-opens GuardVerificationBottomSheet for an already-verified assignment.
     * Shows guard name + signature + timestamp from existing Firestore data.
     */
    private fun showGuardVerificationSheet(assignment: Assignment) {
        lifecycleScope.launch {
            try {
                val docSnap = db.collection("sap_assignments")
                    .document(assignment.docId)
                    .get()
                    .await()

                if (!docSnap.exists()) {
                    Toast.makeText(this@AssignmentActivity, "Verification data not found", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                val firstName = docSnap.getString("guardFirstName") ?: ""
                val lastName  = docSnap.getString("guardLastName") ?: ""
                val mi        = docSnap.getString("guardMI") ?: ""
                val sigBase64 = docSnap.getString("guardSignatureBase64") ?: ""

                val ts = docSnap.getTimestamp("guardVerifiedAt")
                val verifiedAt = ts?.toDate()?.time ?: 0L

                GuardVerificationBottomSheet.newInstance(
                    firstName = firstName,
                    lastName  = lastName,
                    mi        = mi,
                    signature = sigBase64,
                    verifiedAt = verifiedAt
                ).apply {
                    onSubmitConfirmed = { }  // read-only after submission
                }.show(supportFragmentManager, GuardVerificationBottomSheet.TAG)
            } catch (e: Exception) {
                Log.e(TAG, "showGuardVerificationSheet error: ${e.message}")
                Toast.makeText(this@AssignmentActivity, "Failed to load verification details", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun performStartTrip(assignment: Assignment) {
        val docId = assignment.docId
        sessionManager.saveActiveSapDocId(docId)
        sessionManager.saveCurrentSapDocId(docId)
        sessionManager.setTripActive(true)

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

    /**
     * Sets driverConfirmed = true for this shipment.
     * Does NOT start the trip or launch TransitActivity — that happens
     * when MainActivity CTA is tapped after ALL shipments are confirmed.
     */
    private fun confirmShipment(assignment: Assignment) {
        lifecycleScope.launch {
            try {
                val updates = mapOf("driverConfirmed" to true)
                db.collection("sap_assignments").document(assignment.docId)
                    .update(updates)
                    .addOnSuccessListener {
                        Log.d(TAG, "Shipment confirmed: ${assignment.docId}")
                        Toast.makeText(
                            this@AssignmentActivity,
                            R.string.confirm_shipment_toast,
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    .addOnFailureListener { e ->
                        Log.e(TAG, "confirmShipment failed: ${e.message}")
                        Toast.makeText(
                            this@AssignmentActivity,
                            "Failed to confirm. Try again.",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
            } catch (e: Exception) {
                Log.e(TAG, "confirmShipment error: ${e.message}")
            }
        }
    }

    private fun launchTransitActivity(sapDocId: String) {
        sessionManager.saveCurrentSapDocId(sapDocId)
        val intent = Intent(this, TransitActivity::class.java)
        startActivity(intent)
    }

    /**
     * Loads ALL sap_assignments documents for this driver.
     * TWO-APPROACH pattern:
     *   1. Try queue doc ID (stored when driver joined queue) — catches docs that store queue doc ID as driverId
     *   2. Fallback to Firebase UID — catches docs that store Firebase UID as driverId
     * Uses addSnapshotListener so badge and card states update live.
     */
    private fun loadAssignments() {
        assignmentsListener?.remove()
        val sessionDriverId = sessionManager.getDriverId() ?: return

        // ── APPROACH 1: Primary — try queue doc ID first ──
        val queueDocId = sessionManager.getCurrentQueueDocId()
        if (!queueDocId.isNullOrBlank()) {
            assignmentsListener = db.collection("sap_assignments")
                .whereEqualTo("driverId", queueDocId)
                .addSnapshotListener { snaps, err ->
                    if (err != null || snaps == null) {
                        Log.e(TAG, "loadAssignments (queue approach) error: ${err?.message}")
                        // Fall through to approach 2
                        loadAssignmentsFallback(sessionDriverId)
                        return@addSnapshotListener
                    }
                    if (isFinishing) return@addSnapshotListener

                    val list = snaps.documents.mapNotNull { snap -> buildAssignment(snap) }
                    assignments = list
                    submitToAdapter(list)
                    updateAggregateBadge(list)
                }
        } else {
            // No queue doc ID stored — skip to Firebase UID directly
            loadAssignmentsFallback(sessionDriverId)
        }
    }

    /**
     * APPROACH 2: Fallback — query by Firebase UID.
     * Called when approach 1 returned no results.
     */
    private fun loadAssignmentsFallback(sessionDriverId: String) {
        assignmentsListener = db.collection("sap_assignments")
            .whereEqualTo("driverId", sessionDriverId)
            .addSnapshotListener { snaps, err ->
                if (err != null || snaps == null) {
                    Log.e(TAG, "loadAssignments (uid fallback) error: ${err?.message}")
                    return@addSnapshotListener
                }
                if (isFinishing) return@addSnapshotListener

                val list = snaps.documents.mapNotNull { snap ->
                    buildAssignment(snap)
                }
                assignments = list
                submitToAdapter(list)
                updateAggregateBadge(list)
            }
    }

    /**
     * Loads driver-level requirements once per resume.
     * Stored as a Map field DIRECTLY inside drivers/{driverId} (NOT a subcollection).
     * Stamped into every assignment built while this Activity lives.
     */
    private fun loadRequirements() {
        val driverId = sessionManager.getDriverId() ?: return
        db.collection("drivers").document(driverId).get()
            .addOnSuccessListener { snap ->
                if (isFinishing || !snap.exists()) return@addOnSuccessListener

                @Suppress("UNCHECKED_CAST")
                val rawMap = snap.get("requirements")
                val rawRequirements: Map<String, Boolean> = (rawMap as? Map<String, Boolean>) ?: emptyMap()

                val loaded = mutableMapOf<String, Boolean>()

                // Map each Firestore schema key to the adapter's expected key names
                // Note: db field is "reflectorizedVest" — maps to adapter key "reflectorizedVest"
                loaded["aircon"]           = rawRequirements["aircon"]           ?: false
                loaded["closedShoes"]      = rawRequirements["closedShoes"]      ?: false
                loaded["headcap"]         = rawRequirements["headcap"]          ?: false
                loaded["pants"]           = rawRequirements["pants"]            ?: false
                loaded["safetyShoes"]     = rawRequirements["safetyShoes"]      ?: false
                loaded["reflectorizedVest"] = rawRequirements["reflectorizedVest"] ?: false

                requirementsSnapshot = loaded
                // Re-submit current assignments with requirements now populated
                if (assignments.isNotEmpty()) {
                    val updated = assignments.map { it.copy(requirements = loaded) }
                    assignments = updated
                    submitToAdapter(updated)
                }
            }
    }

    /**
     * Opens ObdItemsBottomSheet for the tapped assignment's line items.
     */
    private fun openObdBottomSheet(assignment: Assignment) {
        if (assignment.lineItems.isEmpty()) {
            Toast.makeText(this, "No OBD items available", Toast.LENGTH_SHORT).show()
            return
        }

        if (assignment.isInTransit) {
            // In-transit shipment → each completed row navigates to MyTripActivity
            ObdItemsBottomSheet.newInstance(
                lineItems = assignment.lineItems,
                shipmentDocId = assignment.docId,
                onItemClick = { item ->
                    val intent = Intent(this, MyTripActivity::class.java)
                    intent.putExtra("shipment_doc_id", assignment.docId)
                    startActivity(intent)
                },
                onObdCameraTap = null
            ).show(supportFragmentManager, ObdItemsBottomSheet::class.java.simpleName)
        } else {
            // Pre-transit → read-only view of all OBD items
            ObdItemsBottomSheet.newInstance(
                lineItems = assignment.lineItems,
                shipmentDocId = assignment.docId,
                onItemClick = null,
                onObdCameraTap = null
            ).show(supportFragmentManager, ObdItemsBottomSheet::class.java.simpleName)
        }
    }

    private fun buildAssignment(
        snap: com.google.firebase.firestore.DocumentSnapshot
    ): Assignment {
        val queueId = snap.getString("driverId") ?: "" // queue doc ID (not Firebase UID)

        val tripStatus = snap.getString("tripStatus")
        val guardVerified = snap.getBoolean("guardVerified") ?: false
        val driverConfirmed = snap.getBoolean("driverConfirmed") ?: false
        val guardFirstName = snap.getString("guardFirstName") ?: ""
        val guardLastName = snap.getString("guardLastName") ?: ""
        val guardSignature = snap.getString("guardSignature") ?: ""

        // Extract shipmentNumber from ROOT document field (SAP field), not from lineItems
        val shipmentNumberRaw = snap.getString("shipmentNumber") ?: ""

        // lineItems[0].actualDelDate — OData format "/Date(millis)/"
        val lineItems = snap.get("lineItems") as? List<*> ?: emptyList<Any>()
        val firstItem = lineItems.filterIsInstance<Map<String, Any?>>().firstOrNull()
        val delTsRaw = firstItem?.get("actualDelDate") as? String
        val deliveryTs = try {
            delTsRaw?.removePrefix("/Date(")?.removeSuffix(")/")?.toLong() ?: 0L
        } catch (e: Exception) { 0L }

        val obdCount = lineItems.filterIsInstance<Map<String, Any?>>().size
        val obdNumber = firstItem?.get("obdNumber") as? String ?: ""

        return Assignment(
            docId = snap.id,
            queueId = queueId,
            driverId = sessionManager.getDriverId() ?: "",
            status = snap.getString("status") ?: "",
            tripStatus = tripStatus,
            plateNumber = snap.getString("truckPlate") ?: "",
            truckType = snap.getString("truckCompany") ?: "",
            helper = snap.getString("helperName") ?: "",
            guardVerified = guardVerified,
            driverConfirmed = driverConfirmed,
            guardFirstName = guardFirstName,
            guardLastName = guardLastName,
            guardSignature = guardSignature,
            deliveryTimestamp = deliveryTs,
            obdCount = obdCount,
            requirements = requirementsSnapshot,
            lineItems = lineItems.filterIsInstance<Map<String, Any?>>() as List<Map<String, Any>>,
            obdNumber = obdNumber,
            shipmentNumberRaw = shipmentNumberRaw
        )
    }

    private fun submitToAdapter(list: List<Assignment>) {
        // Only lock a shipment if activeSapDocId is NOT stale.
        // Must be: (a) present in current list AND (b) actually in_transit in Firestore.
        // Prevents stale SharedPreferences from locking cards from past sessions.
        val rawActiveId = sessionManager.getActiveSapDocId()
        val sessionActiveDocId = rawActiveId?.takeIf { storedId ->
            list.any { it.docId == storedId && it.isInTransit }
        }

        val adapter = (binding.rvAssignments.adapter as? AssignmentAdapter)
            ?: AssignmentAdapter(
                onTripActionClick = { assignment -> onAssignmentAction(assignment) },
                onGuardPillTap = { assignment -> openGuardVerification(assignment) },
                onObdTap = { assignment -> openObdBottomSheet(assignment) },
                onVerificationAction = { assignment, guardVerified ->
                    if (guardVerified) showGuardVerificationSheet(assignment)
                    else openGuardVerification(assignment)
                }
            ).also { binding.rvAssignments.adapter = it }

        // Always refresh sessionActiveDocId so bind() has the current value
        adapter.sessionActiveDocId = sessionActiveDocId
        adapter.submitList(list.toList())

        if (list.isEmpty()) {
            binding.rvAssignments.visibility = View.GONE
            binding.emptyState.visibility = View.VISIBLE
        } else {
            binding.rvAssignments.visibility = View.VISIBLE
            binding.emptyState.visibility = View.GONE
        }

        // Second pass: load queue doc for truckType / helper / plateNumber
        list.forEachIndexed { idx, assignment ->
            if (assignment.queueId.isBlank()) return@forEachIndexed
            db.collection("queue").document(assignment.queueId).get()
                .addOnSuccessListener { queueDoc ->
                    if (!queueDoc.exists()) return@addOnSuccessListener
                    val plateNumber = queueDoc.getString("plateNumber") ?: assignment.plateNumber
                    val truckType = queueDoc.getString("truckType") ?: assignment.truckType
                    val helper = queueDoc.getString("helper") ?: assignment.helper

                    val updated = assignment.copy(
                        plateNumber = plateNumber,
                        truckType = truckType,
                        helper = helper
                    )
                    val mutableList = assignments.toMutableList()
                    if (idx < mutableList.size) {
                        mutableList[idx] = updated
                        assignments = mutableList
                        adapter.sessionActiveDocId = sessionManager.getActiveSapDocId()
                        adapter.submitList(assignments.toList())
                    }
                }
        }
    }

    /**
     * Updates both header badge views:
     * - Top bar pill (aggregateBadgeContainer / tvAggregateBadge)
     * - Red banner pill (badgeDot / tvBadgeText)
     */
    private fun updateAggregateBadge(list: List<Assignment>) {
        if (list.isEmpty()) {
            binding.aggregateBadgeContainer.visibility = View.GONE
            binding.badgeVerified.visibility = View.GONE
            return
        }
        binding.aggregateBadgeContainer.visibility = View.VISIBLE
        binding.badgeVerified.visibility = View.VISIBLE

        val pendingCount = list.count { !it.guardVerified }
        if (pendingCount == 0) {
            binding.aggregateBadgeContainer.setBackgroundResource(R.drawable.bg_status_pill_green)
            binding.aggregateBadgeDot.setBackgroundResource(R.drawable.bg_status_dot_green)
            binding.tvAggregateBadge.text = getString(R.string.assignment_badge_all_verified)
            binding.badgeVerified.setBackgroundResource(R.drawable.bg_status_pill_green)
            binding.badgeDot.setBackgroundResource(R.drawable.bg_status_dot_green)
            binding.tvBadgeText.text = getString(R.string.assignment_badge_all_verified)
        } else {
            binding.aggregateBadgeContainer.setBackgroundResource(R.drawable.bg_status_pill_queued)
            binding.aggregateBadgeDot.setBackgroundResource(R.drawable.bg_status_dot_gold)
            binding.tvAggregateBadge.text = getString(R.string.assignment_badge_pending)
            binding.badgeVerified.setBackgroundResource(R.drawable.bg_status_pill_queued)
            binding.badgeDot.setBackgroundResource(R.drawable.bg_status_dot_gold)
            binding.tvBadgeText.text = getString(R.string.assignment_badge_pending)
        }
    }

    // ═══ Bouncing Circles + Pulse Rings ═══

    private fun startCircleAnimation() {
        binding.circleContainer.post {
            val containerW = binding.circleContainer.width
            val containerH = binding.circleContainer.height
            if (containerW == 0 || containerH == 0) return@post

            val random = java.util.Random()
            val count = random.nextInt(4) + 3 // 3–6 circles

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
                startBounceAnimation(circle, sizePx, containerW, containerH, minSpeed, maxSpeed,
                    startX, startY, (i * 150L) + random.nextInt(500))
            }
        }
    }

    private fun startPulseRings() {
        startPulseRing(binding.pulseRing1)
        startPulseRing(binding.pulseRing2, delayMs = 1200L)
    }

    private fun startPulseRing(ring: View, delayMs: Long = 0L) {
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
}