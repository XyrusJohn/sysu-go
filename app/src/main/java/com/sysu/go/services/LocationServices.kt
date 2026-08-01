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

private const val TAG = "LocationServices"
private const val CHANNEL_ID = "LocationServiceChannel"
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
                Log.w(TAG, "Battery LOW — emitting offline, disabling tracking")
                sessionManager.setTrackingActive(false)
                TrackingStateMonitor.setState(TrackingStateMonitor.State.OFFLINE)
                SocketManager.emitOfflineStatus(
                    FirebaseAuth.getInstance().currentUser?.uid,
                    SocketManager.getDriverName()
                )
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = com.google.android.gms.location.LocationServices.getFusedLocationProviderClient(this)
        sessionManager = SessionManager(this)

        // Register battery critical listener — fires when battery drops to low threshold
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_LOW))

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                for (location in locationResult.locations) {
                    handleLocation(location.latitude, location.longitude)
                }
            }
        }
    }

    private fun handleLocation(lat: Double, lng: Double) {
        // Update shared static cache for POD timestamp overlay
        lastKnownLat = lat
        lastKnownLng = lng

        // STRICT GEOFENCE GUARD: re-validate tracking flag on every GPS callback.
        // Catches the case where the trip ended (returning_to_base / completed) but
        // the service was already in the middle of dispatching a callback.
        // Without this check we'd get one stray emit between trip end and the
        // removeLocationUpdates() call, polluting the sysu-app map.
        if (!sessionManager.isTrackingActive()) {
            Log.w(TAG, "[STRICT] handleLocation called but tracking_active=FALSE — dropping emit")
            return
        }

        val currentTime = System.currentTimeMillis()

        // Throttle: emit at most every 5 seconds during transit
        if (currentTime - lastEmitTime < 5000) return

        val newLocation = android.location.Location("GPS").apply {
            latitude = lat
            longitude = lng
        }

        // Filter noise: 10m minimum movement (tightened from 30m for transit use)
        lastEmitLocation?.let { last ->
            if (last.distanceTo(newLocation) < 10.0) {
                Log.d(TAG, "Filtered stationary/drift ($lat, $lng)")
                return
            }
        }

        lastEmitTime = currentTime
        lastEmitLocation = newLocation

        // Read sap_doc_id from SharedPreferences (written by SessionManager in TransitActivity)
        val sapDocId = prefs?.getString(sapDocIdKey, null)

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
        }

        // Emit via Socket.IO for real-time admin map
        SocketManager.emitDriverMoving(lat, lng, sapDocId, status = "online")
        Log.d(TAG, "driver_moving: [$lat, $lng], sapDocId=$sapDocId")

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
    }

    /**
     * Emits driver_stopped (yellow) after driver has been stationary for 60s.
     * Uses the position captured at the moment the driver stopped — not a stale GPS coord.
     * Guard ensures it fires at most once per stop event (reset by movement).
     */
    private fun emitDriverStopped() {
        // If driver is moving again, skip — driver_moving takes precedence
        if (wasStoppedEmitted) return

        val lat = lastStoppedLat ?: return
        val lng = lastStoppedLng ?: return
        val sapDocId = lastStoppedSapDocId

        wasStoppedEmitted = true
        SocketManager.emitDriverStopped(lat, lng, sapDocId)
        Log.d(TAG, "driver_stopped: [$lat, $lng], sapDocId=$sapDocId")
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

        stoppedRunnable?.let { stoppedHandler.removeCallbacks(it) }

        val runnable = Runnable {
            if (sessionManager.isTrackingActive()) {
                // Re-fetch from captured vals — they may have been updated by a later
                // handleLocation call if the timer was re-posted; emit from latest
                SocketManager.emitDriverStopped(emitLat, emitLng, emitSapDocId)
                TrackingStateMonitor.setState(TrackingStateMonitor.State.STOPPED)
                wasStoppedEmitted = true
                Log.d(TAG, "driver_stopped timer fired at last position: [$emitLat, $emitLng]")
            }
        }
        stoppedRunnable = runnable
        stoppedHandler.postDelayed(runnable, 60_000L) // 60 seconds
        Log.d(TAG, "Stopped timer started — will emit driver_stopped in 60s if no ≥10m movement")
    }

    private fun cancelStoppedTimer() {
        stoppedRunnable?.let {
            stoppedHandler.removeCallbacks(it)
            stoppedRunnable = null
            wasStoppedEmitted = false
            TrackingStateMonitor.setState(TrackingStateMonitor.State.ONLINE)
            Log.d(TAG, "Stopped timer cancelled — movement detected")
        }
    }

    @SuppressLint("MissingPermission")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())

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
        if (sessionManager.isTrackingActive()) {
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
        val locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            5000  // 5 second interval for smooth transit tracking
        ).setMinUpdateIntervalMillis(3000)
          .setMinUpdateDistanceMeters(10f)
          .build()

        fusedLocationClient.requestLocationUpdates(
            locationRequest,
            locationCallback,
            mainLooper
        )
    }

    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Aktibo ang SYSU Tracking")
            .setContentText("Ipinapadala ang iyong lokasyon sa dispatch")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "GPS Location Tracking",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Keeps app alive for real-time driver tracking"
                enableLights(true)
                enableVibration(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cancelStoppedTimer()
        try { unregisterReceiver(batteryReceiver) } catch (_: Exception) {}
        fusedLocationClient.removeLocationUpdates(locationCallback)
        serviceScope.cancel()
        SocketManager.disconnect()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}