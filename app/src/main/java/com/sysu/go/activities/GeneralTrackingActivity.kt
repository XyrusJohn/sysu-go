package com.sysu.go.activities

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.sysu.go.R
import com.sysu.go.model.Assignment
import com.sysu.go.network.SocketManager
import com.sysu.go.utils.SessionManager

private const val TAG = "GENERAL_TRACKING_MANUAL"

class GeneralTrackingActivity : AppCompatActivity() {

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback

    private lateinit var btnStartTracking: MaterialButton
    private lateinit var btnEndTracking: MaterialButton
    private lateinit var btnSelectActiveTrip: MaterialButton

    private lateinit var tvTrackingStatus: TextView
    private lateinit var tvCurrentLocation: TextView
    private lateinit var tvSocketStatus: TextView

    // ═══ TELEMETRY & NETWORK VARIABLES ═══
    private lateinit var dotGpsSignal: View
    private lateinit var tvGpsAccuracy: TextView
    private lateinit var dotNetworkStatus: View
    private lateinit var tvNetworkStatus: TextView
    private lateinit var connectivityManager: ConnectivityManager

    private var isTrackingStarted = false
    private var pendingStart = false

    // Network Callback para mag-trigger kapag nawalan ng internet
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: android.net.Network) {
            runOnUiThread { updateNetworkStatus(true) }
        }
        override fun onLost(network: android.net.Network) {
            runOnUiThread {
                updateNetworkStatus(false)
                if (isTrackingStarted) showNetworkLostSnackbar()
            }
        }
    }

    // Firestore instance
    private val db = FirebaseFirestore.getInstance()

    // Bouncing Circles
    private val circleAnimators = mutableListOf<android.animation.Animator>()
    private val circleViews = mutableListOf<View>()

    // Shipments state
    private var allAssignments: List<Assignment> = emptyList()
    private var readyToStartShipments: List<Assignment> = emptyList()
    private var isActiveShipmentDelivered = false
    private var allAssignedShipmentsCompleted = false
    private var shipmentsListener: ListenerRegistration? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        Log.d(TAG, "GeneralTrackingActivity created")
        setContentView(R.layout.activity_general_tracking)

        // Initialize views
        btnStartTracking = findViewById(R.id.btnStartTracking)
        btnEndTracking = findViewById(R.id.btnEndTracking)
        btnSelectActiveTrip = findViewById(R.id.btnSelectActiveTrip)
        tvTrackingStatus = findViewById(R.id.tvTrackingStatus)
        tvCurrentLocation = findViewById(R.id.tvCurrentLocation)
        tvSocketStatus = findViewById(R.id.tvSocketStatus)

        // Initialize Telemetry Views
        dotGpsSignal = findViewById(R.id.dotGpsSignal)
        tvGpsAccuracy = findViewById(R.id.tvGpsAccuracy)
        dotNetworkStatus = findViewById(R.id.dotNetworkStatus)
        tvNetworkStatus = findViewById(R.id.tvNetworkStatus)

        // Initialize FusedLocationProviderClient
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        // Initialize Network Monitoring
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            connectivityManager.registerDefaultNetworkCallback(networkCallback)
        }
        updateNetworkStatus(isNetworkAvailable())

        // Pre-flight check: Alert user immediately if GPS is OFF
        checkGpsEnabled()

        // Start bouncing circles animation
        startCircleAnimation()

        // Sync initial tracking status from session
        isTrackingStarted = SessionManager(this).isTrackingActive()

        // Initialize UI states
        updateTrackingStatus()
        updateSocketStatus()
        tvCurrentLocation.text = "Location: Waiting for GPS..."
        tvCurrentLocation.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))

        // Set up socket connection status listeners — must dispatch to main thread
        // because Socket.IO callbacks fire on its own EventThread (non-UI thread).
        SocketManager.addOnConnectListener { runOnUiThread { updateSocketStatus() } }
        SocketManager.addEventListener("disconnect") { runOnUiThread { updateSocketStatus() } }

        // Location callback with Telemetry integration
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let {
                    Log.d(TAG, "[GPS] Location received: ${it.latitude}, ${it.longitude}, accuracy: ${it.accuracy}")
                    runOnUiThread {
                        tvCurrentLocation.text = "Location: ${String.format("%.6f", it.latitude)}, ${String.format("%.6f", it.longitude)}"
                        tvCurrentLocation.setTextColor(ContextCompat.getColor(this@GeneralTrackingActivity, R.color.sysu_red))

                        // Update GPS Telemetry based on accuracy (meters)
                        updateGpsSignal(it.accuracy)
                    }

                    pushLocationUpdate(it.latitude, it.longitude)
                }
            }
        }

        // Set up button click listeners
        btnEndTracking.setOnClickListener { endTrip() }

        // Back button
        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }

        // Check location permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 1001)
        }
    }

    // Iniisa ang emit (Socket.IO) + dual-write (Firestore) logic para magamit
    // parehas ng periodic locationCallback AT ng "push agad" sa startTracking().
    private fun pushLocationUpdate(lat: Double, lng: Double) {
        if (!isTrackingStarted) return

        val session = SessionManager(this)

        // Guard Clause: Halt if session was already closed by BottomSheet or other process
        if (!session.isTrackingActive()) {
            Log.w(TAG, "[GPS] Ghost tick detected! Session is inactive. Stopping tracker.")
            stopTracking()
            return
        }

        val sapDocId = session.getActiveSapDocId()
        val currentUid = FirebaseAuth.getInstance().currentUser?.uid ?: session.getDriverId()

        if (currentUid.isNullOrBlank()) return
        val driverName = session.getDriverName() ?: "Driver"

        // 1. Socket.IO: emit driver_moving (ito ang iniintay ng server.js para mag-emit ng update_admin_map sa overview map!)
        SocketManager.emitDriverMoving(lat, lng, sapDocId, status = "online")
        Log.d(TAG, "[SOCKET] Emitted driver_moving: lat=$lat, lng=$lng, sapDocId=$sapDocId")

        // 2. Firestore: dual-write sa driver_locations para sa web overview map na naka-Firestore listener
        val locData = hashMapOf(
            "driverId" to currentUid,
            "name" to driverName,
            "lat" to lat,
            "lng" to lng,
            "status" to "online",
            "sapDocId" to sapDocId,
            "updatedAt" to com.google.firebase.firestore.FieldValue.serverTimestamp()
        )
        db.collection("driver_locations")
            .document(currentUid)
            .set(locData, com.google.firebase.firestore.SetOptions.merge())

        if (!sapDocId.isNullOrBlank()) {
            db.collection("sap_assignments")
                .document(sapDocId)
                .update(
                    mapOf(
                        "lastLocation.lat" to lat,
                        "lastLocation.lng" to lng,
                        "lastLocation.at" to com.google.firebase.firestore.FieldValue.serverTimestamp()
                    )
                )
        }
    }

    // Kunin agad ang pinaka-fresh na fix (hindi lang basta cached lastLocation, kasi
    // pwedeng null/stale yun kung matagal nang hindi na-touch ang GPS ng OS) at i-push
    // kaagad sa socket + Firestore. Ito yung "instant on click" na location bago pa man
    // dumating yung unang periodic tick ng locationCallback — dahil dun sa dating code,
    // kailangan pa munang gumalaw ng driver bago mag-fire ang unang onLocationResult,
    // kaya blangko muna sa overview map hanggang sa gumalaw siya.
    @Suppress("MissingPermission")
    private fun pushImmediateLocation() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) return

        val cts = com.google.android.gms.tasks.CancellationTokenSource()
        fusedLocationClient.getCurrentLocation(
            com.google.android.gms.location.Priority.PRIORITY_HIGH_ACCURACY,
            cts.token
        ).addOnSuccessListener { location ->
            if (location == null) {
                Log.w(TAG, "[GPS] getCurrentLocation returned null, waiting for first periodic tick instead")
                return@addOnSuccessListener
            }
            Log.d(TAG, "[GPS] Immediate fix on Start Tracking: ${location.latitude}, ${location.longitude}")
            runOnUiThread {
                tvCurrentLocation.text = "Location: ${String.format("%.6f", location.latitude)}, ${String.format("%.6f", location.longitude)}"
                tvCurrentLocation.setTextColor(ContextCompat.getColor(this, R.color.sysu_red))
                updateGpsSignal(location.accuracy)
            }
            pushLocationUpdate(location.latitude, location.longitude)
        }.addOnFailureListener { e ->
            Log.w(TAG, "[GPS] Immediate fix failed: ${e.message}, waiting for first periodic tick instead")
        }
    }

    private fun startLocationUpdates() {
        Log.d(TAG, "[GPS] Starting location updates")
        val locationRequest = LocationRequest.create().apply {
            interval = 5000
            fastestInterval = 2000
            priority = LocationRequest.PRIORITY_HIGH_ACCURACY
        }
        fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
        Log.d(TAG, "[GPS] Location updates started")
    }

    private fun stopLocationUpdates() {
        Log.d(TAG, "[GPS] Stopping location updates")
        fusedLocationClient.removeLocationUpdates(locationCallback)
        Log.d(TAG, "[GPS] Location updates stopped")
    }

    private fun startTracking() {
        val session = SessionManager(this)
        val activeDocId = session.getActiveSapDocId()
        if (isTrackingStarted || !session.isTripActive() || activeDocId.isNullOrEmpty()) return
        Log.d(TAG, "[TRACKING_BUTTON] 🟢 START Tracking button clicked by user")

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            session.setTrackingActive(true)
            isTrackingStarted = true

            SocketManager.disconnect()
            SocketManager.connect(this)
            Log.d(TAG, "[SOCKET] Reconnecting socket before starting location updates...")

            startLocationUpdates()

            // Huwag hintayin ang unang periodic tick (na kailangan pa munang gumalaw
            // ang driver) — kumuha agad ng fresh fix at i-push kaagad papuntang
            // overview map sa mismong sandaling na-click ang "Start Tracking".
            pushImmediateLocation()

            val serviceIntent = Intent(this, com.sysu.go.services.LocationServices::class.java)
            ContextCompat.startForegroundService(this, serviceIntent)
            Log.d(TAG, "[SERVICE] LocationServices successfully restarted")

            updateTrackingStatus()
            Log.d(TAG, "[TRACKING] trackingActive set to true & Socket reconnect initiated")
        } else {
            pendingStart = true
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 1001)
        }
    }

    private fun endTrip() {
        // NOTE: Intentionally NOT gated by isTrackingStarted — driver must be able to
        // trigger Return to Base even if tracking was accidentally stopped or session lost.
        val isReturningToBase = allAssignments.any { it.tripStatus == "returning_to_base" }
        if (!allAssignedShipmentsCompleted && !isReturningToBase) {
            Toast.makeText(this, "Complete all PODs for all shipments before ending trip.", Toast.LENGTH_SHORT).show()
            return
        }
        Log.d(TAG, "[TRACKING_BUTTON] 🔵 END Trip button clicked -> Launching ReturnToBaseBottomSheet (isReturning=$isReturningToBase)")
        showReturnToBaseSheet(if (isReturningToBase) "returning_to_base" else null)
    }

    private fun showReturnToBaseSheet(returnStatus: String? = null) {
        val docIds = allAssignments.joinToString(",") { it.docId }
        val bottomSheet = ReturnToBaseBottomSheet.newInstance(
            shipmentDocIds = docIds,
            currentReturnStatus = returnStatus
        )
        bottomSheet.show(supportFragmentManager, "ReturnToBaseBottomSheet")
    }

    private fun stopTracking() {
        if (!isTrackingStarted) return
        Log.d(TAG, "[TRACKING_BUTTON] 🔴 STOP Tracking button clicked by user")

        stopLocationUpdates()
        val session = SessionManager(this)
        // Only pause tracking — intentionally NOT clearing isTripActive or activeDocId.
        // This preserves the trip session so the driver can still trigger End Trip
        // even after stopping GPS updates (e.g. if accidentally stopped or app paused).
        session.setTrackingActive(false)

        isTrackingStarted = false
        pendingStart = false

        val driverId = FirebaseAuth.getInstance().currentUser?.uid ?: session.getDriverId()
        val driverName = session.getDriverName()
        if (!driverId.isNullOrBlank()) {
            SocketManager.emit("remove_marker", driverId)
            SocketManager.emitOfflineStatus(driverId, driverName)
            Log.d(TAG, "[STATUS] Emitted remove_marker and offline status to server for $driverId")

            // Linisin din ang Firestore driver_locations para walang maiwang ghost marker
            db.collection("driver_locations").document(driverId).delete()
        }

        SocketManager.disconnect()
        Log.d(TAG, "[SOCKET] Disconnected from server")

        stopService(Intent(this, com.sysu.go.services.LocationServices::class.java))
        Log.d(TAG, "[SERVICE] LocationServices stopped")

        tvCurrentLocation.text = "Location: Waiting for GPS..."
        tvCurrentLocation.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))

        loadShipmentsData()
    }

    private fun updateTrackingStatus() {
        val session = SessionManager(this)
        val isTripActive = session.isTripActive()
        val activeDocId = session.getActiveSapDocId()
        val isReturningToBase = allAssignments.any { it.tripStatus == "returning_to_base" }

        // ═══ ENTERPRISE GATE: Lock start button if no trip is selected ═══
        val canStartTracking = isTripActive && !activeDocId.isNullOrEmpty()
        btnStartTracking.isEnabled = canStartTracking
        if (!canStartTracking) {
            btnStartTracking.alpha = 0.4f
            btnStartTracking.text = if (isReturningToBase) "Returning to Base" else "Select Trip First"
            btnStartTracking.backgroundTintList = ContextCompat.getColorStateList(this, R.color.sysu_red)
            btnStartTracking.setOnClickListener { /* Do nothing */ }
        } else {
            btnStartTracking.alpha = 1.0f
            if (isTrackingStarted) {
                // Gawing STOP button kapag nagta-track na
                btnStartTracking.text = "Stop Tracking"
                btnStartTracking.backgroundTintList = ContextCompat.getColorStateList(this, R.color.sysu_amber)
                btnStartTracking.setOnClickListener { stopTracking() }
            } else {
                // Gawing START button kapag naka-pause
                btnStartTracking.text = "Start Tracking"
                btnStartTracking.backgroundTintList = ContextCompat.getColorStateList(this, R.color.sysu_red)
                btnStartTracking.setOnClickListener { startTracking() }
            }
        }
        btnStartTracking.setTextColor(ContextCompat.getColor(this, R.color.white))

        // ═══ END TRIP GATE: btnEndTracking ═══
        // Intentionally NOT requiring isTrackingStarted — driver must still be able to
        // trigger Return to Base even if GPS tracking was paused or session was lost.
        val canEndTrip = allAssignedShipmentsCompleted || isReturningToBase
        btnEndTracking.isEnabled = canEndTrip
        btnEndTracking.alpha = if (canEndTrip) 1.0f else 0.4f
        val endTripColor = if (isReturningToBase) R.color.sysu_blue else R.color.sysu_amber
        btnEndTracking.text = if (isReturningToBase) "Return to Base" else "End Trip"
        btnEndTracking.strokeColor = ContextCompat.getColorStateList(
            this, if (canEndTrip) endTripColor else R.color.border
        )
        btnEndTracking.setTextColor(
            ContextCompat.getColor(this, if (canEndTrip) endTripColor else R.color.text_secondary)
        )

        // ═══ HEADER IDENTIFIER ═══
        val statusText = if (isTrackingStarted) "ON" else "OFF"
        val tripIdentifier = when {
            isReturningToBase -> " | Returning to Base"
            isTripActive && !activeDocId.isNullOrEmpty() -> " | Active: Trip #$activeDocId"
            else -> " | No Active Trip"
        }

        tvTrackingStatus.text = "Tracking: $statusText$tripIdentifier"
        tvTrackingStatus.setTextColor(ContextCompat.getColor(this, R.color.white))
    }

    private fun updateSocketStatus() {
        val isConnected = SocketManager.isConnected()
        val statusText = if (isConnected) "Socket: Connected" else "Socket: Disconnected"
        val statusColor = if (isConnected) R.color.transit_green else R.color.text_secondary

        tvSocketStatus.text = statusText
        tvSocketStatus.setTextColor(ContextCompat.getColor(this, statusColor))
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1001) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                if (pendingStart) {
                    pendingStart = false
                    startTracking()
                } else if (isTrackingStarted) {
                    startLocationUpdates()
                    updateSocketStatus()
                }
            } else {
                tvTrackingStatus.text = "Location permission denied"
                tvTrackingStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark))
                if (pendingStart) pendingStart = false
            }
        }
    }

    override fun onResume() {
        super.onResume()
        isTrackingStarted = SessionManager(this).isTrackingActive()
        updateTrackingStatus()
        updateSocketStatus()
        loadShipmentsData()

        // Re-attach the UI location listener if tracking is already active.
        // onPause() detaches it to save battery; onResume() must re-attach it
        // so the screen stops showing "Waiting for GPS..." after coming back.
        // The background LocationServices service keeps running regardless.
        if (isTrackingStarted &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
            startLocationUpdates()
            Log.d(TAG, "[RESUME] Reattached UI location listener (tracking was active)")
        }
    }

    override fun onPause() {
        super.onPause()
        // Stop UI-only location updates when screen is paused to conserve resources.
        // The background LocationServices foreground service continues tracking seamlessly.
        stopLocationUpdates()
    }

    override fun onDestroy() {
        Log.d(TAG, "[TRACKING] GeneralTrackingActivity onDestroy called")
        stopCircleAnimations()
        SocketManager.removeOnConnectListener { updateSocketStatus() }
        SocketManager.removeEventListener("disconnect") { updateSocketStatus() }
        shipmentsListener?.remove()

        // Unregister network callback to prevent memory leaks
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                connectivityManager.unregisterNetworkCallback(networkCallback)
            } catch (e: Exception) {
                Log.e(TAG, "Error unregistering network callback: ${e.message}")
            }
        }

        super.onDestroy()
        stopLocationUpdates()
        // Note: Do NOT set trackingActive to false here.
        // Background tracking is managed by LocationServices and persists even when exiting this screen.
    }

    // ═════════════════════════════════════════════════════════════════
    // TELEMETRY & SMART ALERTS HELPERS
    // ════════════════════════════════════════════════════════════════
    private fun isNetworkAvailable(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        @Suppress("DEPRECATION")
        val activeNetwork = cm.activeNetworkInfo
        return activeNetwork != null && activeNetwork.isConnected
    }

    private fun updateNetworkStatus(isOnline: Boolean) {
        val statusText = if (isOnline) "Network: Online" else "Network: Offline"
        val statusColor = if (isOnline) R.color.transit_green else R.color.sysu_red
        tvNetworkStatus.text = statusText
        tvNetworkStatus.setTextColor(ContextCompat.getColor(this, statusColor))
        dotNetworkStatus.backgroundTintList = ContextCompat.getColorStateList(this, statusColor)
    }

    private fun updateGpsSignal(accuracy: Float) {
        val (statusText, statusColor) = when {
            accuracy < 10f -> "GPS: Excellent (${accuracy.toInt()}m)" to R.color.transit_green
            accuracy < 30f -> "GPS: Good (${accuracy.toInt()}m)" to R.color.transit_green
            accuracy < 50f -> "GPS: Weak (${accuracy.toInt()}m)" to R.color.sysu_amber
            else -> "GPS: Poor (${accuracy.toInt()}m)" to R.color.sysu_red
        }
        tvGpsAccuracy.text = statusText
        tvGpsAccuracy.setTextColor(ContextCompat.getColor(this, statusColor))
        dotGpsSignal.backgroundTintList = ContextCompat.getColorStateList(this, statusColor)
    }

    private fun checkGpsEnabled() {
        val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val isGpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        if (!isGpsEnabled) {
            showGpsDisabledDialog()
        }
    }

    private fun showGpsDisabledDialog() {
        android.app.AlertDialog.Builder(this)
            .setTitle("📍 GPS Disabled")
            .setMessage("Mahina o walang GPS signal. Pakibukas ang Location Settings para mag-tuloy ang tracking.")
            .setPositiveButton("Buksan ang Settings") { _, _ ->
                startActivity(Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            }
            .setNegativeButton("Kalaunan", null)
            .show()
    }

    private fun showNetworkLostSnackbar() {
        val snackbar = Snackbar.make(
            findViewById(R.id.cardLocation),
            "⚠️ Nawalan ng internet connection. Ang tracking data ay naka-queue.",
            Snackbar.LENGTH_LONG
        )
        snackbar.setBackgroundTint(ContextCompat.getColor(this, R.color.sysu_red))
        snackbar.setTextColor(ContextCompat.getColor(this, R.color.white))
        snackbar.show()
    }

    // ═════════════════════════════════════════════════════════════════
    // TRIP ASSIGNMENT & POD CHECK LOGIC
    // ════════════════════════════════════════════════════════════════
    private fun loadShipmentsData() {
        val driverId = SessionManager(this).getDriverId() ?: return
        shipmentsListener?.remove()

        shipmentsListener = db.collection("sap_assignments")
            .whereEqualTo("driverId", driverId)
            .addSnapshotListener { snaps, error ->
                if (error != null || snaps == null) return@addSnapshotListener
                val allList = snaps.documents.mapNotNull { buildAssignmentFromSnap(it) }
                allAssignments = allList

                val session = SessionManager(this)
                val currentActiveDocId = session.getActiveSapDocId()

                // Source of truth for locking = Firestore tripStatus (in_transit or returning_to_base)
                val inTransitAssignment = allList.firstOrNull { it.tripStatus == "in_transit" || it.tripStatus == "returning_to_base" }
                if (inTransitAssignment != null) {
                    session.saveActiveSapDocId(inTransitAssignment.docId)
                    session.setTripActive(true)
                } else if (!currentActiveDocId.isNullOrEmpty() || isTrackingStarted) {
                    // Stale session cleanup
                    session.saveActiveSapDocId(null)
                    session.setTripActive(false)
                    stopTracking()
                }

                readyToStartShipments = allList.filter { it.tripStatus == "loaded" && it.driverConfirmed }

                val activeDocId = session.getActiveSapDocId()
                val activeAssign = allList.firstOrNull { it.docId == activeDocId }
                isActiveShipmentDelivered = activeAssign != null && isAssignmentPodComplete(activeAssign)

                allAssignedShipmentsCompleted = allList.isNotEmpty() && allList.all {
                    it.tripStatus == "completed" || it.tripStatus == "returning_to_base" || isAssignmentPodComplete(it)
                }

                updateTrackingStatus()
                updateFloatingBottomBar()
            }
    }

    private fun isAssignmentPodComplete(assignment: Assignment): Boolean {
        if (assignment.lineItems.isEmpty()) return false
        return assignment.lineItems.all { (it["isPodCompleted"] as? Boolean) == true }
    }

    private fun buildAssignmentFromSnap(doc: DocumentSnapshot): Assignment? {
        val queueId = doc.getString("driverId") ?: ""
        val tripStatus = doc.getString("tripStatus")
        val guardVerified = doc.getBoolean("guardVerified") ?: false
        val driverConfirmed = doc.getBoolean("driverConfirmed") ?: false
        val guardFirstName = doc.getString("guardFirstName") ?: ""
        val guardLastName = doc.getString("guardLastName") ?: ""
        val guardSignature = doc.getString("guardSignature") ?: ""
        val shipmentNumberRaw = doc.getString("shipmentNumber") ?: ""
        val lineItems = doc.get("lineItems") as? List<*> ?: emptyList<Any>()
        val firstItem = lineItems.filterIsInstance<Map<String, Any?>>().firstOrNull()
        val delTsRaw = firstItem?.get("actualDelDate") as? String
        val deliveryTs = try {
            delTsRaw?.removePrefix("/Date(")?.removeSuffix(")/")?.toLong() ?: 0L
        } catch (e: Exception) { 0L }
        val obdCount = lineItems.filterIsInstance<Map<String, Any?>>().size
        val obdNumber = firstItem?.get("obdNumber") as? String ?: ""

        return Assignment(
            docId = doc.id,
            queueId = queueId,
            driverId = SessionManager(this).getDriverId() ?: "",
            status = doc.getString("status") ?: "",
            tripStatus = tripStatus,
            plateNumber = doc.getString("truckPlate") ?: "",
            truckType = doc.getString("truckCompany") ?: "",
            helper = doc.getString("helperName") ?: "",
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
    }

    private fun updateFloatingBottomBar() {
        val session = SessionManager(this)
        val isTripActive = session.isTripActive()
        val activeDocId = session.getActiveSapDocId()
        val isReturningToBase = allAssignments.any { it.tripStatus == "returning_to_base" }
        val floatingBar = findViewById<LinearLayout>(R.id.floatingBottomBar)
        val tvBatchStatus = findViewById<TextView>(R.id.tvBatchStatus)

        when {
            // ── State 0: Returning to Base ──
            isReturningToBase -> {
                floatingBar.visibility = View.VISIBLE
                tvBatchStatus.text = "Heading to Warehouse"
                btnSelectActiveTrip.isEnabled = true
                btnSelectActiveTrip.text = "View Return Panel"
                btnSelectActiveTrip.backgroundTintList = ContextCompat.getColorStateList(this, R.color.sysu_blue)
                btnSelectActiveTrip.setOnClickListener { showReturnToBaseSheet("returning_to_base") }
            }

            // ── State 1: Active trip in_transit, PODs incomplete ──
            isTripActive && !activeDocId.isNullOrEmpty() && !isActiveShipmentDelivered -> {
                floatingBar.visibility = View.VISIBLE
                tvBatchStatus.text = "Trip in Progress"
                btnSelectActiveTrip.isEnabled = true
                btnSelectActiveTrip.text = "View Active Shipment"
                btnSelectActiveTrip.backgroundTintList = ContextCompat.getColorStateList(this, R.color.transit_green)
                btnSelectActiveTrip.setOnClickListener {
                    val intent = Intent(this@GeneralTrackingActivity, QueueDetailsActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                        putExtra("shipment_doc_id", activeDocId)
                    }
                    startActivity(intent)
                }
            }

            // ── State 2: Active shipment fully POD'd (Delivered) ──
            isTripActive && !activeDocId.isNullOrEmpty() && isActiveShipmentDelivered -> {
                floatingBar.visibility = View.VISIBLE
                if (readyToStartShipments.isNotEmpty()) {
                    // Selection of remaining loaded shipments re-enables!
                    tvBatchStatus.text = "Trip #$activeDocId Delivered"
                    btnSelectActiveTrip.isEnabled = true
                    btnSelectActiveTrip.text = "Piliin ang Active Trip"
                    btnSelectActiveTrip.backgroundTintList = ContextCompat.getColorStateList(this, R.color.sysu_red)
                    btnSelectActiveTrip.setOnClickListener {
                        SelectActiveTripBottomSheet.newInstance(readyToStartShipments).apply {
                            onTripSelected = { selectedAssignment -> performStartTrip(selectedAssignment) }
                        }.show(supportFragmentManager, SelectActiveTripBottomSheet.TAG)
                    }
                } else {
                    // All shipments delivered
                    tvBatchStatus.text = "All Deliveries Completed"
                    btnSelectActiveTrip.isEnabled = false
                    btnSelectActiveTrip.text = "Delivered"
                    btnSelectActiveTrip.backgroundTintList = ContextCompat.getColorStateList(this, R.color.transit_green)
                    btnSelectActiveTrip.setOnClickListener { }
                }
            }

            // ── State 3: No active trip, loaded+confirmed shipments exist ──
            readyToStartShipments.isNotEmpty() -> {
                floatingBar.visibility = View.VISIBLE
                tvBatchStatus.text = "Cannot Start trip without Selecting a Customer"
                btnSelectActiveTrip.isEnabled = true
                btnSelectActiveTrip.text = "Piliin ang Active Trip"
                btnSelectActiveTrip.backgroundTintList = ContextCompat.getColorStateList(this, R.color.sysu_red)
                btnSelectActiveTrip.setOnClickListener {
                    SelectActiveTripBottomSheet.newInstance(readyToStartShipments).apply {
                        onTripSelected = { selectedAssignment -> performStartTrip(selectedAssignment) }
                    }.show(supportFragmentManager, SelectActiveTripBottomSheet.TAG)
                }
            }

            // ── State 4: Nothing ──
            else -> {
                floatingBar.visibility = View.GONE
                btnSelectActiveTrip.setOnClickListener { }
            }
        }
    }

    private fun performStartTrip(assignment: Assignment) {
        val session = SessionManager(this)
        val prevDocId = session.getActiveSapDocId()
        val nextDocId = assignment.docId

        val batch = db.batch()

        // Rule E3: If previous shipment was active and different, write tripStatus=completed + completedAt
        if (!prevDocId.isNullOrEmpty() && prevDocId != nextDocId) {
            val prevRef = db.collection("sap_assignments").document(prevDocId)
            batch.update(prevRef, mapOf(
                "tripStatus" to "completed",
                "completedAt" to FieldValue.serverTimestamp()
            ))
        }

        // Rule E2: Write tripStatus=in_transit + startedAt for the selected shipment
        val nextRef = db.collection("sap_assignments").document(nextDocId)
        batch.update(nextRef, mapOf(
            "tripStatus" to "in_transit",
            "startedAt" to FieldValue.serverTimestamp()
        ))

        batch.commit()
            .addOnSuccessListener {
                session.saveActiveSapDocId(nextDocId)
                session.saveCurrentSapDocId(nextDocId)
                session.setTripActive(true)

                loadShipmentsData()

                Toast.makeText(this, "Trip selected! You can now start tracking.", Toast.LENGTH_SHORT).show()
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to start trip: ${e.message}")
                Toast.makeText(this, "Failed to start trip. Check connection.", Toast.LENGTH_SHORT).show()
            }
    }

    // ═════════════════════════════════════════════════════════════════
    // BOUNCING CIRCLES ANIMATION
    // ════════════════════════════════════════════════════════════════
    private fun startCircleAnimation() {
        val circleContainer = findViewById<FrameLayout>(R.id.circleContainer) ?: return
        circleContainer.post {
            val containerW = circleContainer.width
            val containerH = circleContainer.height
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

                val circle = createBounceCircle(sizePx, alpha)
                circleContainer.addView(circle)
                circleViews.add(circle)

                startBounceAnimation(
                    view = circle, viewSize = sizePx, containerWidth = containerW, containerHeight = containerH,
                    minSpeed = minSpeed, maxSpeed = maxSpeed, initialX = startX, initialY = startY,
                    startDelayMs = (i * 150L) + random.nextInt(500)
                )
            }
        }
    }

    private fun createBounceCircle(sizePx: Int, alpha: Float): View {
        return View(this).apply {
            layoutParams = FrameLayout.LayoutParams(sizePx, sizePx)
            background = ContextCompat.getDrawable(this@GeneralTrackingActivity, R.drawable.bg_circle_light_red)
            this.alpha = alpha
        }
    }

    private fun startBounceAnimation(
        view: View, viewSize: Int, containerWidth: Int, containerHeight: Int,
        minSpeed: Float, maxSpeed: Float, initialX: Float, initialY: Float, startDelayMs: Long
    ) {
        val random = java.util.Random()
        val maxX = (containerWidth - viewSize).toFloat().coerceAtLeast(0f)
        val maxY = (containerHeight - viewSize).toFloat().coerceAtLeast(0f)

        view.translationX = initialX
        view.translationY = initialY

        var velX = (random.nextFloat() * (maxSpeed - minSpeed) + minSpeed) * if (random.nextBoolean()) 1f else -1f
        var velY = (random.nextFloat() * (maxSpeed - minSpeed) + minSpeed) * if (random.nextBoolean()) 1f else -1f

        android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 16L
            repeatCount = android.animation.ValueAnimator.INFINITE
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
            repeatCount = android.animation.ValueAnimator.INFINITE
            repeatMode = android.animation.ValueAnimator.REVERSE
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            startDelay = startDelayMs
            circleAnimators.add(this)
            start()
        }

        android.animation.ObjectAnimator.ofFloat(view, "scaleY", 1f, 1.06f).apply {
            duration = 4500L + random.nextInt(1500)
            repeatCount = android.animation.ValueAnimator.INFINITE
            repeatMode = android.animation.ValueAnimator.REVERSE
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            startDelay = startDelayMs + 600L
            circleAnimators.add(this)
            start()
        }
    }

    private fun stopCircleAnimations() {
        circleAnimators.forEach { it.cancel() }
        circleAnimators.clear()
        val circleContainer = findViewById<FrameLayout>(R.id.circleContainer)
        circleViews.forEach { circleContainer?.removeView(it) }
        circleViews.clear()
    }
}
