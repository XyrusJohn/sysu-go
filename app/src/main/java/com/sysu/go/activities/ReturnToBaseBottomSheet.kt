package com.sysu.go.activities

import android.app.Dialog
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.sysu.go.R
import com.sysu.go.databinding.LayoutReturnPanelBinding
import com.sysu.go.network.SocketManager
import com.sysu.go.services.LocationServices
import com.sysu.go.utils.SessionManager
import com.sysu.go.utils.DriverStatus
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Floating bottom panel shown when a driver taps the map icon after all
 * shipments are completed. Three-step return-to-base flow:
 *
 *   1. START RETURNING TO BASE  → drivers.returnStatus = "returning_to_base"
 *   2. ARRIVED AT BASE         → drivers.returnStatus = "arrived_at_base"
 *                                  (strict geo-tracking stops here)
 *   3. CONFIRM ARRIVAL         → tripStatus = "completed" on every doc,
 *                                  drivers.returnStatus = "completed",
 *                                  queue released, navigate to MainActivity
 *
 * Per-shipment `tripStatus` stays semantic (ends at "completed" once
 * all PODs done). The driver-level return journey lives on
 * drivers.{driverId}.returnStatus so the web app can listen to it
 * without conflating it with shipment progress.
 */
class ReturnToBaseBottomSheet : DialogFragment() {

    private var _binding: LayoutReturnPanelBinding? = null
    private val binding get() = _binding!!
    private val db = FirebaseFirestore.getInstance()
    private val sessionManager by lazy { SessionManager(requireContext()) }

    /**
     * Comma-separated docIds of non-completed shipments.
     * MainActivity passes all remaining shipments so they can all be updated
     * on the final CONFIRM step.
     */
    private var shipmentDocIds: String = ""

    /**
     * Current driver-level return status read from drivers.returnStatus.
     * null = returned-to-base trip not yet started
     * "returning_to_base" = driver started the journey back
     * "arrived_at_base" = driver physically at warehouse, awaiting confirm
     */
    private var currentReturnStatus: String? = null

    /**
     * True once the user has tapped "ARRIVED AT BASE" — the panel
     * transitions to the inline confirmation step.
     */
    private var awaitingWarehouseConfirm: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        shipmentDocIds = arguments?.getString(ARG_SHIPMENT_DOC_IDS, "") ?: ""
        currentReturnStatus = arguments?.getString(ARG_RETURN_STATUS, null)
        setStyle(STYLE_NORMAL, R.style.ThemeOverlay_FrostedBlueBottomSheet)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        dialog.window?.requestFeature(Window.FEATURE_NO_TITLE)
        return dialog
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = LayoutReturnPanelBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.overlay.setOnClickListener { dismiss() }
        binding.btnDismiss.setOnClickListener { dismiss() }

