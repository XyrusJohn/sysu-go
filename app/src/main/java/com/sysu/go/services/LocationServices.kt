package com.sysu.go.services

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import com.google.firebase.auth.FirebaseAuth
import com.sysu.go.R
import com.sysu.go.activities.MainActivity
import com.sysu.go.network.SocketManager
import com.sysu.go.utils.SessionManager
import com.sysu.go.utils.TrackingStateMonitor
import kotlinx.coroutines.*

private const val TAG = "LOCATION_TRACKING_DEFINITIVE"
private const val CHANNEL_ID = "sysu_tracking_channel"
private const val CHANNEL_NAME = "Live Trip Tracking"
private const val NOTIFICATION_ID = 1

// Static shared state — updated on every valid GPS fix.
// MyTripActivity reads these to stamp POD photos with location.
private var lastKnownLat: Double? = null
private var lastKnownLng: Double? = null

/**
 * Returns the most recently cached GPS location, or null if LocationServices
 * has not yet received a fix (e.g., app freshly opened without transit).
 */
fun getLastKnownLocationPair(): Pair<Double, Double>? {
    val lat = lastKnownLat ?: return null
    val lng = lastKnownLng ?: return null
    return lat to lng
}

class LocationServices : Service() {

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private val serviceScope = CoroutineScope(Dispatchers.IO)
    private lateinit var sessionManager: SessionManager

    // SharedPreferences — same "driver_session" file that SessionManager (TransitActivity) writes to
    private val prefs by lazy {
        getSharedPreferences("driver_session", Context.MODE_PRIVATE)
    }

    private var lastEmitLocation: android.location.Location? = null
    private var lastEmitTime: Long = 0

    // Stopped timer: if driver is stationary for 60s, emit driver_stopped (traffic signal)
    // Cancelled and reset on every valid movement (≥10m GPS step)
    private val stoppedHandler = Handler(Looper.getMainLooper())
    private var stoppedRunnable: Runnable? = null
    private var lastStoppedLat: Double? = null
    private var lastStoppedLng: Double? = null
    private var lastStoppedSapDocId: String? = null
    private var wasStoppedEmitted = false  // guard: only emit stopped once per stop event

    private val sapDocIdKey = "sap_doc_id"

