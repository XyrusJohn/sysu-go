package com.sysu.go.activities

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Bundle
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.sysu.go.R
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.firebase.auth.FirebaseAuth
import com.sysu.go.network.SocketManager
import com.sysu.go.utils.SessionManager

private const val TAG = "GENERAL_TRACKING_MANUAL"

class GeneralTrackingActivity : AppCompatActivity() {

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private lateinit var btnStartTracking: Button
    private lateinit var btnStopTracking: Button
    private lateinit var btnEndTracking: Button
    private lateinit var tvTrackingStatus: TextView
    private lateinit var tvCurrentLocation: TextView
    private lateinit var tvSocketStatus: TextView
    private var isTrackingStarted = false
    private var pendingStart = false

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        Log.d(TAG, "GeneralTrackingActivity created")

        setContentView(R.layout.activity_general_tracking)

        // Initialize views
        btnStartTracking = findViewById(R.id.btnStartTracking)
        btnStopTracking = findViewById(R.id.btnStopTracking)
        btnEndTracking = findViewById(R.id.btnEndTracking)
        tvTrackingStatus = findViewById(R.id.tvTrackingStatus)
        tvCurrentLocation = findViewById(R.id.tvCurrentLocation)
        tvSocketStatus = findViewById(R.id.tvSocketStatus)

