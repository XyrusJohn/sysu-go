package com.sysu.go.activities

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkInfo
import android.content.pm.PackageManager
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.MenuItem
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices as GmsLocationServices
import com.google.android.gms.location.Priority
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.sysu.go.R
import com.sysu.go.databinding.ActivityTransitBinding
import com.sysu.go.network.SocketManager
import com.sysu.go.services.LocationServices
import com.sysu.go.utils.SessionManager
import com.sysu.go.utils.TrackingStateMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.Locale

class TransitActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTransitBinding
    private val db = FirebaseFirestore.getInstance()
    private val activityScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val sessionManager by lazy { SessionManager(this) }

    private var fusedLocationClient: FusedLocationProviderClient? = null
    private var locationCallback: LocationCallback? = null
    private var androidLocationListener: LocationListener? = null
    private var currentSapDocId: String = ""
    private var currentHelper: String? = null
    private var currentTruckType: String? = null
    private var isTripEnded: Boolean = false   // true when tripStatus=completed
    private var isReturningToBase: Boolean = false
    private var isPodCompleteForAll: Boolean = false
    private var isTripStarted = false
    private var isSessionRestored = false      // true when opened via session restore (cold-start mid-trip)
    private var socketConnectListener: (() -> Unit)? = null
    private var isOfflineDialogShowing = false
    private var connectionReceiver: BroadcastReceiver? = null

    // Circle animation tracking (same pattern as QueueDetailsActivity)
    private val circleAnimators = mutableListOf<android.animation.Animator>()
    private val circleViews = mutableListOf<View>()

    /** Updates the tracking state dot + subtitle text in the header bar. */
    private val trackingStateListener = object : TrackingStateMonitor.Listener {
        override fun onTrackingStateChanged(state: TrackingStateMonitor.State) {
            runOnUiThread { updateTrackingStateUI(state) }
        }
    }

    companion object {
        private const val TAG = "TransitActivity"
        private const val LOCATION_PERMISSION_REQUEST = 1001
    }

    @SuppressLint("MissingInflatedId")
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityTransitBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // Restore trip session state if driver re-opens app mid-trip (including after force-stop)
        val savedDocId = sessionManager.getCurrentSapDocId()
        val wasTripActive = sessionManager.isTripActive()
        Log.d(TAG, "[STATE RESTORE] docId=$savedDocId isTripActive=$wasTripActive")
        if (savedDocId != null && wasTripActive) {
            currentSapDocId = savedDocId
            isTripStarted = true
            isSessionRestored = true
            binding.tvSubStatus.text = "Trip in progress"
            binding.btnStartTrip.text = "BACK TO HOME"
            binding.btnStartTrip.setBackgroundColor(ContextCompat.getColor(this@TransitActivity, R.color.transit_green))
            binding.btnStartTrip.isEnabled = true

            // Pre-connect sockets so emitLastKnownLocation succeeds on Start Trip
            SocketManager.init(this)
            SocketManager.connect()

            // On socket connect: re-emit last known location immediately, then start tracking
            socketConnectListener = {
                val lastLat = sessionManager.getLastEmitLat()
                val lastLng = sessionManager.getLastEmitLng()
                Log.d(TAG, "[RESTORE] Re-emitting last location: [$lastLat, $lastLng]")
                SocketManager.emitDriverMoving(lastLat, lastLng, currentSapDocId, "online")
                // NOTE: DO NOT unconditionally write tripStatus = "in_transit" here.
                // If the session was restored from returning_to_base, this would overwrite
                // the correct tripStatus. Let loadTripData() read source-of-truth instead.
            }
            socketConnectListener?.let { SocketManager.addOnConnectListener(it) }

            // Restart location tracking — survives force-stop.
            // LocationServices will read tracking_active and begin GPS ONLY if flag is true.
            sessionManager.setTrackingActive(true)
            // Reset any stale tracking state from previous trip session
            TrackingStateMonitor.setState(TrackingStateMonitor.State.ONLINE)
            if (isLocationPermissionGranted()) {
                startLocationUpdates()
            }
            Log.d(TAG, "[STATE RESTORE] Restored trip session — socket reconnect + tracking started")
        } else {
            Log.d(TAG, "[STATE RESTORE] No active trip or session was cleared")
            sessionManager.clearCurrentSapDocId()

            // Pre-connect sockets for normal non-restore path (Start Trip flow)
            SocketManager.init(this)
            SocketManager.connect()
        }

        setupUI()
        loadTripData()
        startCircleAnimation()
        updateProgressStepper()
        TrackingStateMonitor.addListener(trackingStateListener)
    }

    override fun onResume() {
        super.onResume()
        registerConnectionMonitor()
        // Strict presence: re-announce online immediately when app comes to foreground,
        // so sysu-app updates the driver marker to green without waiting for driver_moving
        if (isSessionRestored || isTripStarted) {
            SocketManager.emitStatusUpdate("online", sessionManager.getDriverName() ?: "Driver")
        }
        // Refresh ALL state when returning from MyTripActivity.
        // The driver may have completed all PODs, or tripStatus may have changed
        // (e.g. returning_to_base from dispatch web), while we were in the background.
        // This ensures button shows correct state without requiring activity recreate.
        if (currentSapDocId.isNotBlank()) {
            activityScope.launch {
                try {
                    val doc = db.collection("sap_assignments")
                        .document(currentSapDocId)
                        .get()
                        .await()
                    if (doc.exists()) {
                        val tripStatus = doc.getString("tripStatus")
                        val lineItemsAll = doc.get("lineItems") as? List<Map<String, Any?>>
                        isPodCompleteForAll = lineItemsAll?.all {
                            (it["isPodCompleted"] as? Boolean) == true
                        } ?: false
                        // Sync all derived flags with the tripStatus from Firestore
                        isTripEnded = tripStatus == "completed"
                        isReturningToBase = tripStatus == "returning_to_base"
                        isTripStarted = tripStatus == "in_transit"
                        Log.d(TAG, "[onResume refresh] tripStatus=$tripStatus isPodCompleteForAll=$isPodCompleteForAll " +
                            "isReturningToBase=$isReturningToBase isTripEnded=$isTripEnded")
                        updateProgressStepper()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "[onResume refresh] failed to re-fetch: ${e.message}")
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        unregisterConnectionMonitor()
        isOfflineDialogShowing = false
        // Strict presence: immediately tell sysu-app driver is going to background.
        // Driver stays on sysu-app map in OFFLINE (red) state at last driver_moving position.
        // This replaces relying on Socket.IO's 30s ping/pong timeout.
        if (isSessionRestored || isTripStarted) {
            SocketManager.emitStatusUpdate("offline", sessionManager.getDriverName() ?: "Driver")
        }
    }

    // ─── Permission helpers ──────────────────────────────────────────────────

    private fun isLocationPermissionGranted(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun showLocationPermissionDialog() {
        val message = """
            Location Permission Required

            Para makita ng dispatch kung nasaan ang truck mo habang nasa transit, kailangan nyang i-enable ang location permission.

            Steps:
            1. I-click ang "Open Settings" sa baba
            2. Sa App Info screen, pumunta sa "Location"
            3. I-set sa "Allow all the time" (Background location)

            Hindi kami makakapag-track ng location kung di naka-enable ito.
        """.trimIndent()

        AlertDialog.Builder(this)
            .setTitle("Enable Location")
            .setMessage(message)
            .setCancelable(false)
            .setPositiveButton("Open Settings") { _, _ ->
                openAppSettings()
            }
            .setNegativeButton("Cancel") { _, _ ->
                binding.tvLocationCoords.text = "Location: Not enabled"
                Toast.makeText(
                    this,
                    "Location di naka-enable — di makikita ng dispatch ang location mo",
                    Toast.LENGTH_LONG
                ).show()
            }
            .show()
    }

    private fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
        }
        startActivity(intent)
    }

    // ─── Setup & Data Loading ────────────────────────────────────────────────

    private fun setupUI() {
        binding.btnBack.setOnClickListener { finish() }

        binding.btnStartTrip.setOnClickListener {
            when {
                isReturningToBase -> {
                    // Confirm before ending the return trip
                    AlertDialog.Builder(this)
                        .setTitle("End Trip?")
                        .setMessage("Are you sure you want to end this trip? This cannot be undone.")
                        .setPositiveButton("End Trip") { _, _ -> performEndTrip() }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
                isTripStarted -> {
                    if (isPodCompleteForAll) {
                        AlertDialog.Builder(this)
                            .setTitle("Return to Base?")
                            .setMessage("All OBD items are complete. Begin returning to the warehouse?")
                            .setPositiveButton("Confirm") { _, _ -> transitionToReturningToBase() }
                            .setNegativeButton("Cancel", null)
                            .show()
                    } else {
                        val intent = Intent(this, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                        }
                        startActivity(intent)
                        finish()
                    }
                }
                else -> startTrip()              // not started → start trip
            }
        }
    }

    private fun stopLocationUpdates() {
        // Stop LocationServices foreground service — it owns all GPS tracking now.
        // Setting tracking_active=false first ensures LocationServices.onStartCommand
        // won't restart GPS if the system kills+restarts the service.
        sessionManager.setTrackingActive(false)
        val serviceIntent = Intent(this, LocationServices::class.java)
        stopService(serviceIntent)
        // Remove location updates from our clients BEFORE nulling the callbacks.
        // This prevents orphaned GPS requests from TransitActivity's own clients
        // (separate from LocationServices foreground service).
        var cb: LocationCallback? = null
        var listener: LocationListener? = null
        synchronized(this) {
            cb = locationCallback
            listener = androidLocationListener
            locationCallback = null
            androidLocationListener = null
        }
        cb?.let {
            try { fusedLocationClient?.removeLocationUpdates(it) } catch (_: Exception) {}
        }
        listener?.let {
            try {
                val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
                lm.removeUpdates(it)
            } catch (_: Exception) {}
        }
    }

    private fun startTrip() {
        if (currentSapDocId.isBlank()) {
            Toast.makeText(this, "Trip data not loaded yet. Try again.", Toast.LENGTH_SHORT).show()
            Log.w(TAG, "startTrip called but currentSapDocId is blank — loadTripData may have returned zero docs")
            return
        }

        binding.btnStartTrip.isEnabled = false
        binding.btnStartTrip.text = "TRIP IN PROGRESS..."

        // Save to session SYNCHRONOUSLY before Firestore call.
        // If app is killed between here and the Firestore update completing,
        // the session persists and correctly restores trip state on reopen.
        Log.d(TAG, "[START TRIP] Saving trip session: docId=$currentSapDocId")
        sessionManager.saveCurrentSapDocId(currentSapDocId)
        sessionManager.setTripActive(true)
        // Flag LocationServices to start GPS tracking — it reads this on onStartCommand.
        // emitLastKnownLocation fires immediately below (pre-GPS-fix), then
        // LocationServices begins real-time tracking as soon as GPS locks.
        sessionManager.setTrackingActive(true)
        Log.d(TAG, "[START TRIP] Session saved — now calling Firestore: tripStatus=in_transit")

        // Emit immediately using last known GPS — so sysu-app map shows marker RIGHT AWAY
        // without waiting 5-30s for first GPS fix
        emitLastKnownLocation(currentSapDocId)

        val updates = mapOf(
            "tripStatus" to "in_transit",
            "startedAt" to Timestamp.now()
        )

        db.collection("sap_assignments").document(currentSapDocId)
            .update(updates)
            .addOnSuccessListener {
                if (isFinishing) return@addOnSuccessListener
                isTripStarted = true
                Log.d(TAG, "Trip started successfully — location tracking via LocationServices (tracking_active=TRUE)")
                // Check permission before starting LocationServices — if denied, prompt user to open settings
                if (isFinishing) return@addOnSuccessListener
                if (isLocationPermissionGranted()) {
                    startLocationUpdates()
                } else {
                    showLocationPermissionDialog()
                }
                if (isFinishing) return@addOnSuccessListener
                binding.btnStartTrip.text = "BACK TO HOME"
                binding.btnStartTrip.setBackgroundColor(ContextCompat.getColor(this@TransitActivity, R.color.transit_green))
                updateProgressStepper()
            }
            .addOnFailureListener { e ->
                if (isFinishing) return@addOnFailureListener
                Log.e(TAG, "Failed to start trip: ${e.message}")
                binding.btnStartTrip.isEnabled = true
                binding.btnStartTrip.text = "START TRIP"
                Toast.makeText(this, "Failed to start trip. Check connection.", Toast.LENGTH_SHORT).show()
            }
    }

    private fun endTrip() {
        if (currentSapDocId.isBlank()) {
            Toast.makeText(this, "No active trip to end.", Toast.LENGTH_SHORT).show()
            return
        }

        // Confirm before ending
        AlertDialog.Builder(this)
            .setTitle("End Trip?")
            .setMessage("Are you sure you want to end this trip? This cannot be undone.")
            .setPositiveButton("End Trip") { _, _ -> performEndTrip() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun performEndTrip() {
        if (currentSapDocId.isBlank()) {
            Toast.makeText(this, "No active trip to end.", Toast.LENGTH_SHORT).show()
            return
        }

        binding.btnStartTrip.isEnabled = false
        binding.btnStartTrip.text = "ENDING..."

        val updates = mapOf(
            "tripStatus" to "completed",
            "completedAt" to Timestamp.now()
        )
        db.collection("sap_assignments").document(currentSapDocId)
            .update(updates)
            .addOnSuccessListener {
                if (isFinishing) return@addOnSuccessListener
                isTripStarted = false
                isTripEnded = true
                sessionManager.clearTripSession()
                // Prevent LocationServices from emitting further driver_moving.
                // Set flag BEFORE stopService so if service is auto-restarted by
                // the system, onStartCommand sees tracking_active=FALSE and skips GPS.
                sessionManager.setTrackingActive(false)

                // Stop the foreground service immediately — no more notifications, no more emit.
                stopService(Intent(this, LocationServices::class.java))
                // Reset tracking state to ONLINE — driver is free again, no active trip
                TrackingStateMonitor.setState(TrackingStateMonitor.State.ONLINE)

                // Emit offline to sysu-app Railway so marker disappears immediately
                SocketManager.init(this)
                val driverName = sessionManager.getDriverName()
                val uid = FirebaseAuth.getInstance().currentUser?.uid
                if (uid != null) {
                    SocketManager.emitOfflineStatus(uid, driverName)
                }

                stopLocationUpdates()
                if (isFinishing) return@addOnSuccessListener
                binding.btnStartTrip.text = "START TRIP"
                binding.btnStartTrip.setBackgroundTintList(
                    ContextCompat.getColorStateList(this@TransitActivity, R.color.sysu_red)
                )
                binding.btnStartTrip.isEnabled = true
                updateProgressStepper()
                Toast.makeText(this, "Trip completed!", Toast.LENGTH_LONG).show()
                Log.d(TAG, "Trip ended successfully")
            }
            .addOnFailureListener { e ->
                if (isFinishing) return@addOnFailureListener
                Log.e(TAG, "Failed to end trip: ${e.message}")
                binding.btnStartTrip.text = "BACK TO HOME"
                binding.btnStartTrip.isEnabled = true
                Toast.makeText(this, "Failed to end trip. Try again.", Toast.LENGTH_SHORT).show()
            }
    }

    private fun transitionToReturningToBase() {
        if (currentSapDocId.isBlank()) {
            Toast.makeText(this, "No active trip.", Toast.LENGTH_SHORT).show()
            return
        }
        binding.btnStartTrip.isEnabled = false
        binding.btnStartTrip.text = "UPDATING..."

        val updates = mapOf(
            "tripStatus" to "returning_to_base",
            "returnedToBaseAt" to Timestamp.now()
        )
        db.collection("sap_assignments").document(currentSapDocId)
            .update(updates)
            .addOnSuccessListener {
                if (isFinishing) return@addOnSuccessListener
                isReturningToBase = true
                isTripStarted = false
                isPodCompleteForAll = false  // reset for cleanliness
                updateProgressStepper()
                Toast.makeText(this, "Heading back to base!", Toast.LENGTH_SHORT).show()
            }
            .addOnFailureListener { e ->
                if (isFinishing) return@addOnFailureListener
                Log.e(TAG, "Failed to transition to returning_to_base: ${e.message}")
                binding.btnStartTrip.isEnabled = true
                binding.btnStartTrip.text = "START RETURNING TO BASE"
                Toast.makeText(this, "Failed. Check connection.", Toast.LENGTH_SHORT).show()
            }
    }

    private fun loadTripData() {
        val driverId = sessionManager.getDriverId() ?: run {
            Log.e(TAG, "driverId is null from sessionManager")
            return
        }

        binding.tvLocationCoords.text = "Acquiring location..."

        // OPTIMIZATION: If currentSapDocId is already set (from session restore on open),
        // load that specific doc directly instead of running the full queue→sap join.
        // This is faster and avoids silent failures in the async join.
        val restoredDocId = currentSapDocId
        if (restoredDocId.isNotBlank()) {
            Log.d(TAG, "[LOAD] currentSapDocId already set from session: $restoredDocId — fetching directly")
            // First look up the queue doc so we get currentHelper and currentTruckType
            // (those fields don't exist in sap_assignments — they only live in the queue doc)
            db.collection("queue")
                .whereEqualTo("driverId", driverId)
                .limit(1)
                .get()
                .addOnSuccessListener { queueSnap ->
                    if (!queueSnap.isEmpty) {
                        val queueDoc = queueSnap.documents.first()
                        currentHelper = queueDoc.getString("helper")
                        currentTruckType = queueDoc.getString("truckType")
                        Log.d(TAG, "[LOAD] Queue lookup: helper=$currentHelper truckType=$currentTruckType")
                    } else {
                        Log.w(TAG, "[LOAD] No queue doc for driverId=$driverId")
                    }
                    // Now fetch the sap doc and populate UI with the queue-derived fields
                    db.collection("sap_assignments").document(restoredDocId)
                        .get()
                        .addOnSuccessListener { doc ->
                            if (doc.exists()) {
                                Log.d(TAG, "[LOAD] Direct fetch found doc id=${doc.id} tripStatus=${doc.getString("tripStatus")}")
                                currentSapDocId = doc.id
                                // Compute isPodCompleteForAll
                                val lineItemsAll = doc.get("lineItems") as? List<Map<String, Any?>>
                                isPodCompleteForAll = lineItemsAll?.all { (it["isPodCompleted"] as? Boolean) == true } ?: false
                                populateShipmentUI(doc, currentHelper, currentTruckType)
                            } else {
                                Log.w(TAG, "[LOAD] Direct fetch doc $restoredDocId not found — falling back to queue join")
                                loadTripDataFromQueue(driverId)
                            }
                        }
                        .addOnFailureListener { e ->
                            Log.w(TAG, "[LOAD] Direct fetch failed: ${e.message} — falling back to queue join")
                            loadTripDataFromQueue(driverId)
                        }
                }
                .addOnFailureListener { e ->
                    Log.w(TAG, "[LOAD] Queue lookup failed: ${e.message} — fetching sap doc without helper/truckType")
                    // Still try to load the sap doc even if queue lookup fails
                    db.collection("sap_assignments").document(restoredDocId)
                        .get()
                        .addOnSuccessListener { doc ->
                            if (doc.exists()) {
                                populateShipmentUI(doc, null, null)
                            } else {
                                loadTripDataFromQueue(driverId)
                            }
                        }
                        .addOnFailureListener { e2 ->
                            Log.w(TAG, "[LOAD] Direct fetch also failed: ${e2.message}")
                            loadTripDataFromQueue(driverId)
                        }
                }
            return
        }

        // Normal flow: load trip data via queue→sap join
        loadTripDataFromQueue(driverId)
    }

    private fun loadTripDataFromQueue(driverId: String) {
        // Clear any stale completed-session from previous trip so we start fresh
        sessionManager.clearCurrentSapDocId()
        isTripStarted = false
        isTripEnded = false

        db.collection("queue")
            .whereEqualTo("driverId", driverId)
            .limit(1)
            .get()
            .addOnSuccessListener { queueSnap ->
                if (queueSnap.isEmpty) {
                    Log.w(TAG, "No queue document for driverId: $driverId")
                    return@addOnSuccessListener
                }
                val queueDoc = queueSnap.documents.first()
                val queueId = queueDoc.id
                currentHelper = queueDoc.getString("helper")
                currentTruckType = queueDoc.getString("truckType")
                Log.d(TAG, "Found queue doc id=$queueId helper=$currentHelper truckType=$currentTruckType")

                // Primary query: match driverId + tripStatus=in_transit
                db.collection("sap_assignments")
                    .whereEqualTo("driverId", queueId)
                    .whereEqualTo("tripStatus", "in_transit")
                    .limit(1)
                    .get()
                    .addOnSuccessListener { sapSnap ->
                        if (sapSnap.isEmpty) {
                            Log.w(TAG, "No sap_assignment for queueId=$queueId with tripStatus=in_transit — trying fallback")
                            // Fallback: get all sap_assignments for this driver, log all to debug
                            db.collection("sap_assignments")
                                .whereEqualTo("driverId", queueId)
                                .limit(10)
                                .get()
                                .addOnSuccessListener { fallbackSnap ->
                                    Log.d(TAG, "Fallback query returned ${fallbackSnap.size()} docs:")
                                    for (doc in fallbackSnap.documents) {
                                        Log.d(TAG, "  Doc id=${doc.id} tripStatus=${doc.getString("tripStatus")} " +
                                            "shipmentNumber=${doc.getString("shipmentNumber")} " +
                                            "shipment_number=${doc.getString("shipment_number")} " +
                                            "truckPlate=${doc.getString("truckPlate")} " +
                                            "plateNumber=${doc.getString("plateNumber")} " +
                                            "lineItems=${doc.get("lineItems")}")
                                    }
                                    if (fallbackSnap.isEmpty) return@addOnSuccessListener

                                    // Use the first doc that has some data
                                    val sapDoc = fallbackSnap.documents.first()
                                    currentSapDocId = sapDoc.id
                                    // Compute isPodCompleteForAll
                                    val lineItemsAll = sapDoc.get("lineItems") as? List<Map<String, Any?>>
                                    isPodCompleteForAll = lineItemsAll?.all { (it["isPodCompleted"] as? Boolean) == true } ?: false
                                    val fbTripStatus = sapDoc.getString("tripStatus")
                                    if (fbTripStatus == "completed") {
                                        isTripStarted = false
                                        isTripEnded = true
                                    }
                                    populateShipmentUI(sapDoc, currentHelper, currentTruckType)
                                    return@addOnSuccessListener
                                }
                            return@addOnSuccessListener
                        }
                        val sapDoc = sapSnap.documents.first()
                        currentSapDocId = sapDoc.id
                        // Compute isPodCompleteForAll
                        val lineItemsAll = sapDoc.get("lineItems") as? List<Map<String, Any?>>
                        isPodCompleteForAll = lineItemsAll?.all { (it["isPodCompleted"] as? Boolean) == true } ?: false
                        Log.d(TAG, "Found sap doc id=${sapDoc.id} tripStatus=${sapDoc.getString("tripStatus")}")
                        populateShipmentUI(sapDoc, currentHelper, currentTruckType)
                    }
                    .addOnFailureListener { e ->
                        Log.e(TAG, "Error loading SAP assignment: ${e.message}", e)
                    }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Error loading queue: ${e.message}", e)
            }
    }

    /** Populates UI fields from a sap_assignments document.
     * - Shipment number: Firestore doc ID (matches shipment number in physical documents)
     * - Plate No: truckPlate from sap_assignments
     * - Truck type: truckType from queue doc (with truckCompany from sap_assignments as fallback)
     * - Helper: helper from queue doc
     */
    private fun populateShipmentUI(sapDoc: DocumentSnapshot, helper: String?, truckType: String?) {
        currentSapDocId = sapDoc.id

        // Log ALL fields on this doc so we know exact names next time
        Log.d(TAG, "populateShipmentUI all fields on doc ${sapDoc.id}:")
        sapDoc.data?.forEach { (k, v) ->
            Log.d(TAG, "  $k = $v")
        }

        // Shipment number — use Firestore doc ID as the shipment number
        // (the physical document ships with the same number, e.g. "0010128489")
        val shipmentNumber = sapDoc.id

        // Plate number — truckPlate is the Firestore field
        val plateNumber = sapDoc.getString("truckPlate")
            ?: sapDoc.getString("TruckPlate")
            ?: sapDoc.getString("plateNumber")
            ?: sapDoc.getString("plate_number")
            ?: sapDoc.getString("truckPlateNo")
            ?: "—"

        // Truck type — prefer queue's truckType ("4W"), fall back to sap's truckCompany ("Lalamove Philippines Inc")
        val resolvedTruckType = truckType
            ?: sapDoc.getString("truckCompany")
            ?: sapDoc.getString("TruckCompany")
            ?: sapDoc.getString("truckModel")
            ?: sapDoc.getString("vehicleType")
            ?: "—"

        // Helper name — from queue doc (sap_assignments has no helper field, only driverName for the driver)
        val resolvedHelper = helper ?: "—"

        binding.tvShipmentNumber.text = shipmentNumber
        binding.tvPlateNo.text = plateNumber
        binding.tvTruckType.text = resolvedTruckType
        binding.tvHelper.text = resolvedHelper

        // Detect completed trip — show all steps as done with green checkmarks
        val tripStatus = sapDoc.getString("tripStatus")
        if (tripStatus == "completed") {
            isTripStarted = false
            isTripEnded = true
        } else if (tripStatus == "returning_to_base") {
            isReturningToBase = true
            isTripStarted = false
            isTripEnded = false
        }

        Log.d(TAG, "Shipment UI populated: Shipment=$shipmentNumber Plate=$plateNumber Truck=$resolvedTruckType Helper=$resolvedHelper")
        // Always sync progress stepper after state is set — onCreate() calls this before async completes
        updateProgressStepper()
    }

    /**
     * Extracts a field from lineItems[0] with multiple possible field name variants.
     * Firestore uses ShipmentNumber (TitleCase), shipment_number (snake_case), shipmentNumber (camelCase).
     */
    private fun extractFromLineItems(sapDoc: DocumentSnapshot, vararg fieldNames: String): String? {
        val lineItems = sapDoc.get("lineItems") as? List<*>
        if (lineItems.isNullOrEmpty()) return null
        val first = lineItems.first() as? Map<*, *>
        if (first == null) {
            Log.d(TAG, "lineItems found but first item is not a map: ${lineItems.first()}")
            return null
        }
        for (field in fieldNames) {
            val value = first[field]
            Log.d(TAG, "  lineItems[0].$field = $value")
            (value as? String)?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return null
    }

    // ─── Location Updates (GMS with Android LocationManager fallback) ───────────────────────

    /** Emits driver_moving IMMEDIATELY using the last known GPS fix.
     * Called on "Start Trip" so sysu-app web sees the marker instantly —
     * without waiting for the first live GPS fix (which can take 5-30s).
     * Falls back silently if no last known location is available. */
    @SuppressLint("MissingPermission")
    private fun emitLastKnownLocation(sapDocId: String) {
        // Ensure socket is initialized so it can read driver_name from SharedPrefs
        SocketManager.init(this)

        try {
            val flp = GmsLocationServices.getFusedLocationProviderClient(this)
            flp.lastLocation.addOnSuccessListener { location ->
                if (location != null) {
                    sessionManager.saveLastEmit(location.latitude, location.longitude)
                    SocketManager.emitDriverMoving(
                        location.latitude,
                        location.longitude,
                        sapDocId,
                        "online"
                    )
                    Log.d(TAG, "[START EMIT] Immediate driver_moving: [${location.latitude}, ${location.longitude}], $sapDocId")
                    updateLocationUI(location.latitude, location.longitude)
                } else {
                    Log.d(TAG, "[START EMIT] No last known location — LocationServices will emit on first GPS fix")
                }
            }.addOnFailureListener { e ->
                Log.w(TAG, "[START EMIT] lastLocation failed: ${e.message}")
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "[START EMIT] Location permission not granted: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        // Start LocationServices foreground service (always works — different context)
        val serviceIntent = Intent(this, LocationServices::class.java)
        startForegroundService(serviceIntent)

        // Try GMS first; if it throws SecurityException (as seen in the logcat), fall back to Android LM
        try {
            fusedLocationClient = GmsLocationServices.getFusedLocationProviderClient(this)

            val locationRequest = LocationRequest.Builder(
                Priority.PRIORITY_HIGH_ACCURACY,
                5000
            ).setMinUpdateIntervalMillis(3000)
              .build()

            // Initial location fetch
            fusedLocationClient?.lastLocation?.addOnSuccessListener { location ->
                if (location != null) {
                    updateLocationUI(location.latitude, location.longitude)
                } else {
                    binding.tvLocationCoords.text = "Searching for GPS..."
                }
            }?.addOnFailureListener { e ->
                Log.w(TAG, "GMS lastLocation failed, falling back to LocationManager: ${e.message}")
                startAndroidLocationManager()
            }

            locationCallback = object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    for (location in result.locations) {
                        updateLocationUI(location.latitude, location.longitude)
                    }
                }
            }

            fusedLocationClient?.requestLocationUpdates(
                locationRequest,
                locationCallback!!,
                Looper.getMainLooper()
            )
        } catch (e: SecurityException) {
            Log.w(TAG, "GMS SecurityException — using Android LocationManager: ${e.message}")
            startAndroidLocationManager()
        } catch (e: Exception) {
            Log.w(TAG, "GMS failed unexpectedly, using Android LocationManager: ${e.message}")
            startAndroidLocationManager()
        }
    }

    /** Fallback: uses the platform LocationManager when GMS fails (e.g. broker permission error) */
    @SuppressLint("MissingPermission")
    private fun startAndroidLocationManager() {
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        androidLocationListener = object : LocationListener {
            override fun onLocationChanged(location: android.location.Location) {
                updateLocationUI(location.latitude, location.longitude)
            }
            @Deprecated("Deprecated in API 29")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }

        try {
            //noinspection MissingPermission
            lm.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                5000L,
                10f,
                androidLocationListener!!,
                Looper.getMainLooper()
            )

            // Get last known location attempt
            //noinspection MissingPermission
            val lastLoc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            if (lastLoc != null) {
                updateLocationUI(lastLoc.latitude, lastLoc.longitude)
            } else {
                //noinspection MissingPermission
                lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)?.let {
                    updateLocationUI(it.latitude, it.longitude)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "LocationManager also failed: ${e.message}")
            binding.tvLocationCoords.text = "Location unavailable"
        }
    }

    private fun updateLocationUI(lat: Double, lng: Double) {
        val formatted = String.format(Locale.US, "%.6f N, %.6f E", lat, lng)
        binding.tvLocationCoords.text = formatted
        Log.d(TAG, "Location updated: $formatted")
    }

    // ─── Circle Animation (copied from QueueDetailsActivity) ─────────────────

    private fun startCircleAnimation() {
        circleViews.forEach { binding.circleContainer.removeView(it) }
        circleViews.clear()
        circleAnimators.forEach { it.cancel() }
        circleAnimators.clear()

        binding.circleContainer.post {
            val containerW = binding.circleContainer.width
            val containerH = binding.circleContainer.height
            if (containerW == 0 || containerH == 0) return@post

            val random = java.util.Random()
            val count = random.nextInt(3) + 3

            for (i in 0 until count) {
                val sizeDp = 20 + random.nextInt(51)
                val sizePx = (sizeDp * resources.displayMetrics.density).toInt()
                val startX = random.nextInt(containerW).toFloat()
                val startY = random.nextInt(containerH).toFloat()
                val minSpeed = 0.2f + random.nextFloat() * 0.2f
                val maxSpeed = minSpeed + 0.2f + random.nextFloat() * 0.3f
                val alpha = 0.35f + random.nextFloat() * 0.35f

                val circle = View(this).apply {
                    layoutParams = FrameLayout.LayoutParams(sizePx, sizePx)
                    background = getDrawable(R.drawable.bg_circle_light_red)
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
        containerW: Int,
        containerH: Int,
        minSpeed: Float,
        maxSpeed: Float,
        startX: Float,
        startY: Float,
        delayMs: Long
    ) {
        var posX = startX
        var posY = startY
        var velX = minSpeed + java.util.Random().nextFloat() * (maxSpeed - minSpeed)
        var velY = minSpeed + java.util.Random().nextFloat() * (maxSpeed - minSpeed)

        val anim = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = Long.MAX_VALUE
            repeatCount = android.animation.ValueAnimator.INFINITE
            startDelay = delayMs
            addUpdateListener {
                posX += velX
                posY += velY

                if (posX <= 0 || posX >= containerW - viewSize) velX *= -1
                if (posY <= 0 || posY >= containerH - viewSize) velY *= -1

                val maxX = (containerW - viewSize).toFloat().coerceAtLeast(0f)
                val maxY = (containerH - viewSize).toFloat().coerceAtLeast(0f)
                posX = posX.coerceIn(0f, maxX)
                posY = posY.coerceIn(0f, maxY)

                view.x = posX
                view.y = posY
            }
        }
        anim.interpolator = null
        anim.start()

        circleAnimators.add(anim)
    }

    // ─── Trip Progress Stepper ──────────────────────────────────────────────

    /**
     * Updates the 3-step progress indicator (Verified → Transit → Done)
     * based on the current tripStatus.
     *
     * Trip states:
     *   - not started: Verified=GREEN(done), Transit=PENDING, Done=GREEN(done)
     *   - in transit:   Verified=GREEN(done), Transit=ACTIVE(red+check), Done=PENDING
     *   - completed:    Verified=GREEN(done), Transit=GREEN(done), Done=GREEN(done)
     */
    private fun updateProgressStepper() {
        // Completed trip — all three steps done, green checkmarks
        if (isTripEnded) {
            setStepState(R.id.dotQueue, null, R.id.checkQueue, R.id.labelQueue, StepState.DONE)
            setStepState(R.id.dotLoading, null, R.id.checkLoading, R.id.labelLoading, StepState.DONE)
            setStepState(R.id.dotTransit, null, R.id.checkTransit, R.id.labelTransit, StepState.DONE)
            binding.tvSubStatus.text = "Trip completed"
            binding.btnStartTrip.text = "START TRIP"
            binding.btnStartTrip.setBackgroundTintList(
                ContextCompat.getColorStateList(this@TransitActivity, R.color.sysu_red)
            )
            binding.btnStartTrip.isEnabled = true
            return
        }

        // Returning to base — show Verified done, Transit done, Done active
        if (isReturningToBase) {
            setStepState(R.id.dotQueue, null, R.id.checkQueue, R.id.labelQueue, StepState.DONE)
            setStepState(R.id.dotLoading, null, R.id.checkLoading, R.id.labelLoading, StepState.DONE)
            setStepState(R.id.dotTransit, null, R.id.checkTransit, R.id.labelTransit, StepState.DONE)
            binding.tvSubStatus.text = "ARRIVED AT WAREHOUSE"
            binding.btnStartTrip.text = "ARRIVED AT WAREHOUSE"
            binding.btnStartTrip.setBackgroundColor(ContextCompat.getColor(this@TransitActivity, R.color.sysu_blue))
            binding.btnStartTrip.isEnabled = true
            return
        }

        // In transit — Verified done, Transit active(in progress), Done pending
        if (isTripStarted) {
            if (isPodCompleteForAll) {
                setStepState(R.id.dotQueue, null, R.id.checkQueue, R.id.labelQueue, StepState.DONE)
                setStepState(R.id.dotLoading, null, R.id.checkLoading, R.id.labelLoading, StepState.DONE)
                setStepState(R.id.dotTransit, null, R.id.checkTransit, R.id.labelTransit, StepState.DONE)
                binding.tvSubStatus.text = "Ready to return"
                binding.btnStartTrip.text = "START RETURNING TO BASE"
                binding.btnStartTrip.setBackgroundColor(ContextCompat.getColor(this, R.color.sysu_blue))
                binding.btnStartTrip.isEnabled = true
                return
            }

            setStepState(R.id.dotQueue, null, R.id.checkQueue, R.id.labelQueue, StepState.DONE)
            setStepState(R.id.dotLoading, null, R.id.checkLoading, R.id.labelLoading, StepState.ACTIVE)
            setStepState(R.id.dotTransit, R.id.numTransit, R.id.checkTransit, R.id.labelTransit, StepState.PENDING)
            binding.tvSubStatus.text = "Trip in progress"
            binding.btnStartTrip.text = "BACK TO HOME"
            binding.btnStartTrip.setBackgroundColor(ContextCompat.getColor(this@TransitActivity, R.color.transit_green))
            binding.btnStartTrip.isEnabled = true
            return
        }

        // Not yet started — Verified done (already signed by guard), Transit/Done pending
        setStepState(R.id.dotQueue, null, R.id.checkQueue, R.id.labelQueue, StepState.DONE)
        setStepState(R.id.dotLoading, R.id.numLoading, R.id.checkLoading, R.id.labelLoading, StepState.PENDING)
        setStepState(R.id.dotTransit, R.id.numTransit, R.id.checkTransit, R.id.labelTransit, StepState.PENDING)
        binding.tvSubStatus.text = "Ready to start"
        binding.btnStartTrip.text = "START TRIP"
        binding.btnStartTrip.setBackgroundTintList(ContextCompat.getColorStateList(this@TransitActivity, R.color.sysu_red))
        binding.btnStartTrip.isEnabled = true
    }

    /**
     * Updates the tracking state indicator dot in the header bar and optionally
     * the subtitle prefix based on current tracking state (online/stopped/offline).
     */
    private fun updateTrackingStateUI(state: TrackingStateMonitor.State) {
        val (dotDrawable, dotTint, subtitlePrefix) = when (state) {
            TrackingStateMonitor.State.ONLINE -> Triple(
                R.drawable.bg_status_dot_green,
                ContextCompat.getColor(this, R.color.sysu_green),
                "In transit"
            )
            TrackingStateMonitor.State.STOPPED -> Triple(
                R.drawable.bg_status_dot_gold,
                ContextCompat.getColor(this, R.color.sysu_gold),
                "Stop"
            )
            TrackingStateMonitor.State.OFFLINE -> Triple(
                R.drawable.bg_status_dot_red,
                ContextCompat.getColor(this, R.color.sysu_red),
                "Offline"
            )
        }
        binding.trackingStateDot.setBackgroundResource(dotDrawable)
        binding.trackingStateDot.backgroundTintList = android.content.res.ColorStateList.valueOf(dotTint)
        // OFFLINE changes both subtitle text and color to red.
        // STOPPED shows subtitle as "Stop" with secondary color (yellow dot is enough signal).
        binding.tvHeaderSubtitle.text = subtitlePrefix
        if (state == TrackingStateMonitor.State.OFFLINE) {
            binding.tvHeaderSubtitle.setTextColor(ContextCompat.getColor(this, R.color.sysu_red))
        } else {
            binding.tvHeaderSubtitle.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        }
    }

    private enum class StepState { DONE, ACTIVE, PENDING }

    private fun setStepState(
        dotIdDone: Int,
        numIdDone: Int?,
        checkIdDone: Int,
        labelIdDone: Int,
        state: StepState
    ) {
        val ctx = this
        val dot = binding.root.findViewById<View>(dotIdDone)
        val check = binding.root.findViewById<TextView?>(checkIdDone)
        val num = numIdDone?.let { binding.root.findViewById<TextView?>(it) }
        val label = binding.root.findViewById<TextView>(labelIdDone)

        when (state) {
            StepState.DONE -> {
                dot.setBackgroundResource(R.drawable.bg_progress_step_done)
                check?.visibility = View.VISIBLE
                num?.visibility = View.GONE
                label.setTextColor(ContextCompat.getColor(ctx, R.color.transit_green))
            }
            StepState.ACTIVE -> {
                dot.setBackgroundResource(R.drawable.bg_progress_step_active)
                check?.visibility = View.GONE
                num?.visibility = View.GONE
                label.setTextColor(ContextCompat.getColor(ctx, R.color.sysu_red))
            }
            StepState.PENDING -> {
                dot.setBackgroundResource(R.drawable.bg_progress_step_pending)
                check?.visibility = View.GONE
                num?.visibility = View.VISIBLE
                label.setTextColor(ContextCompat.getColor(ctx, R.color.text_muted))
            }
        }
    }

    // ─── Time Formatting ─────────────────────────────────────────────────────

    private fun formatTimestamp(ts: Timestamp): String {
        val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
        return sdf.format(ts.toDate())
    }

    // ─── Back navigation — route to MainActivity instead of HOME during session restore ───
    // HARD KILL scenario: SplashActivity routes to TransitActivity as new task root.
    // No MainActivity in back stack. System back / toolbar up → would go to HOME.
    // Intercept both and push MainActivity onto stack first.
    private fun navigateToMainActivity() {
        Log.d(TAG, "[BACK] Session restore — pushing MainActivity onto stack")
        val intent = Intent(this, MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
        startActivity(intent)
        finish()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (isSessionRestored) {
            navigateToMainActivity()
            return
        }
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home && isSessionRestored) {
            navigateToMainActivity()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    // ─── Cleanup ───────────────────────────────────────────────────────────

    // ─── Connection Monitor for offline popup ───────────────────────────────

    private fun registerConnectionMonitor() {
        connectionReceiver?.let { return }
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
                        sessionManager.getDriverName() ?: "Driver"
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

    override fun onDestroy() {
        unregisterConnectionMonitor()
        TrackingStateMonitor.removeListener(trackingStateListener)
        circleAnimators.forEach { it.cancel() }
        circleAnimators.clear()
        // Use iterator with exception handling to prevent ConcurrentModificationException
        // if an animation callback fires during tear-down.
        val viewsToRemove = circleViews.toList()
        circleViews.clear()
        for (v in viewsToRemove) {
            try { binding.circleContainer.removeView(v) } catch (_: Exception) {}
        }

        // Clean up socket connect listener to prevent leak on activity recreate
        socketConnectListener?.let {
            SocketManager.removeOnConnectListener(it)
            socketConnectListener = null
        }

        // Remove location updates from our clients before nulling callbacks.
        // Capture references inside synchronized, then remove from outside.
        var cb: LocationCallback? = null
        var listener: LocationListener? = null
        synchronized(this) {
            cb = locationCallback
            listener = androidLocationListener
            locationCallback = null
            androidLocationListener = null
        }
        cb?.let {
            try { fusedLocationClient?.removeLocationUpdates(it) } catch (_: Exception) {}
        }
        listener?.let {
            try {
                val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
                lm.removeUpdates(it)
            } catch (_: Exception) {}
        }
        activityScope.cancel()
        super.onDestroy()
    }
}