    // Battery critical receiver — emit offline when battery is critically low (<5%)
    // This is a "truly unreachable" signal for dispatch safety
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_BATTERY_LOW) {
                Log.w(TAG, "[BATTERY] Battery LOW — emitting offline, disabling tracking")
                sessionManager.setTrackingActive(false)
                TrackingStateMonitor.setState(TrackingStateMonitor.State.OFFLINE)
                SocketManager.emitOfflineStatus(
                    FirebaseAuth.getInstance().currentUser?.uid,
                    SocketManager.getDriverName()
                )
                Log.w(TAG, "[EMIT] Battery LOW - emitted offline status")
            }
        }
    }

    // For dynamic notification updates
    private var lastUpdateTime: Long = 0
    private var lastAccuracy: Float? = null
    private val notificationManager by lazy { getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager }

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = com.google.android.gms.location.LocationServices.getFusedLocationProviderClient(this)
        sessionManager = SessionManager(this)
        Log.d(TAG, "LocationServices created - tracking_active=${sessionManager.isTrackingActive()}")

        // Register battery critical listener — fires when battery drops to low threshold
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_LOW))
        Log.d(TAG, "Battery critical listener registered")

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                for (location in locationResult.locations) {
                    Log.d(TAG, "LocationCallback received location: [${
                        location.latitude}, ${location.longitude}] tracking_active=${sessionManager.isTrackingActive()}")
                    handleLocation(location)
                }
            }
        }
    }

    private fun handleLocation(location: android.location.Location) {
        val lat = location.latitude
        val lng = location.longitude
        val accuracy = location.accuracy

        // Update shared static cache for POD timestamp overlay
        lastKnownLat = lat
        lastKnownLng = lng

        // STRICT GEOFENCE GUARD: re-validate tracking flag on every GPS callback.
        // Catches the case where the trip ended (returning_to_base / completed) but
        // the service was already in the middle of dispatching a callback.
        // Without this check we'd get one stray emit between trip end and the
        // removeLocationUpdates() call, polluting the sysu-app map.
        val isTracking = sessionManager.isTrackingActive()
        if (!isTracking) {
            Log.w(TAG, "[STRICT] handleLocation called but tracking_active=FALSE — dropping emit. lat=$lat, lng=$lng")
            return
        }

        Log.d(TAG, "[GPS] Handling location update: lat=$lat, lng=$lng, accuracy=$accuracy m, tracking_active=$isTracking")

        val currentTime = System.currentTimeMillis()

        // Throttle: emit at most every 5 seconds during transit
        if (currentTime - lastEmitTime < 5000) {
            Log.d(TAG, "[THROTTLE] Skipping emit - last emit was ${currentTime - lastEmitTime}ms ago")
            return
        }

        val newLocation = android.location.Location("GPS").apply {
            latitude = lat
            longitude = lng
        }

        // Filter noise: 10m minimum movement (tightened from 30m for transit use)
        lastEmitLocation?.let { last ->
            val distance = last.distanceTo(newLocation)
            if (distance < 10.0) {
                Log.d(TAG, "[FILTER] Filtering stationary/drift - distance=$distance m, lat=$lat, lng=$lng")
                return
            }
        }

        lastEmitTime = currentTime
        lastEmitLocation = newLocation

        // Read active SAP doc ID from SessionManager
        val sapDocId = sessionManager.getActiveSapDocId() ?: sessionManager.getCurrentSapDocId() ?: prefs?.getString(sapDocIdKey, null)

        // Persist coords BEFORE emit — used for session restore on app reopen
        prefs.edit()
            .putFloat("last_emit_lat", lat.toFloat())
            .putFloat("last_emit_lng", lng.toFloat())
            .apply()

        // Any valid movement cancels the stopped timer and clears the stopped state.
        // This ensures driver_stopped only fires after a FULL 60s of no ≥10m movement.
        cancelStoppedTimer()

        // Normalize stopped state to ONLINE on any valid movement
        if (wasStoppedEmitted) {
            TrackingStateMonitor.setState(TrackingStateMonitor.State.ONLINE)
            Log.d(TAG, "[STATE] Stopped state reset to ONLINE due to movement")
        }

        // Emit via Socket.IO for real-time admin map
        SocketManager.emitDriverMoving(lat, lng, sapDocId, status = "online")
        Log.d(TAG, "[EMIT] driver_moving: [$lat, $lng], sapDocId=$sapDocId")

        // ── Direct Firestore Real-Time Location Dual-Channel ───────────────────
        val currentUid = FirebaseAuth.getInstance().currentUser?.uid
            ?: sessionManager.getDriverId()
        if (!currentUid.isNullOrBlank()) {
            val driverName = sessionManager.getDriverName() ?: "Driver"
            val locData = hashMapOf(
                "driverId" to currentUid,
                "name" to driverName,
                "lat" to lat,
                "lng" to lng,
                "status" to "online",
                "sapDocId" to sapDocId,
                "updatedAt" to com.google.firebase.firestore.FieldValue.serverTimestamp()
            )
            com.google.firebase.firestore.FirebaseFirestore.getInstance().collection("driver_locations")
                .document(currentUid)
                .set(locData, com.google.firebase.firestore.SetOptions.merge())

            if (!sapDocId.isNullOrBlank()) {
                com.google.firebase.firestore.FirebaseFirestore.getInstance().collection("sap_assignments")
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

        wasStoppedEmitted = false

        // Capture this position as the "last known stopped position" for potential
        // driver_stopped emit. Store it BEFORE starting the timer so that when the
        // timer fires at 60s, we emit the position at the time of stopping — not a
        // GPS-lost or stale coordinate.
        lastStoppedLat = lat
        lastStoppedLng = lng
        lastStoppedSapDocId = sapDocId

        // Start/update the 60s stopped timer.
        // Resets on every subsequent valid movement event.
        postStoppedTimer()

        // Update notification with latest GPS accuracy and time
        lastUpdateTime = currentTime
        lastAccuracy = accuracy
        updateNotification()
    }

    /**
     * Emits driver_stopped (yellow) after driver has been stationary for 60s.
     * Uses the position captured at the moment the driver stopped — not a stale GPS coord.
     * Guard ensures it fires at most once per stop event (reset by movement).
     */
    private fun emitDriverStopped() {
        // If driver is moving again, skip — driver_moving takes precedence
        if (wasStoppedEmitted) {
            Log.d(TAG, "[STOP] emitDriverStopped skipped - wasStoppedEmitted=true")
            return
        }

        val lat = lastStoppedLat ?: return
        val lng = lastStoppedLng ?: return
        val sapDocId = lastStoppedSapDocId

        wasStoppedEmitted = true
        SocketManager.emitDriverStopped(lat, lng, sapDocId)
        Log.d(TAG, "[EMIT] driver_stopped: [$lat, $lng], sapDocId=$sapDocId")

        val currentUid = FirebaseAuth.getInstance().currentUser?.uid
            ?: sessionManager.getDriverId()
        if (!currentUid.isNullOrBlank()) {
            com.google.firebase.firestore.FirebaseFirestore.getInstance().collection("driver_locations")
                .document(currentUid)
                .update(
                    mapOf(
                        "status" to "stopped",
                        "updatedAt" to com.google.firebase.firestore.FieldValue.serverTimestamp()
                    )
                )
        }
    }

    /**
     * Posts a 60-second delayed runnable.
     * If [postStoppedTimer] is called again before it fires, the previous pending
     * runnable is cancelled first — ensuring the timer always reflects the MOST
     * RECENT stop (not the oldest in the queue).
     */
    private fun postStoppedTimer() {
        // Capture current position at timer start for use when runnable fires
        val emitLat = lastStoppedLat ?: return
        val emitLng = lastStoppedLng ?: return
        val emitSapDocId = lastStoppedSapDocId

        Log.d(TAG, "[TIMER] Starting stopped timer with position: [$emitLat, $emitLng], sapDocId=$emitSapDocId")

        stoppedRunnable?.let { stoppedHandler.removeCallbacks(it) }

        val runnable = Runnable {
            val isTracking = sessionManager.isTrackingActive()
            Log.d(TAG, "[TIMER] Timer fired - tracking_active=$isTracking")
            if (isTracking) {
                // Re-fetch from captured vals — they may have been updated by a later
                // handleLocation call if the timer was re-posted; emit from latest
                SocketManager.emitDriverStopped(emitLat, emitLng, emitSapDocId)
                TrackingStateMonitor.setState(TrackingStateMonitor.State.STOPPED)
                wasStoppedEmitted = true
                Log.d(TAG, "[EMIT] driver_stopped timer fired at last position: [$emitLat, $emitLng]")
            } else {
                Log.d(TAG, "[TIMER] Timer fired but tracking_active=FALSE - skipping emit")
            }
        }
        stoppedRunnable = runnable
        stoppedHandler.postDelayed(runnable, 60_000L) // 60 seconds
        Log.d(TAG, "[TIMER] Stopped timer started — will emit driver_stopped in 60s if no ≥10m movement")
    }

    private fun cancelStoppedTimer() {
        stoppedRunnable?.let {
            stoppedHandler.removeCallbacks(it)
            stoppedRunnable = null
            wasStoppedEmitted = false
            TrackingStateMonitor.setState(TrackingStateMonitor.State.ONLINE)
            Log.d(TAG, "[TIMER] Stopped timer cancelled — movement detected")
        }
    }

    @SuppressLint("MissingPermission")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand called - tracking_active=${sessionManager.isTrackingActive()}")
        createNotificationChannel()
        // Initialize notification update timers
        lastUpdateTime = System.currentTimeMillis()
        lastAccuracy = null
        startForeground(NOTIFICATION_ID, buildNotification("Initializing GPS..."))

        // Prevent double-connect: if SocketManager already initialized and
        // connected from this service's context, skip reconnecting.
        if (SocketManager.isConnected()) {
            Log.i(TAG, "LocationServices: SocketManager already connected, skipping redundant connect")
        } else {
            Log.i(TAG, "LocationServices: Connecting Socket.IO for background keep-alive")
            SocketManager.connect(this)
        }

        // STRICT GEOFENCE: only start location updates when TransitActivity has
        // explicitly set tracking_active=true (i.e. driver clicked "Start Trip").
        // The double-guard in handleLocation() guarantees no emit can leak past
        // a transition that flipped tracking_active=false (returning_to_base,
        // completed) before the system de-registers the callback.
        // When the app starts from MainActivity (no trip active), tracking is OFF —
        // the service runs as a foreground service (notification stays alive,
        // socket stays online) but does NOT emit driver_moving.
        val isTracking = sessionManager.isTrackingActive()
        if (isTracking) {
            Log.i(TAG, "LocationServices: tracking_active=TRUE — starting GPS location updates")
            try {
                startLocationUpdates()
            } catch (e: SecurityException) {
                Log.e(TAG, "Location permission not granted — cannot start GPS tracking", e)
            }
        } else {
            Log.i(TAG, "LocationServices: tracking_active=FALSE — foreground service only, no GPS tracking")
        }

        return START_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        Log.d(TAG, "[GPS] Starting location updates")
        lastEmitLocation = null
        lastEmitTime = 0

        val locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            5000  // 5 second interval for smooth transit tracking
        ).setMinUpdateIntervalMillis(3000)
          .setMinUpdateDistanceMeters(10f)
          .build()

        Log.d(TAG, "[GPS] Requesting location updates with request: $locationRequest")
        fusedLocationClient.requestLocationUpdates(
            locationRequest,
            locationCallback,
            mainLooper
        )
    }

    private fun buildNotification(contentText: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SYSU-GO Tracking Active")
            .setContentText(contentText)
            .setSmallIcon(R.mipmap.ic_launcher) // TODO: replace with @drawable/ic_notification if available
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification() {
        val secondsAgo = ((System.currentTimeMillis() - lastUpdateTime) / 1000).toInt()
        val accuracy = lastAccuracy
        val accuracyLabel = when {
            accuracy == null -> "GPS: Waiting..."
            accuracy < 10 -> "GPS: Excellent (${accuracy.toInt()}m)"
            accuracy < 30 -> "GPS: Good (${accuracy.toInt()}m)"
            accuracy < 50 -> "GPS: Weak (${accuracy.toInt()}m)"
            else -> "GPS: Poor (${accuracy.toInt()}m)"
        }
        val contentText = "🟢 $accuracyLabel | Last update: ${secondsAgo}s ago"
        val notification = buildNotification(contentText)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Live trip tracking notification"
                enableLights(false)
                enableVibration(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        Log.d(TAG, "LocationServices destroyed")
        // Remove the foreground notification
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
        cancelStoppedTimer()
        try { unregisterReceiver(batteryReceiver) } catch (_: Exception) {}
        fusedLocationClient.removeLocationUpdates(locationCallback)
        serviceScope.cancel()
        SocketManager.disconnect()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}