        // Initialize FusedLocationProviderClient
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        // Initialize UI states
        updateTrackingStatus()
        updateSocketStatus()
        tvCurrentLocation.text = "Location: Waiting for GPS..."
        tvCurrentLocation.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))

        // Set up socket connection status listeners
        SocketManager.addOnConnectListener { updateSocketStatus() }
        SocketManager.addEventListener("disconnect") { updateSocketStatus() }

        // Location callback (same as before but we'll control when it's active)
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let {
                    // LocationServices.handleLocation already has the trackingActive guard
                    // so we don't need to check again here
                    Log.d(TAG, "[GPS] Location received: ${it.latitude}, ${it.longitude}")
                    // Update UI with current location
                    runOnUiThread {
                        tvCurrentLocation.text = "Location: ${String.format("%.6f", it.latitude)}, ${String.format("%.6f", it.longitude)}"
                        tvCurrentLocation.setTextColor(ContextCompat.getColor(this@GeneralTrackingActivity, R.color.sysu_red))
                    }
                }
            }
        }

        // Set up button click listeners
        btnStartTracking.setOnClickListener {
            startTracking()
        }

        btnStopTracking.setOnClickListener {
            stopTracking()
        }

        btnEndTracking.setOnClickListener {
            endTrip()
        }

        // Check location permission
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            // Permission not granted, request it
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                1001
            )
        }
        // Note: Actual location updates will start when user taps Start Tracking
    }

    private fun startLocationUpdates() {
        Log.d(TAG, "[GPS] Starting location updates")
        val locationRequest = LocationRequest.create().apply {
            interval = 5000
            fastestInterval = 2000
            priority = LocationRequest.PRIORITY_HIGH_ACCURACY
        }
        fusedLocationClient.requestLocationUpdates(
            locationRequest,
            locationCallback,
            Looper.getMainLooper()
        )
        Log.d(TAG, "[GPS] Location updates started")
    }

    private fun stopLocationUpdates() {
        Log.d(TAG, "[GPS] Stopping location updates")
        fusedLocationClient.removeLocationUpdates(locationCallback)
        Log.d(TAG, "[GPS] Location updates stopped")
    }

    private fun startTracking() {
        if (isTrackingStarted) return

        Log.d(TAG, "[TRACKING_BUTTON] 🟢 START Tracking button clicked by user")

        SocketManager.disconnect()
        SocketManager.connect(this)
        Log.d(TAG, "[SOCKET] Reconnecting socket before starting location updates...")

        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            startLocationUpdates()

            // FIX: Restart the foreground service to resume heartbeats
            val serviceIntent = Intent(this, com.sysu.go.services.LocationServices::class.java)
            ContextCompat.startForegroundService(this, serviceIntent)
            Log.d(TAG, "[SERVICE] LocationServices successfully restarted")

            SessionManager(this).setTrackingActive(true)
            isTrackingStarted = true

            updateTrackingStatus()
            // updateSocketStatus() will be called by socket connection listener when socket actually connects

            Log.d(TAG, "[TRACKING] trackingActive set to true & Socket reconnect initiated")
        } else {
            pendingStart = true
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                1001
            )
        }
    }

    private fun endTrip() {
        if (!isTrackingStarted) return

        Log.d(TAG, "[TRACKING_BUTTON] 🔵 END Trip button clicked by user")

        val driverId = FirebaseAuth.getInstance().currentUser?.uid
        val driverName = SessionManager(this).getDriverName() ?: "Driver"
        SocketManager.emitCompletedStatus(driverId, driverName)
        Log.d(TAG, "[STATUS] Emitted completed status to server")

        stopLocationUpdates()

        SessionManager(this).setTrackingActive(false)
        isTrackingStarted = false
        pendingStart = false

        // Stop the foreground service
        stopService(Intent(this, com.sysu.go.services.LocationServices::class.java))
        Log.d(TAG, "[SERVICE] LocationServices stopped")

        // FIX: Reset location UI text
        tvCurrentLocation.text = "Location: Waiting for GPS..."
        tvCurrentLocation.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))

        updateTrackingStatus()
        updateSocketStatus()
        Log.d(TAG, "[TRACKING] trackingActive set to false")
    }

    private fun stopTracking() {
        if (!isTrackingStarted) return

        Log.d(TAG, "[TRACKING_BUTTON] 🔴 STOP Tracking button clicked by user")

        stopLocationUpdates()

        SessionManager(this).setTrackingActive(false)
        isTrackingStarted = false
        pendingStart = false

        val driverId = FirebaseAuth.getInstance().currentUser?.uid
        val driverName = SessionManager(this).getDriverName() ?: "Driver"
        SocketManager.emitOfflineStatus(driverId, driverName)
        Log.d(TAG, "[STATUS] Emitted offline status to server")

        SocketManager.disconnect()
        Log.d(TAG, "[SOCKET] Disconnected from server")

        stopService(Intent(this, com.sysu.go.services.LocationServices::class.java))
        Log.d(TAG, "[SERVICE] LocationServices stopped")

        // FIX: Reset location UI text
        tvCurrentLocation.text = "Location: Waiting for GPS..."
        tvCurrentLocation.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))

        updateTrackingStatus()
        updateSocketStatus()
        Log.d(TAG, "[TRACKING] trackingActive set to false")
    }

    private fun updateTrackingStatus() {
        val status = if (isTrackingStarted) "ON" else "OFF"
        tvTrackingStatus.text = "Tracking: $status"
        tvTrackingStatus.setTextColor(
            if (isTrackingStarted)
                ContextCompat.getColor(this, R.color.sysu_red)
            else
                ContextCompat.getColor(this, R.color.text_secondary)
        )

        // Update button appearance
        btnStartTracking.isEnabled = !isTrackingStarted
        btnStopTracking.isEnabled = isTrackingStarted
        btnEndTracking.isEnabled = isTrackingStarted

        // Visual feedback for buttons
        btnStartTracking.setBackgroundTintList(
            if (isTrackingStarted)
                ContextCompat.getColorStateList(this, R.color.text_secondary)
            else
                ContextCompat.getColorStateList(this, R.color.sysu_red)
        )

        btnStopTracking.setBackgroundTintList(
            if (isTrackingStarted)
                ContextCompat.getColorStateList(this, R.color.sysu_red)
            else
                ContextCompat.getColorStateList(this, R.color.text_secondary)
        )

        btnEndTracking.setBackgroundTintList(
            if (isTrackingStarted)
                ContextCompat.getColorStateList(this, R.color.sysu_red)
            else
                ContextCompat.getColorStateList(this, R.color.text_secondary)
        )
    }

    private fun updateSocketStatus() {
        val isConnected = SocketManager.isConnected()
        val statusText = if (isConnected) "Socket: Connected" else "Socket: Disconnected"
        val statusColor = if (isConnected) R.color.transit_green else R.color.text_secondary

        Log.d(TAG, "[UI] Updating socket status: connected=$isConnected, text='$statusText'")
        tvSocketStatus.text = statusText
        tvSocketStatus.setTextColor(ContextCompat.getColor(this, statusColor))
        Log.d(TAG, "[UI] Socket status updated: ${tvSocketStatus.text.toString()}")
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        Log.d(TAG, "[PERM] onRequestPermissionsResult called: requestCode=$requestCode")
        if (requestCode == 1001) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "[PERM] Location permission granted")
                if (pendingStart) {
                    pendingStart = false
                    startTracking() // Reuse the same startTracking logic
                } else if (isTrackingStarted) {
                    // Ensure location updates are started (should already be)
                    startLocationUpdates()
                    // Update socket status UI in case it changed
                    updateSocketStatus()
                }
            } else {
                // Permission denied
                Log.d(TAG, "[PERM] Location permission denied")
                tvTrackingStatus.text = "Location permission denied"
                tvTrackingStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark))
                // If we were waiting for permission to start tracking, cancel it
                if (pendingStart) {
                    pendingStart = false
                    // Do not start tracking
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateSocketStatus() // Update socket status when activity resumes
    }

    override fun onDestroy() {
        Log.d(TAG, "[TRACKING] GeneralTrackingActivity onDestroy called")

        // Remove socket connection status listeners
        SocketManager.removeOnConnectListener { updateSocketStatus() }
        SocketManager.removeEventListener("disconnect") { updateSocketStatus() }

        super.onDestroy()
        stopLocationUpdates()
        // Ensure tracking is stopped when activity is destroyed
        if (isTrackingStarted) {
            SessionManager(this).setTrackingActive(false)
            Log.d(TAG, "[TRACKING] trackingActive set to false in onDestroy")
        }
    }
}