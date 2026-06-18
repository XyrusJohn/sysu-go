package com.sysu.go.activities

import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.firebase.firestore.FirebaseFirestore
import com.sysu.go.R
import com.sysu.go.databinding.ActivityQueueDetailsBinding
import com.sysu.go.utils.SessionManager

class QueueDetailsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityQueueDetailsBinding
    private val db = FirebaseFirestore.getInstance()
    private var currentSapDocId: String = ""
    private var currentLineItems: List<Map<String, Any?>> = emptyList()
    private var hasShipment: Boolean = false   // true only when sap_assignment doc is loaded
    private var queueDriverId: String = ""    // Firebase UID — used for requirements lookup

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

        // OBD items
        binding.cardObdItems.setOnClickListener {
            val sheet = ObdItemsBottomSheet.newInstance(currentLineItems)
            sheet.show(supportFragmentManager, ObdItemsBottomSheet.TAG)
        }
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
            .addSnapshotListener { snap, err ->
                if (err != null || snap == null || !snap.exists()) return@addSnapshotListener
                populateQueueDetailsUI(snap)
            }
    }

    /**
     * Populates all UI fields from a sap_assignments document.
     * sap_assignments/{shipmentNumber} — doc ID is the shipment number itself.
     * Requirements are loaded via 2-hop: driverId (queue doc ID) → queue.driverId (Firebase UID) → drivers.requirements.
     */
    private fun populateQueueDetailsUI(doc: com.google.firebase.firestore.DocumentSnapshot) {
        val shipmentNumber = doc.id  // document ID IS the shipment number (e.g. "0010128715")

        // ── Banner: show shipment number as queue reference ──
        binding.tvQueueNumberLabel.text = "SHIPMENT"
        binding.tvQueueNumber.text = shipmentNumber

        // ── Subtitle ──
        binding.tvSubStatus.text = "Being loaded"

        // ── Details card fields from sap_assignments root ──
        binding.tvShipmentNumber.text = shipmentNumber
        binding.tvPlateNo.text   = doc.getString("truckPlate")   ?: "—"
        // tvTruckType and tvHelper come from the queue doc (loaded inside loadRequirementsAndQueueData below)

        // ── Customer Requirements + Queue fields: 2-hop via queue doc ──
        val queueDocId = doc.getString("driverId") ?: ""
        if (queueDocId.isNotBlank()) {
            loadRequirementsAndQueueData(queueDocId)
        } else {
            // Fallback: clear requirements and fields
            bindRequirement(binding.tvReqAircon, null)
            bindRequirement(binding.tvReqVest, null)
            bindRequirement(binding.tvReqHeadcap, null)
            bindRequirement(binding.tvReqSafetyShoes, null)
            bindRequirement(binding.tvReqPants, null)
            bindRequirement(binding.tvReqClosedShoes, null)
            binding.tvTruckType.text = "—"
            binding.tvHelper.text    = "—"
        }

        // ── Planned Delivery: actualDelDate lives in lineItems[0] as OData string "/Date(millis)/" ──
        @Suppress("UNCHECKED_CAST")
        val lineItems = doc.get("lineItems") as? List<Map<String, Any?>> ?: emptyList()
        currentLineItems = lineItems.mapNotNull { it as? Map<String, Any?> }
        val firstItem = lineItems.firstOrNull()
        val rawDate = firstItem?.get("actualDelDate") as? String
        binding.tvDeliveryDate.text = formatODataDate(rawDate)

        // ── OBD count pill ──
        binding.tvObdCount.text = lineItems.size.toString()

        currentSapDocId = shipmentNumber
        hasShipment = true
    }

    /**
     * Loads queue doc data AND driver requirements via 2-hop:
     * sap_assignments.driverId (= queue doc ID) → queue.driverId (Firebase UID) → drivers.requirements
     *
     * Also sets truckType, helper, and plateNumber from the queue doc (source of truth for these fields).
     */
    private fun loadRequirementsAndQueueData(queueDocId: String) {
        db.collection("queue").document(queueDocId).get()
            .addOnSuccessListener { queueDoc ->
                if (queueDoc == null || !queueDoc.exists() || isFinishing) return@addOnSuccessListener

                // ── Truck type, helper, plate — from queue document ──
                binding.tvTruckType.text = queueDoc.getString("truckType") ?: "—"
                binding.tvHelper.text    = queueDoc.getString("helper")   ?: "—"
                // plateNumber may already be set from sap; only override if queue has it (queue is source of truth)
                val queuePlate = queueDoc.getString("plateNumber") ?: ""
                if (queuePlate.isNotBlank()) binding.tvPlateNo.text = queuePlate

                // ── Get Firebase UID for requirements lookup ──
                val firebaseUid = queueDoc.getString("driverId") ?: ""
                if (firebaseUid.isBlank()) return@addOnSuccessListener
                db.collection("drivers").document(firebaseUid).get()
                    .addOnSuccessListener { driverDoc ->
                        if (driverDoc == null || !driverDoc.exists() || isFinishing) return@addOnSuccessListener
                        @Suppress("UNCHECKED_CAST")
                        val req = driverDoc.get("requirements") as? Map<String, Any?> ?: return@addOnSuccessListener
                        bindRequirement(binding.tvReqAircon,        req["aircon"]           as? Boolean)
                        bindRequirement(binding.tvReqVest,         req["reflectorizedVest"] as? Boolean)
                        bindRequirement(binding.tvReqHeadcap,      req["headcap"]          as? Boolean)
                        bindRequirement(binding.tvReqSafetyShoes, req["safetyShoes"]      as? Boolean)
                        bindRequirement(binding.tvReqPants,       req["pants"]            as? Boolean)
                        bindRequirement(binding.tvReqClosedShoes, req["closedShoes"]      as? Boolean)
                    }
            }
    }

    /** Binds a requirement TextView: green ✓ (true), gray — (false/null). */
    private fun bindRequirement(tv: android.widget.TextView, value: Boolean?) {
        if (isFinishing) return
        when (value) {
            true -> {
                tv.text = "✔"
                tv.setTextColor(ContextCompat.getColor(this, R.color.transit_green))
            }
            else -> {
                tv.text = "—"
                tv.setTextColor(ContextCompat.getColor(this, R.color.text_muted))
            }
        }
    }

    /** Parses OData date string "/Date(millis)/" into a human-readable "MMM dd, yyyy • h:mm a". */
    private fun formatODataDate(raw: String?): String {
        if (raw == null) return "—"
        return try {
            val millis = raw.removePrefix("/Date(").removeSuffix(")/").toLong()
            val sdf = java.text.SimpleDateFormat("MMM dd, yyyy • h:mm a", java.util.Locale.US)
            sdf.format(java.util.Date(millis))
        } catch (e: Exception) {
            "—"
        }
    }

    private fun processQueueDocument(doc: com.google.firebase.firestore.DocumentSnapshot, source: String) {
        if (isFinishing) return
        val status = doc.getString("status") ?: "unknown"
        Log.d("OBD", ">>> processQueueDocument source=$source status='$status'")

        binding.tvQueueNumber.text = status.replaceFirstChar {
            if (it.isLowerCase()) it.titlecase() else it.toString()
        }

        // Persist queue doc ID for later lookup
        currentQueueDocId = doc.id
        sessionManager.saveCurrentQueueDocId(doc.id)

        // ── 2-hop: queue.driverId (Firebase UID) → drivers.requirements ──
        val driverId = doc.getString("driverId") ?: ""
        if (driverId.isNotBlank()) {
            queueDriverId = driverId
            loadRequirementsFromDriver(driverId)
        } else {
            clearRequirements()
        }

        binding.tvPlateNo.text    = doc.getString("plateNumber")   ?: "—"
        binding.tvTruckType.text  = doc.getString("truckType")      ?: "—"
        binding.tvHelper.text     = doc.getString("helper")         ?: "—"

        val joinedAt = doc.getTimestamp("joinedAt")
        if (joinedAt != null) {
            val truckType = doc.getString("truckType") ?: ""
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
        Log.d("OBD", ">>> sap query using queueDocId='$currentQueueDocId' (doc.id, not driverId='$driverId')")
        db.collection("sap_assignments")
            .whereEqualTo("driverId", currentQueueDocId)
            .get()
            .addOnSuccessListener { assignSnaps ->
                if (assignSnaps.isEmpty) {
                    Log.d("OBD", ">>> sap_assignments WALA")
                    hasShipment = false
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

                hasShipment = true

                // ── Read and display delivery date from sap_assignments ──
                val delTimestamp = assignSnaps.documents[0].getTimestamp("actualDelDate")
                if (delTimestamp != null && !isFinishing) {
                    val cal = java.util.Calendar.getInstance()
                    cal.timeInMillis = delTimestamp.toDate().time
                    val fmt = java.text.SimpleDateFormat("MMM dd, yyyy • h:mm a", java.util.Locale.US)
                    binding.tvDeliveryDate.text = fmt.format(cal.time)
                } else {
                    binding.tvDeliveryDate.text = "—"
                }

                // Override queue number with LOADING once shipment is assigned
                if (!isFinishing) {
                    binding.tvQueueNumber.text = "LOADING"
                    binding.tvQueueNumberLabel.visibility = View.GONE
                }
            }
            .addOnFailureListener { e ->
                Log.e("OBD", ">>> sap_assignments FAILED: ${e.message}")
            }
    }

    private val sessionManager by lazy { SessionManager(this) }
    private var currentQueueDocId: String = ""

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

    private fun clearRequirements() {
        listOf(
            binding.tvReqAircon,
            binding.tvReqVest,
            binding.tvReqHeadcap,
            binding.tvReqSafetyShoes,
            binding.tvReqPants,
            binding.tvReqClosedShoes
        ).forEach { tv ->
            tv.text = "—"
            tv.setTextColor(ContextCompat.getColor(this, R.color.text_muted))
        }
        binding.tvTruckType.text = "—"
        binding.tvHelper.text    = "—"
    }
}