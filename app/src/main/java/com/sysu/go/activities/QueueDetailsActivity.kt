package com.sysu.go.activities

import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.sysu.go.R
import com.sysu.go.databinding.ActivityQueueDetailsBinding
import com.sysu.go.model.SecurityGuard
import com.sysu.go.repository.SecurityGuardRepository
import com.sysu.go.utils.SessionManager
import kotlinx.coroutines.launch

class QueueDetailsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityQueueDetailsBinding
    private val db = FirebaseFirestore.getInstance()
    private var currentSapDocId: String = ""
    private var currentGuardFirstName: String = ""
    private var currentGuardLastName: String = ""
    private var currentGuardMI: String = ""
    private var currentGuardSignature: String = ""
    private var currentVerifiedAtMillis: Long = 0L
    private var pendingSignatureBase64: String? = null
    private var currentLineItems: List<Map<String, Any>> = emptyList()

    // Security guard data (loaded once, used by bottom sheet)
    private var securityGuards: List<SecurityGuard> = emptyList()
    private var currentSelectedGuardId: String = ""
    private var hasShipment: Boolean = false  // true only when sap_assignment doc is loaded
    private var queueDriverId: String = ""  // Firebase UID — used for requirements lookup

    // Circle animation tracking
    private val circleAnimators = mutableListOf<android.animation.Animator>()
    private val circleViews = mutableListOf<View>()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityQueueDetailsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        setupGuardSelection()
        setupUI()
        startCircleAnimation()

        // ── Per-shipment loading: if opened from a "My Queue" card tap ──
        val explicitShipmentDocId = intent.getStringExtra("shipment_doc_id")
        if (!explicitShipmentDocId.isNullOrBlank()) {
            loadSpecificShipment(explicitShipmentDocId)
        } else {
            loadQueueDetails()
        }
    }

    private fun setupGuardSelection() {
        val repository = SecurityGuardRepository(this)

        // Load from cache immediately so guards are ready for bottom sheet
        securityGuards = repository.getCachedGuards()

        // Refresh in background and update cache
        lifecycleScope.launch {
            val fresh = repository.refreshGuards()
            Log.d("OBD", "refreshGuards returned ${fresh.size} guards")
            if (fresh.isNotEmpty()) {
                securityGuards = fresh
            }
        }
    }

    private fun startCircleAnimation() {
        circleViews.forEach { binding.circleContainer.removeView(it) }
        circleViews.clear()

        binding.circleContainer.post {
            val containerW = binding.circleContainer.width
            val containerH = binding.circleContainer.height
            if (containerW == 0 || containerH == 0) return@post

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

                val circle = View(this).apply {
                    layoutParams = FrameLayout.LayoutParams(sizePx, sizePx)
                    background = ContextCompat.getDrawable(this@QueueDetailsActivity, R.drawable.bg_circle_light_red)
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
            duration = 16L
            repeatCount = android.animation.ValueAnimator.INFINITE
            this.startDelay = startDelayMs
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

    override fun onDestroy() {
        super.onDestroy()
        circleAnimators.forEach { it.cancel() }
        circleAnimators.clear()
        circleViews.forEach { binding.circleContainer.removeView(it) }
        circleViews.clear()
    }

    private fun setupUI() {
        binding.btnBack.setOnClickListener { finish() }

        // ── Guard card: open GuardSelectionBottomSheet ──
        binding.cardSelectGuard.setOnClickListener {
            if (securityGuards.isEmpty()) {
                Toast.makeText(this, "Guards not loaded yet", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val sheet = GuardSelectionBottomSheet.newInstance(securityGuards) { guard ->
                currentSelectedGuardId = guard.guardId
                binding.tvSelectedGuardName.text = guard.fullName
                binding.tvSelectedGuardName.setTextColor(
                    ContextCompat.getColor(this@QueueDetailsActivity, R.color.text_primary)
                )
                updateSignButtonState()
            }
            sheet.show(supportFragmentManager, GuardSelectionBottomSheet.TAG)
        }

        // ── STEP 1: SIGN — opens floating bottom sheet for signature capture ──
        binding.btnSign.setOnClickListener {
            if (!hasShipment || currentSelectedGuardId.isBlank()) return@setOnClickListener

            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            currentFocus?.let { imm.hideSoftInputFromWindow(it.windowToken, 0) }

            val sheet = SignaturePadBottomSheet()
            sheet.onSignatureConfirmed = { base64 ->
                pendingSignatureBase64 = base64
                binding.btnVerify.visibility = View.VISIBLE
            }
            sheet.show(supportFragmentManager, SignaturePadBottomSheet.TAG)
        }

        // ── STEP 2: VERIFY & SEND ──
        binding.btnVerify.setOnClickListener {
            val base64 = pendingSignatureBase64
            if (base64.isNullOrBlank()) {
                Toast.makeText(this, "Signature required", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            binding.btnVerify.isEnabled = false

            val selectedGuard = securityGuards.find { it.guardId == currentSelectedGuardId }
            if (selectedGuard == null) {
                binding.btnVerify.isEnabled = true
                Toast.makeText(this, "Guard selection error. Please re-select.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            saveVerificationToFirestore(signatureBase64 = base64, guard = selectedGuard)
        }

        // View previously submitted verification
        binding.cardFloatingViewVerification.setOnClickListener {
            val sheet = com.sysu.go.activities.GuardVerificationBottomSheet.newInstance(
                firstName = currentGuardFirstName,
                lastName = currentGuardLastName,
                mi = currentGuardMI,
                signature = currentGuardSignature,
                verifiedAt = currentVerifiedAtMillis
            )
            sheet.show(supportFragmentManager, com.sysu.go.activities.GuardVerificationBottomSheet.TAG)
        }

        // OBD items
        binding.cardObdItems.setOnClickListener {
            val sheet = ObdItemsBottomSheet.newInstance(currentLineItems)
            sheet.show(supportFragmentManager, ObdItemsBottomSheet.TAG)
        }

        // Initial sign button state — locked until shipment assigned + guard selected
        binding.btnSign.isEnabled = false
        updateSignButtonState()
    }

    /**
     * Gates SIGN button: only enabled when shipment exists AND guard is selected.
     */
    private fun updateSignButtonState() {
        val canSign = hasShipment && currentSelectedGuardId.isNotBlank()
        binding.btnSign.isEnabled = canSign
    }

    private fun loadQueueDetails() {
        val session = SessionManager(this)
        val sessionDriverId = session.getDriverId() ?: run {
            Log.e("OBD", ">>> driverId is NULL — walang session!")
            return
        }
        Log.d("OBD", ">>> Session driverId: '$sessionDriverId'")

        // ── ATTEMPT 1 ── Primary query using SessionManager's driverId (Firebase UID).
        // Works because queue.driverId field = Firebase UID in Firestore.
        db.collection("queue")
            .whereEqualTo("driverId", sessionDriverId)
            .whereIn("status", listOf("assigned", "waiting", "called", "loading", "in_transit"))
            .addSnapshotListener { snapshots, error ->
                if (error != null) {
                    Log.e("OBD", ">>> queue query ERROR: ${error.message}")
                    return@addSnapshotListener
                }
                if (snapshots != null && !snapshots.isEmpty) {
                    Log.d("OBD", ">>> ATTEMPT 1 SUCCESS — queue found via sessionDriverId")
                    processQueueDocument(snapshots.documents[0], source = "attempt1_sessionDriverId")
                    return@addSnapshotListener
                }

                // ── ATTEMPT 2 ── Fallback: look up queueDriverId from sap_assignments.
                // IMPORTANT: sap_assignments.driverId stores the QUEUE DOCUMENT ID (doc.id),
                // NOT the Firebase UID. Must query by doc.id, not by sessionDriverId.
                Log.d("OBD", ">>> ATTEMPT 1 empty — trying ATTEMPT 2 (sap fallback)...")
                db.collection("sap_assignments")
                    .whereEqualTo("driverId", sessionDriverId)
                    .get()
                    .addOnSuccessListener { sapSnaps ->
                        if (!sapSnaps.isEmpty) {
                            val queueDriverId = sapSnaps.documents[0].getString("driverId") ?: sessionDriverId
                            Log.d("OBD", ">>> ATTEMPT 2 found sap, queueDriverId='$queueDriverId'")
                            db.collection("queue")
                                .whereEqualTo("driverId", queueDriverId)
                                .whereIn("status", listOf("assigned", "waiting", "called", "loading", "in_transit"))
                                .get()
                                .addOnSuccessListener { qSnaps ->
                                    if (!qSnaps.isEmpty) {
                                        Log.d("OBD", ">>> ATTEMPT 2 queue found via queueDriverId from sap")
                                        processQueueDocument(qSnaps.documents[0], source = "attempt2_sapFallback")
                                    } else {
                                        Log.d("OBD", ">>> ATTEMPT 2 also empty")
                                    }
                                }
                            return@addOnSuccessListener
                        }

                        // ── ATTEMPT 3 ── Last resort: direct sessionDriverId scan of queue.
                        Log.d("OBD", ">>> ATTEMPT 2 empty — trying ATTEMPT 3 (direct scan)...")
                        db.collection("queue")
                            .whereEqualTo("driverId", sessionDriverId)
                            .whereIn("status", listOf("assigned", "waiting", "called", "loading", "in_transit"))
                            .get()
                            .addOnSuccessListener { scanSnapshots ->
                                if (!scanSnapshots.isEmpty) {
                                    Log.d("OBD", ">>> ATTEMPT 3 SUCCESS")
                                    processQueueDocument(scanSnapshots.documents[0], source = "attempt3_directScan")
                                } else {
                                    Log.d("OBD", ">>> ATTEMPT 3 empty — no queue for driverId='$sessionDriverId'")
                                }
                            }
                    }
            }
    }

    /**
     * Loads a specific shipment directly by its sap_assignments document ID.
     * Called when the activity is opened from a "My Queue" dashboard card tap
     * (shipment_doc_id intent extra is passed).
     */
    private fun loadSpecificShipment(shipmentDocId: String) {
        Log.d("OBD", ">>> loadSpecificShipment docId=$shipmentDocId")
        db.collection("sap_assignments").document(shipmentDocId)
            .get()
            .addOnSuccessListener { snap ->
                if (snap == null || !snap.exists()) {
                    Toast.makeText(this, "Shipment not found", Toast.LENGTH_SHORT).show()
                    finish()
                    return@addOnSuccessListener
                }
                processSapDocFromQueueDetails(shipmentDocId, snap.data ?: return@addOnSuccessListener)
            }
            .addOnFailureListener { e ->
                Log.e("OBD", ">>> loadSpecificShipment FAILED: ${e.message}")
                Toast.makeText(this, "Failed to load shipment", Toast.LENGTH_SHORT).show()
                finish()
            }
    }

    /**
     * Populates all UI fields from a specific sap_assignments document.
     * For per-shipment view opened from dashboard card tap.
     */
    private fun processSapDocFromQueueDetails(docId: String, data: Map<String, Any>) {
        Log.d("OBD", ">>> processSapDocFromQueueDetails docId=$docId")

        // sap_assignments.driverId = QUEUE DOCUMENT ID (doc.id), NOT Firebase UID
        val queueDocId = data["driverId"] as? String ?: ""
        currentSapDocId = docId
        hasShipment = true

        // ── Shipment number = sap_assignments doc ID ──
        binding.tvShipmentNumber.text = docId

        // ── Queue number: no queue position context, show "—" ──
        binding.tvQueueNumber.text = "—"
        binding.tvQueueNumberLabel.text = "QUEUE NUMBER"

        // ── Sub-status ──
        val status = data["status"] as? String ?: ""
        binding.tvSubStatus.text = when (status) {
            "assigned" -> "Shipment Assigned"
            "in_transit" -> "Trip in Progress"
            "loading" -> "Being loaded"
            "waiting" -> "Waiting in queue"
            "called" -> "Proceed to bay"
            else -> status.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        }

        // ── Plate / Truck / Helper from queue doc ──
        // Requirements (driver-level) need FIREBASE UID, which lives in queueDoc["driverId"]
        if (queueDocId.isNotBlank()) {
            db.collection("queue").document(queueDocId)
                .get()
                .addOnSuccessListener { queueDoc ->
                    if (queueDoc == null || !queueDoc.exists() || isFinishing) return@addOnSuccessListener
                    val q = queueDoc.data ?: return@addOnSuccessListener

                    // Plate / Truck / Helper from the queue document
                    binding.tvPlateNo.text     = q["plateNumber"]  as? String ?: "—"
                    binding.tvTruckType.text   = q["truckType"]   as? String ?: "—"
                    binding.tvHelper.text      = q["helper"]      as? String ?: "—"

                    // Get the FIREBASE UID (queueDoc["driverId"]) for requirements lookup
                    val firebaseUid = q["driverId"] as? String ?: ""
                    queueDriverId = firebaseUid
                    if (firebaseUid.isNotBlank()) {
                        loadRequirementsFromDriver(firebaseUid)
                    }
                }
        } else {
            binding.tvPlateNo.text   = "—"
            binding.tvTruckType.text = "—"
            binding.tvHelper.text    = "—"
        }

        // ── OBD line items ──
        val lineItemsRef = data["lineItems"]
        if (lineItemsRef != null && lineItemsRef is List<*>) {
            val lineItems = lineItemsRef.mapNotNull { it as? Map<*, *> }
            if (lineItems.isNotEmpty()) {
                @Suppress("UNCHECKED_CAST")
                val items = lineItems as? List<Map<String, Any>> ?: emptyList()
                currentLineItems = items
                binding.tvObdCount.text = items.size.toString()
            } else {
                currentLineItems = emptyList()
                binding.tvObdCount.text = "0"
            }
        } else {
            currentLineItems = emptyList()
            binding.tvObdCount.text = "0"
        }

        // ── Planned delivery date ──
        val delTimestamp = data["actualDelDate"] as? com.google.firebase.Timestamp
        if (delTimestamp != null && !isFinishing) {
            val cal = java.util.Calendar.getInstance()
            cal.timeInMillis = delTimestamp.toDate().time
            val fmt = java.text.SimpleDateFormat("MMM dd, yyyy  hh:mm a", java.util.Locale.US)
            binding.tvDeliveryDate.text = fmt.format(cal.time)
        } else {
            binding.tvDeliveryDate.text = "—"
        }

        // ── Guard verification ──
        val guardVerified = data["guardVerified"] as? Boolean ?: false
        if (guardVerified) {
            binding.guardVerificationSection.visibility = View.GONE
            binding.cardFloatingViewVerification.visibility = View.VISIBLE
            binding.btnVerify.visibility = View.GONE

            currentGuardFirstName = data["guardFirstName"] as? String ?: ""
            currentGuardLastName = data["guardLastName"] as? String ?: ""
            currentGuardMI = data["guardMI"] as? String ?: ""
            currentGuardSignature = data["guardSignature"] as? String ?: ""
            currentSelectedGuardId = data["guardId"] as? String ?: ""
            val ts = data["verifiedAt"] as? com.google.firebase.Timestamp
            currentVerifiedAtMillis = ts?.toDate()?.time ?: 0L
        } else {
            binding.guardVerificationSection.visibility = View.VISIBLE
            binding.cardFloatingViewVerification.visibility = View.GONE
            binding.btnVerify.visibility = View.GONE
            updateSignButtonState()
        }
    }

    /**
     * Shared handler for a confirmed queue document, used across all attempt fallbacks.
     * @param source Debug tag showing which attempt succeeded.
     */
    private fun processQueueDocument(doc: DocumentSnapshot, source: String) {
        Log.d("OBD", ">>> processQueueDocument FROM: $source")

        val queueDocId = doc.id
        val data = doc.data ?: return
        val status = data["status"] as? String ?: ""
        val joinedAt = data["joinedAt"] as? com.google.firebase.Timestamp
        val driverId = data["driverId"] as? String ?: ""
        queueDriverId = driverId

        binding.tvPlateNo.text = data["plateNumber"] as? String ?: "—"
        binding.tvTruckType.text = data["truckType"] as? String ?: "—"
        binding.tvHelper.text = data["helperName"] as? String ?: data["helper"] as? String ?: "—"

        // Load requirements from driver document (async, non-blocking)
        loadRequirementsFromDriver(driverId)

        binding.tvSubStatus.text = when (status) {
            "assigned" -> "Shipment Assigned"
            "waiting" -> "Waiting in queue"
            "called" -> "Proceed to bay"
            "in_transit" -> "Trip in Progress"
            "loading" -> "Being loaded"
            else -> status.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        }

        if (joinedAt != null) {
            val truckType = data["truckType"] as? String ?: ""
            db.collection("queue")
                .whereEqualTo("truckType", truckType)
                .whereIn("status", listOf("assigned", "waiting", "called"))
                .whereEqualTo("registrationComplete", true)
                .whereLessThan("joinedAt", joinedAt)
                .get()
                .addOnSuccessListener { posSnapshots ->
                    // Only show position number if no shipment has been assigned yet
                    if (!hasShipment) {
                        binding.tvQueueNumber.text = (posSnapshots.size() + 1).toString()
                        binding.tvQueueNumberLabel.visibility = View.VISIBLE
                    }
                }
        } else if (status == "loading") {
            binding.tvQueueNumber.text = "GO"
        }

        // ── CRITICAL: sap_assignments.driverId stores the QUEUE DOCUMENT ID (doc.id),
        // NOT queue["driverId"] which is the Firebase UID. Use queueDocId here.
        Log.d("OBD", ">>> sap query using queueDocId='$queueDocId' (doc.id, not driverId='$driverId')")
        db.collection("sap_assignments")
            .whereEqualTo("driverId", queueDocId)
            .get()
            .addOnSuccessListener { assignSnaps ->
                if (assignSnaps.isEmpty) {
                    Log.d("OBD", ">>> sap_assignments WALA — hiding guard section")
                    hasShipment = false
                    binding.guardVerificationSection.visibility = View.GONE
                    binding.cardFloatingViewVerification.visibility = View.GONE
                    binding.btnVerify.visibility = View.GONE
                    updateSignButtonState()
                    return@addOnSuccessListener
                }

                binding.tvShipmentNumber.text = assignSnaps.documents[0].id
                currentSapDocId = assignSnaps.documents[0].id

                val lineItemsRef = assignSnaps.documents[0].get("lineItems")
                if (lineItemsRef != null && lineItemsRef is List<*>) {
                    val lineItems = lineItemsRef.mapNotNull { it as? Map<*, *> }
                    if (lineItems.isNotEmpty()) {
                        @Suppress("UNCHECKED_CAST")
                        val items = lineItems as? List<Map<String, Any>> ?: emptyList()
                        currentLineItems = items
                        binding.tvObdCount.text = items.size.toString()
                    } else {
                        currentLineItems = emptyList()
                        binding.tvObdCount.text = "0"
                    }
                } else {
                    currentLineItems = emptyList()
                    binding.tvObdCount.text = "0"
                }

                val guardVerified = assignSnaps.documents[0].getBoolean("guardVerified") ?: false
                hasShipment = true

                // ── Read and display delivery date from sap_assignments ──
                val delTimestamp = assignSnaps.documents[0].getTimestamp("actualDelDate")
                if (delTimestamp != null && !isFinishing) {
                    val cal = java.util.Calendar.getInstance()
                    cal.timeInMillis = delTimestamp.toDate().time
                    val fmt = java.text.SimpleDateFormat("MMM dd, yyyy  hh:mm a", java.util.Locale.US)
                    binding.tvDeliveryDate.text = fmt.format(cal.time)
                } else {
                    binding.tvDeliveryDate.text = "—"
                }

                // Override queue number with LOADING once shipment is assigned
                if (!isFinishing) {
                    binding.tvQueueNumber.text = "LOADING"
                    binding.tvQueueNumberLabel.visibility = View.GONE
                }

                if (guardVerified) {
                    binding.guardVerificationSection.visibility = View.GONE
                    binding.cardFloatingViewVerification.visibility = View.VISIBLE
                    binding.btnVerify.visibility = View.GONE

                    currentGuardFirstName = assignSnaps.documents[0].getString("guardFirstName") ?: ""
                    currentGuardLastName = assignSnaps.documents[0].getString("guardLastName") ?: ""
                    currentGuardMI = assignSnaps.documents[0].getString("guardMI") ?: ""
                    currentGuardSignature = assignSnaps.documents[0].getString("guardSignature") ?: ""
                    currentSelectedGuardId = assignSnaps.documents[0].getString("guardId") ?: ""
                    val ts = assignSnaps.documents[0].getTimestamp("verifiedAt")
                    currentVerifiedAtMillis = ts?.toDate()?.time ?: 0L
                } else {
                    binding.guardVerificationSection.visibility = View.VISIBLE
                    binding.cardFloatingViewVerification.visibility = View.GONE
                    binding.btnVerify.visibility = View.GONE
                    updateSignButtonState()
                }
            }
            .addOnFailureListener { e ->
                Log.e("OBD", ">>> sap_assignments FAILED: ${e.message}")
            }
    }

    private fun loadRequirementsFromDriver(driverId: String) {
        if (driverId.isBlank()) return
        db.collection("drivers").document(driverId)
            .get()
            .addOnSuccessListener { doc ->
                @Suppress("UNCHECKED_CAST")
                val reqMap = doc.get("requirements") as? Map<String, Boolean>
                displayRequirement("aircon", reqMap, binding.tvReqAircon)
                displayRequirement("reflectorizedVest", reqMap, binding.tvReqVest)
                displayRequirement("safetyShoes", reqMap, binding.tvReqSafetyShoes)
                displayRequirement("headcap", reqMap, binding.tvReqHeadcap)
                displayRequirement("pants", reqMap, binding.tvReqPants)
                displayRequirement("closedShoes", reqMap, binding.tvReqClosedShoes)
            }
    }

    private fun displayRequirement(
        key: String,
        reqMap: Map<String, Boolean>?,
        tv: android.widget.TextView
    ) {
        val checked = reqMap?.get(key) ?: false
        if (isFinishing) return
        tv.text = if (checked) "✔" else "—"
        tv.setTextColor(
            ContextCompat.getColor(
                this@QueueDetailsActivity,
                if (checked) R.color.transit_green else R.color.text_muted
            )
        )
    }

    private fun saveVerificationToFirestore(signatureBase64: String, guard: SecurityGuard) {
        if (currentSapDocId.isBlank()) {
            binding.btnVerify.isEnabled = true
            Toast.makeText(this, "Error: assignment not loaded. Please restart.", Toast.LENGTH_LONG).show()
            return
        }

        val updates = mapOf(
            "guardVerified" to true,
            "guardId" to guard.guardId,
            "guardFirstName" to guard.firstName,
            "guardLastName" to guard.lastName,
            "guardMI" to guard.middleInitial,
            "guardSignature" to signatureBase64,
            "verifiedAt" to com.google.firebase.Timestamp.now()
        )

        db.collection("sap_assignments").document(currentSapDocId)
            .update(updates)
            .addOnSuccessListener {
                binding.guardVerificationSection.visibility = View.GONE
                binding.cardFloatingViewVerification.visibility = View.VISIBLE
                binding.btnVerify.visibility = View.GONE

                currentGuardFirstName = guard.firstName
                currentGuardLastName = guard.lastName
                currentGuardMI = guard.middleInitial
                currentGuardSignature = signatureBase64
                currentSelectedGuardId = guard.guardId
                currentVerifiedAtMillis = System.currentTimeMillis()

                Toast.makeText(this, "Verification saved", Toast.LENGTH_SHORT).show()
            }
            .addOnFailureListener { e ->
                Log.e("OBD", ">>> verification save FAILED: ${e.message}")
                binding.btnVerify.isEnabled = true
                Toast.makeText(this, "Failed to save verification. Try again.", Toast.LENGTH_SHORT).show()
            }
    }
}