        bindPanelContent()
        bindConfirmButton()
    }

    private fun bindPanelContent() {
        when {
            awaitingWarehouseConfirm -> configureForWarehouseConfirmStep()
            currentReturnStatus == "arrived_at_base" -> configureForWarehouseConfirmStep()
            currentReturnStatus == "returning_to_base" -> configureForReturningState()
            else -> configureForDeliveredState()
        }
    }

    private fun bindConfirmButton() {
        binding.btnConfirmReturn.setOnClickListener {
            when {
                // Step 2: Arrived at Base (Unlocked only if inside geofence)
                currentReturnStatus == "returning_to_base" && !awaitingWarehouseConfirm -> {
                    showDualConfirmation(
                        title = "Dumating na sa Base?",
                        message = "Kumpirmahin kung nakarating ka na sa $warehouseName. Ito ay magsisilbing senyales ng pagdating sa warehouse.",
                        confirmText = "Oo, Dumating Na",
                        confirmColor = R.color.sysu_blue,
                        iconRes = R.drawable.ic_location_crosshairs
                    ) {
                        performArrivedAtBase()
                    }
                }
                // Step 3: Final Confirm Arrival (release driver & complete trip)
                currentReturnStatus == "arrived_at_base" || awaitingWarehouseConfirm -> {
                    showDualConfirmation(
                        title = "Kumpirmahin ang Pagtatapos?",
                        message = "Ito ang magtatapos sa buong shipment session at magbabalik sa iyo sa pila para sa susunod na biyahe.",
                        confirmText = "Oo, Tapusin ang Biyahe",
                        confirmColor = R.color.sysu_green,
                        iconRes = R.drawable.ic_dialog_confirm
                    ) {
                        performConfirmArrival()
                    }
                }
                // Step 1: Start Returning to Base
                else -> {
                    showDualConfirmation(
                        title = "Babalik na sa Warehouse?",
                        message = "Kumpirmahin kung tapos na ang lahat ng deliveries at papunta ka na pabalik sa $warehouseName.",
                        confirmText = "Oo, Bumalik sa Base",
                        confirmColor = R.color.sysu_blue,
                        iconRes = R.drawable.ic_dialog_confirm
                    ) {
                        performStartReturning()
                    }
                }
            }
        }
    }

    private fun showDualConfirmation(
        title: String,
        message: String,
        confirmText: String,
        confirmColor: Int,
        iconRes: Int,
        onConfirm: () -> Unit
    ) {
        val ctx = requireContext()
        val dlgView = LayoutInflater.from(ctx).inflate(R.layout.dialog_sysu_confirmation, null)
        val dialog = androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setView(dlgView)
            .setCancelable(true)
            .create()

        dlgView.findViewById<android.widget.ImageView>(R.id.ivDialogIcon)?.apply {
            setImageResource(iconRes)
            backgroundTintList = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(ctx, confirmColor)
            )
        }
        dlgView.findViewById<android.widget.TextView>(R.id.tvDialogTitle)?.text = title
        dlgView.findViewById<android.widget.TextView>(R.id.tvDialogMessage)?.text = message

        dlgView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnCancel)?.setOnClickListener {
            dialog.dismiss()
        }

        dlgView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnConfirm)?.apply {
            text = confirmText
            backgroundTintList = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(ctx, confirmColor)
            )
            setOnClickListener {
                dialog.dismiss()
                onConfirm()
            }
        }

        dialog.show()
    }

    // ─── DocId helpers ────────────────────────────────────────────────────────

    private fun getFirstDocId(): String? =
        shipmentDocIds.split(",").firstOrNull { it.isNotBlank() }
            ?: sessionManager.getActiveSapDocId()

    private fun getAllDocIds(): List<String> =
        shipmentDocIds.split(",").filter { it.isNotBlank() }.ifEmpty {
            listOfNotNull(sessionManager.getActiveSapDocId())
        }

    // ─── Panel content ───────────────────────────────────────────────────────

    // ─── Warehouse Geofence Configuration ──────────────────────────────────
    private var warehouseLat: Double = 14.671073
    private var warehouseLng: Double = 121.014855
    private var warehouseRadiusMeters: Double = 100.0
    private var warehouseName: String = "SYSU Main Warehouse"
    private var isInsideGeofence: Boolean = false

    private val fusedLocationClient by lazy {
        com.google.android.gms.location.LocationServices.getFusedLocationProviderClient(requireContext())
    }

    private var geofenceLocationCallback: com.google.android.gms.location.LocationCallback? = null

    private fun configureForDeliveredState() {
        stopGeofenceLocationUpdates()
        binding.tvPanelTitle.text = "All Deliveries Complete"
        binding.tvPanelSubtitle.text = "All shipments have been delivered. Ready to return to base?"
        binding.tvDeliveredSummary.visibility = View.VISIBLE
        binding.tvDeliveredSummary.text = getString(R.string.return_panel_all_delivered)
        binding.llGeofenceStatus.visibility = View.GONE
        binding.btnConfirmReturn.text = "START RETURNING TO BASE"
        binding.btnConfirmReturn.isEnabled = true
        binding.btnConfirmReturn.alpha = 1.0f
        binding.btnConfirmReturn.backgroundTintList = ContextCompat.getColorStateList(
            requireContext(), R.color.sysu_blue
        )
    }

    private fun configureForReturningState() {
        binding.tvPanelTitle.text = "Returning to Base"
        binding.tvPanelSubtitle.text = "Heading back to $warehouseName. Mag-a-unlock ang 'Arrived' button kapag nasa loob na ng ${warehouseRadiusMeters.toInt()}m radius."
        binding.tvDeliveredSummary.visibility = View.GONE
        binding.llGeofenceStatus.visibility = View.VISIBLE

        binding.btnConfirmReturn.text = "ARRIVED AT BASE"
        // Initially locked until GPS confirms driver is inside geofence
        updateGeofenceUI(false, null)

        // Fetch dynamic warehouse config and start live GPS geofence tracking
        fetchWarehouseBaseSettingsAndStartTracking()
    }

    private fun configureForWarehouseConfirmStep() {
        stopGeofenceLocationUpdates()
        binding.tvPanelTitle.text = "Confirm Arrival"
        binding.tvPanelSubtitle.text = "You're at $warehouseName. Tap CONFIRM to finalize the trip and return to queue."
        binding.tvDeliveredSummary.visibility = View.GONE
        binding.llGeofenceStatus.visibility = View.GONE
        binding.btnConfirmReturn.text = "YES, CONFIRM ARRIVAL"
        binding.btnConfirmReturn.isEnabled = true
        binding.btnConfirmReturn.alpha = 1.0f
        binding.btnConfirmReturn.backgroundTintList = ContextCompat.getColorStateList(
            requireContext(), R.color.sysu_green
        )
    }

    /**
     * Fetches warehouse_base from settings collection:
     *   settings/warehouse_base -> lat, lng, radiusMeters, name
     * Then initiates live GPS updates to continuously check distance against geofence.
     */
    private fun fetchWarehouseBaseSettingsAndStartTracking() {
        lifecycleScope.launch {
            try {
                val doc = db.collection("settings").document("warehouse_base").get().await()
                if (doc.exists()) {
                    warehouseLat = doc.getDouble("lat") ?: warehouseLat
                    warehouseLng = doc.getDouble("lng") ?: warehouseLng
                    val radiusObj = doc.get("radiusMeters")
                    warehouseRadiusMeters = when (radiusObj) {
                        is Number -> radiusObj.toDouble()
                        else -> 100.0
                    }
                    warehouseName = doc.getString("name") ?: warehouseName
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to fetch warehouse_base settings, using defaults: ${e.message}")
            }
            startGeofenceLocationUpdates()
        }
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun startGeofenceLocationUpdates() {
        if (!com.sysu.go.utils.PermissionHelper.hasLocationPermission(requireContext())) {
            updateGeofenceUI(false, null, "Kailangan ng Location Permission para ma-verify ang distansya.")
            return
        }

        // Quick initial check via lastLocation
        fusedLocationClient.lastLocation.addOnSuccessListener { loc ->
            if (loc != null) {
                evaluateLocation(loc)
            } else {
                updateGeofenceUI(false, null, "Kinukuha pa ang GPS signal...")
            }
        }

        // Continuous location requests while bottom sheet is open in returning_to_base state
        if (geofenceLocationCallback == null) {
            val locationRequest = com.google.android.gms.location.LocationRequest.Builder(
                com.google.android.gms.location.Priority.PRIORITY_HIGH_ACCURACY,
                3000L
            ).setMinUpdateIntervalMillis(2000L)
             .setMinUpdateDistanceMeters(2f)
             .build()

            geofenceLocationCallback = object : com.google.android.gms.location.LocationCallback() {
                override fun onLocationResult(result: com.google.android.gms.location.LocationResult) {
                    val location = result.lastLocation ?: return
                    evaluateLocation(location)
                }
            }

            try {
                fusedLocationClient.requestLocationUpdates(
                    locationRequest,
                    geofenceLocationCallback!!,
                    android.os.Looper.getMainLooper()
                )
            } catch (e: SecurityException) {
                Log.w(TAG, "SecurityException on geofence location updates: ${e.message}")
            }
        }
    }

    private fun evaluateLocation(loc: android.location.Location) {
        val results = FloatArray(1)
        android.location.Location.distanceBetween(
            loc.latitude, loc.longitude,
            warehouseLat, warehouseLng,
            results
        )
        val distanceMeters = results[0]
        val inside = distanceMeters <= warehouseRadiusMeters
        isInsideGeofence = inside
        updateGeofenceUI(inside, distanceMeters)
    }

    private fun stopGeofenceLocationUpdates() {
        geofenceLocationCallback?.let {
            try {
                fusedLocationClient.removeLocationUpdates(it)
            } catch (_: Exception) {}
            geofenceLocationCallback = null
        }
    }

    private fun updateGeofenceUI(inside: Boolean, distanceMeters: Float?, customError: String? = null) {
        if (_binding == null) return

        isInsideGeofence = inside
        if (customError != null) {
            binding.tvGeofenceDistance.text = customError
            binding.tvGeofenceBadge.text = "GPS Error"
            binding.tvGeofenceBadge.setTextColor(ContextCompat.getColor(requireContext(), R.color.sysu_red))
            binding.tvGeofenceBadge.setBackgroundResource(R.drawable.bg_chip_required)
            binding.ivGeofenceIcon.setColorFilter(ContextCompat.getColor(requireContext(), R.color.sysu_red))
            binding.btnConfirmReturn.isEnabled = false
            binding.btnConfirmReturn.alpha = 0.5f
            return
        }

        if (inside) {
            val distStr = if (distanceMeters != null) "${distanceMeters.toInt()}m mula sa base" else "Nasa loob ng warehouse area"
            binding.tvGeofenceDistance.text = "✓ $distStr (Pasok sa ${warehouseRadiusMeters.toInt()}m radius)"
            binding.tvGeofenceBadge.text = "Nasa Base Na ✓"
            binding.tvGeofenceBadge.setTextColor(ContextCompat.getColor(requireContext(), R.color.transit_green))
            binding.tvGeofenceBadge.setBackgroundResource(R.drawable.bg_chip_granted)
            binding.ivGeofenceIcon.setColorFilter(ContextCompat.getColor(requireContext(), R.color.transit_green))
            binding.btnConfirmReturn.isEnabled = true
            binding.btnConfirmReturn.alpha = 1.0f
            binding.btnConfirmReturn.backgroundTintList = ContextCompat.getColorStateList(
                requireContext(), R.color.sysu_blue
            )
        } else {
            val distText = if (distanceMeters != null) {
                if (distanceMeters >= 1000) {
                    String.format(java.util.Locale.US, "%.1f km layo mula sa base", distanceMeters / 1000.0)
                } else {
                    "${distanceMeters.toInt()}m layo mula sa base"
                }
            } else "Kinakalkula..."
            binding.tvGeofenceDistance.text = "Kasalukuyang distansya: $distText (Kailangan ≤ ${warehouseRadiusMeters.toInt()}m)"
            binding.tvGeofenceBadge.text = "Nasa Labas Pa"
            binding.tvGeofenceBadge.setTextColor(ContextCompat.getColor(requireContext(), R.color.sysu_red))
            binding.tvGeofenceBadge.setBackgroundResource(R.drawable.bg_chip_required)
            binding.ivGeofenceIcon.setColorFilter(ContextCompat.getColor(requireContext(), R.color.sysu_red))
            binding.btnConfirmReturn.isEnabled = false
            binding.btnConfirmReturn.alpha = 0.5f
        }
    }

    // ─── Actions ──────────────────────────────────────────────────────────────

    /**
     * Step 1: START RETURNING TO BASE
     * Writes:
     *   - drivers.status = "returning_to_base"
     *   - drivers.isReturning = true
     *   - drivers.returnStatus = "returning_to_base"
     *   - drivers.returnedToBaseAt = now
     */
    private fun performStartReturning() {
        val docIds = getAllDocIds()
        if (docIds.isEmpty()) {
            Toast.makeText(requireContext(), "Walang shipment na naka-link sa biyahe.", Toast.LENGTH_LONG).show()
            return
        }
        if (!com.sysu.go.utils.PermissionHelper.hasLocationPermission(requireContext())) {
            Toast.makeText(requireContext(), "Kailangan ng Location Permission para ma-track ang biyahe pabalik sa base.", Toast.LENGTH_LONG).show()
            return
        }
        showLoading("Saving return status...")

        val now = Timestamp.now()
        lifecycleScope.launch {
            try {
                val driverId = sessionManager.getDriverId()
                if (driverId != null) {
                    db.collection("drivers").document(driverId)
                        .update(mapOf(
                            "status" to "returning_to_base",
                            "isReturning" to true,
                            "returnStatus" to "returning_to_base",
                            "returnedToBaseAt" to now
                        ))
                        .await()
                }

                for (docId in docIds) {
                    db.collection("sap_assignments").document(docId)
                        .update(mapOf(
                            "tripStatus" to "returning_to_base",
                            "returnStatus" to "returning_to_base"
                        ))
                        .await()
                }

                // Restore an owned assignment anchor and GPS tracking for the return leg.
                val trackingAnchor = docIds.first()
                sessionManager.saveActiveSapDocId(trackingAnchor)
                sessionManager.saveCurrentSapDocId(trackingAnchor)
                sessionManager.setTripActive(true)
                sessionManager.setTrackingActive(true)
                ContextCompat.startForegroundService(
                    requireContext(),
                    Intent(requireContext(), LocationServices::class.java)
                )
                com.sysu.go.audit.DriverActionAudit.recordFreshLocation(
                    requireContext(), "returning_to_base", subjectId = trackingAnchor,
                    idempotencyKey = "returning:$trackingAnchor"
                )

                Log.d(TAG, "performStartReturning: driver → returning_to_base (isReturning=true)")
                // Mark driver-level status for the Profile badge and Socket.IO broadcast
                DriverStatus.write(driverId, DriverStatus.State.RETURNING_TO_BASE)
                currentReturnStatus = "returning_to_base"
                hideLoading()
                bindPanelContent()
                bindConfirmButton()
            } catch (e: Exception) {
                Log.e(TAG, "performStartReturning error: ${e.message}")
                hideLoading()
                com.sysu.go.diagnostics.UserErrorNotifier.showFailure(requireActivity(), "trip-start", e)
                binding.btnConfirmReturn.isEnabled = true
                binding.btnConfirmReturn.text = "START RETURNING TO BASE"
            }
        }
    }

    /**
     * Step 2: ARRIVED AT BASE (Only enabled when GPS confirms within warehouse radius)
     * Writes:
     *   - drivers.returnStatus = "arrived_at_base"
     *   - drivers.arrivedAtBaseAt = now
     *   - Stops GPS tracking
     */
    private fun performArrivedAtBase() {
        if (!isInsideGeofence) {
            Toast.makeText(requireContext(), "Nasa labas ka pa ng warehouse base! Hindi pa pwedeng mag-arrive.", Toast.LENGTH_LONG).show()
            return
        }

        showLoading("Confirming arrival...")

        val now = Timestamp.now()
        lifecycleScope.launch {
            try {
                // 1. Save arrival state to drivers collection
                val driverId = sessionManager.getDriverId()
                if (driverId != null) {
                    db.collection("drivers").document(driverId)
                        .update(mapOf(
                            "returnStatus" to "arrived_at_base",
                            "arrivedAtBaseAt" to now
                        ))
                        .await()
                }

                Log.d(TAG, "performArrivedAtBase: driver → arrived_at_base")
                DriverStatus.write(driverId, DriverStatus.State.RETURNING_TO_BASE)

                // 2. Stop geo tracking
                sessionManager.setTrackingActive(false)
                try {
                    val uid = sessionManager.getDriverId()
                    SocketManager.init(requireContext())
                    if (uid != null) {
                        SocketManager.emitOfflineStatus(uid, sessionManager.getDriverName())
                    }
                    requireContext().stopService(Intent(requireContext(), LocationServices::class.java))
                } catch (e: Exception) {
                    Log.w(TAG, "performArrivedAtBase: offline emit skipped — ${e.message}")
                }

                currentReturnStatus = "arrived_at_base"
                hideLoading()
                bindPanelContent()
                bindConfirmButton()
            } catch (e: Exception) {
                Log.e(TAG, "performArrivedAtBase error: ${e.message}")
                hideLoading()
                com.sysu.go.diagnostics.UserErrorNotifier.showFailure(requireActivity(), "shipment-state-transition", e)
                binding.btnConfirmReturn.isEnabled = true
                binding.btnConfirmReturn.text = "ARRIVED AT BASE"
            }
        }
    }

    /**
     * Step 3: YES, CONFIRM ARRIVAL — writes tripStatus="completed" + completedAt
     * on all non-completed shipments, releases the queue, sets
     * drivers.returnStatus = "completed", and navigates back to MainActivity.
     * Shipments at this point may already be "completed" from the END SHIPMENT
     * step — this just stamps the final completedAt timestamp and clears state.
     */
    private fun performConfirmArrival() {
        val docIds = getAllDocIds()
        if (docIds.isEmpty()) {
            Toast.makeText(requireContext(), "Walang aktibong biyahe.", Toast.LENGTH_SHORT).show()
            dismiss(); return
        }

        // Capture queue doc ID BEFORE clearing session — needed to update Firestore queue
        val queueDocId = sessionManager.getCurrentQueueDocId()

        showLoading("Completing trip...")

        val now = Timestamp.now()
        // Mirror the driver-level return journey into each sap_assignment so the
        // per-shipment document carries its own return outcome. This makes
        // sap_assignments.{docId} the single source of truth for a shipment's
        // full lifecycle (loading → in_transit → completed → returned_to_base)
        // — no need to chase drivers.returnStatus when querying past trips.
        val updates = mapOf(
            "tripStatus" to "completed",
            "completedAt" to now,
            "returnStatus" to "completed",
            "returnedToBaseAt" to now
        )
        lifecycleScope.launch {
            try {
                // Update all shipments to completed
                for (docId in docIds) {
                    db.collection("sap_assignments").document(docId)
                        .update(updates)
                        .await()
                    Log.d(TAG, "performConfirmArrival: $docId → completed")
                }

                // Update drivers collection — trip completed and update session history
                val driverId = sessionManager.getDriverId()
                if (driverId != null) {
                    val driverRef = db.collection("drivers").document(driverId)
                    // Read current driver data
                    val driverDoc = driverRef.get().await()
                    if (driverDoc.exists()) {
                        val data = driverDoc.data!!
                        val arrivedAtBaseAt = data["arrivedAtBaseAt"] as? Timestamp
                        // We will set returnedToBaseAt to now (the time of confirming return)
                        val returnedToBaseAtNow = now
                        val completedAtNow = now
                        val fn = data["fn"] as? String
                        val mi = data["mi"] as? String
                        val ln = data["ln"] as? String
                        val name = data["name"] as? String
                        val phone = data["phone"] as? String
                        val truckingCompany = data["truckingCompany"] as? String
                        val requirements = data["requirements"] as? List<String> ?: emptyList()

                        // Create session object for history
                        val session = hashMapOf(
                            "arrivedAtBaseAt" to arrivedAtBaseAt,
                            "returnedToBaseAt" to returnedToBaseAtNow,
                            "completedAt" to completedAtNow,
                            "fn" to fn,
                            "mi" to mi,
                            "ln" to ln,
                            "name" to name,
                            "phone" to phone,
                            "truckingCompany" to truckingCompany,
                            "requirements" to requirements
                        )

                        // Get current session history or initialize empty list
                        val sessionHistory = data["sessionHistory"] as? List<*> ?: mutableListOf<Any>()
                        val mutableHistory = sessionHistory.toMutableList()
                        mutableHistory.add(session)
                        // Keep only the last 50 sessions (newest at the end)
                        if (mutableHistory.size > 50) {
                            mutableHistory.removeAt(0) // Remove oldest
                        }

                        // Update driver document
                        driverRef.update(mapOf(
                            "status" to "online",
                            "isReturning" to false,
                            "returnStatus" to "completed",
                            "returnedToBaseAt" to returnedToBaseAtNow,
                            "completedAt" to completedAtNow,
                            "sessionHistory" to mutableHistory
                        )).await()

                        // Reset driver-level status back to "online" — trip cycle complete
                        DriverStatus.write(driverId, DriverStatus.State.ONLINE)
                    }
                }

                // Stop this trip's tracking before releasing the queue; a new QR session
                // must not have its map marker or local session cleared by this completion.
                sessionManager.setTrackingActive(false)
                sessionManager.clearTripSession()
                try {
                    val uid = sessionManager.getDriverId() ?: FirebaseAuth.getInstance().currentUser?.uid
                    val driverName = sessionManager.getDriverName() ?: "Driver"
                    SocketManager.init(requireContext())
                    if (uid != null) {
                        SocketManager.emitCompletedStatus(uid, driverName)
                        SocketManager.emitStatusUpdate("completed", driverName)
                        db.collection("driver_locations").document(uid).delete()
                    }
                    requireContext().stopService(Intent(requireContext(), LocationServices::class.java))
                } catch (e: Exception) {
                    Log.w(TAG, "performConfirmArrival: completed/offline emit skipped — ${e.message}")
                }

                // Release only the captured queue session. Never close every active queue for this
                // driver: a fresh QR scan may create the next session as soon as this one is closed.
                val queueCompletion = mapOf(
                    "status" to "completed",
                    "overallTripStatus" to "completed",
                    "completedAt" to FieldValue.serverTimestamp(),
                    "isAssigned" to false
                )
                if (!queueDocId.isNullOrBlank()) {
                    db.collection("queue").document(queueDocId).update(queueCompletion).await()
                    Log.d(TAG, "performConfirmArrival: queue $queueDocId marked completed")
                } else {
                    val assignmentQueueIds = docIds.mapNotNull { docId ->
                        db.collection("sap_assignments").document(docId).get().await().getString("queueId")
                            ?.takeIf { it.isNotBlank() }
                    }.distinct()
                    val assignmentQueueId = assignmentQueueIds.singleOrNull()
                        ?: throw IllegalStateException("Cannot identify the exact queue session to complete")
                    db.collection("queue").document(assignmentQueueId).update(queueCompletion).await()
                    Log.d(TAG, "performConfirmArrival: resolved queue $assignmentQueueId marked completed")
                }

                // Arrival closes the audit session only after geofence-gated confirmation and writes succeed.
                com.sysu.go.audit.DriverActionAudit.completeSession(requireContext())

                // Do not erase a newly admitted queue ID if MainActivity already observed the next scan.
                if (sessionManager.getCurrentQueueDocId() == queueDocId) {
                    sessionManager.saveCurrentQueueDocId(null)
                }

                dismiss()
                startActivity(Intent(requireContext(), MainActivity::class.java))
            } catch (e: Exception) {
                Log.e(TAG, "performConfirmArrival error: ${e.message}")
                hideLoading()
                com.sysu.go.diagnostics.UserErrorNotifier.showFailure(requireActivity(), "shipment-completion", e)
                binding.btnConfirmReturn.isEnabled = true
                binding.btnConfirmReturn.text = "YES, CONFIRM ARRIVAL"
            }
        }
    }

    private fun showLoading(message: String) {
        binding.llLoading.visibility = View.VISIBLE
        binding.tvLoadingText.text = message
        binding.btnConfirmReturn.isEnabled = false
    }

    private fun hideLoading() {
        binding.llLoading.visibility = View.GONE
        binding.btnConfirmReturn.isEnabled = true
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundDrawableResource(android.R.color.transparent)
        }
    }

    override fun onDestroyView() {
        stopGeofenceLocationUpdates()
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val TAG = "ReturnToBaseBottomSheet"
        private const val ARG_SHIPMENT_DOC_IDS = "shipment_doc_ids"
        private const val ARG_RETURN_STATUS = "return_status"

        /**
         * @param shipmentDocIds comma-separated docIds of all non-completed shipments
         * @param currentReturnStatus current drivers.returnStatus value
         *        (null | "returning_to_base" | "arrived_at_base")
         */
        fun newInstance(
            shipmentDocIds: String = "",
            currentReturnStatus: String? = null
        ): ReturnToBaseBottomSheet {
            return ReturnToBaseBottomSheet().apply {
                arguments = Bundle().apply {
                    putString(ARG_SHIPMENT_DOC_IDS, shipmentDocIds)
                    putString(ARG_RETURN_STATUS, currentReturnStatus)
                }
            }
        }
    }
}
