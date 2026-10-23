package com.sysu.go.audit

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.firebase.auth.FirebaseAuth
import com.sysu.go.network.SocketManager
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

object DriverActionAudit {
    private const val TAG = "DriverActionAudit"
    private const val CONSENT_PREFS = "driver_action_notice"
    private const val NOTICE_VERSION = 2
    private val flushInFlight = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var appContext: Context? = null
    @Volatile private var listenerRegistered = false
    private val onConnected: () -> Unit = { flush() }

    fun initialize(context: Context) {
        appContext = context.applicationContext
        try {
            FirebaseAuth.getInstance().currentUser?.uid?.let { uid ->
                DriverActionOutbox(context).use { it.finalizeUnresolvedReservations(uid) }
            }
            if (!listenerRegistered) synchronized(this) {
                if (!listenerRegistered) {
                    SocketManager.addOnConnectListener(onConnected)
                    listenerRegistered = true
                }
            }
            if (SocketManager.isConnected()) flush()
        } catch (error: Exception) {
            // A local audit store failure must never block the driver's home screen.
            Log.w(TAG, "Activity audit initialization deferred: " + error.javaClass.simpleName)
        }
    }

    fun noticeAcknowledged(context: Context, uid: String): Boolean =
        context.getSharedPreferences(CONSENT_PREFS, Context.MODE_PRIVATE)
            .getInt("notice_version:$uid", 0) >= NOTICE_VERSION

    fun noticeAcknowledgedAt(context: Context, uid: String): Long =
        context.getSharedPreferences(CONSENT_PREFS, Context.MODE_PRIVATE)
            .getLong("notice_acknowledged_at:$uid", Long.MAX_VALUE)

    fun showNoticeIfNeeded(activity: Activity, onDecision: (Boolean) -> Unit = {}) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid
        if (uid.isNullOrBlank()) { onDecision(false); return }
        if (noticeAcknowledged(activity, uid)) {
            recordFreshLocation(activity, "app_open")
            onDecision(true)
            return
        }
        if (activity.isFinishing || activity.isDestroyed) return
        AlertDialog.Builder(activity)
            .setTitle("Driver trip activity notice")
            .setMessage(
                "SYSU records operational steps and location samples for your trip, including when the phone is offline. " +
                    "Dispatch staff can review this history. Synced records are kept for up to 90 days; unsynced actions older than 90 days expire and cannot be uploaded. " +
                    "Location may be unavailable if permission or GPS is off."
            )
            .setCancelable(false)
            .setNegativeButton("Not now") { _, _ -> onDecision(false) }
            .setPositiveButton("Acknowledge") { _, _ ->
                activity.getSharedPreferences(CONSENT_PREFS, Context.MODE_PRIVATE).edit()
                    .putInt("notice_version:$uid", NOTICE_VERSION)
                    .putLong("notice_acknowledged_at:$uid", System.currentTimeMillis())
                    .apply()
                recordFreshLocation(activity, "app_open", outcome = "success")
                onDecision(true)
            }
            .show()
    }

    fun record(
        context: Context,
        eventType: String,
        subjectId: String? = null,
        outcome: String = "success",
        durationMs: Long? = null,
        metadata: JSONObject = JSONObject(),
        location: Location? = null,
        idempotencyKey: String? = null,
        occurredAtMs: Long = System.currentTimeMillis(),
    ) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        if (!noticeAcknowledged(context, uid)) return
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val fix = location
        val state = when {
            !granted -> "permission_denied"
            fix == null -> "unavailable"
            else -> "available"
        }
        try {
            DriverActionOutbox(context).use { outbox ->
                outbox.enqueue(
                    ownerUid = uid,
                    eventType = eventType,
                    occurredAtMs = occurredAtMs,
                    lat = fix?.latitude,
                    lng = fix?.longitude,
                    accuracy = fix?.takeIf { it.hasAccuracy() && it.accuracy.isFinite() }?.accuracy,
                    locationState = state,
                    subjectId = subjectId?.takeIf { it.length <= 128 },
                    outcome = outcome.take(40),
                    durationMs = durationMs,
                    metadata = metadata,
                    idempotencyKey = idempotencyKey
                )
            }
            flush()
        } catch (error: Exception) {
            // Audit capture must never change queue, shipment, or return-action outcomes.
            Log.w(TAG, "Could not queue activity event: " + error.javaClass.simpleName)
        }
    }

    fun recordFreshLocation(
        context: Context,
        eventType: String,
        subjectId: String? = null,
        outcome: String = "success",
        durationMs: Long? = null,
        metadata: JSONObject = JSONObject(),
        idempotencyKey: String? = null,
        occurredAtMs: Long = System.currentTimeMillis(),
    ) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        if (!noticeAcknowledged(context, uid)) return
        try {
            val reservation = DriverActionOutbox(context).use { outbox ->
                outbox.reserveEvent(
                    ownerUid = uid, eventType = eventType, occurredAtMs = occurredAtMs,
                    subjectId = subjectId?.takeIf { it.length <= 128 && !it.contains('/') && !it.contains('\\') },
                    outcome = outcome.take(40), durationMs = durationMs, metadata = metadata,
                    idempotencyKey = idempotencyKey,
                    closeSession = eventType == "session_completed"
                )
            } ?: return
            val locationResolved = AtomicBoolean(false)
            val resolve: (Location?, String) -> Unit = { fix, state ->
                if (locationResolved.compareAndSet(false, true)) {
                    mainHandler.removeCallbacksAndMessages(reservation.eventId)
                    try {
                        DriverActionOutbox(context).use { outbox ->
                            outbox.resolveReservation(
                                uid, reservation, fix?.latitude, fix?.longitude,
                                fix?.takeIf { it.hasAccuracy() && it.accuracy.isFinite() }?.accuracy,
                                state
                            )
                        }
                    } catch (error: Exception) {
                        Log.w(TAG, "Could not resolve activity location: " + error.javaClass.simpleName)
                        runCatching {
                            DriverActionOutbox(context).use { outbox ->
                                outbox.resolveReservation(uid, reservation, locationState = "unavailable")
                            }
                        }
                    }
                    flush()
                }
            }
            val unresolvedTimeout = Runnable { resolve(null, "unavailable") }
            mainHandler.postAtTime(unresolvedTimeout, reservation.eventId, android.os.SystemClock.uptimeMillis() + LOCATION_TIMEOUT_MS)
            if (!hasLocationPermission(context)) {
                resolve(null, "permission_denied")
                return
            }
            try {
                LocationServices.getFusedLocationProviderClient(context).getCurrentLocation(
                    Priority.PRIORITY_BALANCED_POWER_ACCURACY, null
                ).addOnSuccessListener { fix ->
                    resolve(fix, if (fix == null) "unavailable" else "available")
                }.addOnFailureListener {
                    resolve(null, "unavailable")
                }
            } catch (_: SecurityException) {
                resolve(null, "permission_denied")
            } catch (_: Exception) {
                resolve(null, "unavailable")
            }
        } catch (error: Exception) {
            Log.w(TAG, "Could not reserve activity event: " + error.javaClass.simpleName)
        }
    }

    fun completeSession(context: Context) {
        recordFreshLocation(context, "session_completed", outcome = "success", idempotencyKey = "session-completed")
    }

    fun hasLocationPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun pendingCount(context: Context): Int {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return 0
        return try { DriverActionOutbox(context).use { it.pendingCount(uid) } } catch (_: Exception) { 0 }
    }

    private fun flush() {
        val context = appContext ?: return
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        if (!noticeAcknowledged(context, uid) || !SocketManager.isConnected() || !flushInFlight.compareAndSet(false, true)) return
        try {
            val pending = DriverActionOutbox(context).use { it.pending(uid) }
            if (pending.isEmpty()) {
                flushInFlight.set(false)
                return
            }
            val sessionId = pending.first().sessionId
            val batch = pending.takeWhile { it.sessionId == sessionId }
            val ackHandled = AtomicBoolean(false)
            val timeout = Runnable {
                if (ackHandled.compareAndSet(false, true)) {
                    flushInFlight.set(false)
                    if (SocketManager.isConnected() && pendingCount(context) > 0) {
                        mainHandler.postDelayed({ flush() }, RETRY_DELAY_MS)
                    }
                }
            }
            mainHandler.postDelayed(timeout, ACK_TIMEOUT_MS)
            val payload = JSONObject().apply {
                put("sessionId", sessionId)
                put("events", JSONArray().apply { batch.forEach { put(it.toPayload()) } })
            }
            val emitted = SocketManager.emitDriverActionSync(payload) { ack ->
                if (!ackHandled.compareAndSet(false, true)) return@emitDriverActionSync
                mainHandler.removeCallbacks(timeout)
                var allAccepted = false
                try {
                    val accepted = ack.optJSONArray("acceptedEventIds") ?: JSONArray()
                    val terminal = ack.optJSONArray("terminalEventIds") ?: JSONArray()
                    val expected = batch.mapTo(HashSet()) { it.eventId }
                    val ids = (0 until accepted.length()).mapNotNull { accepted.optString(it).takeIf(expected::contains) }
                    val expired = (0 until terminal.length()).mapNotNull { terminal.optString(it).takeIf(expected::contains) }
                    val success = ack.optBoolean("success")
                    if (expired.isNotEmpty()) Log.w(TAG, "Expired unsynced activity events were removed from the local queue")
                    if (success && (ids.isNotEmpty() || expired.isNotEmpty())) {
                        DriverActionOutbox(context).use { it.acknowledge(uid, ids + expired) }
                    }
                    allAccepted = success && (ids + expired).distinct().size == batch.size
                } catch (_: Exception) {
                    Log.w(TAG, "Activity sync acknowledgement could not be applied")
                } finally {
                    flushInFlight.set(false)
                    if (SocketManager.isConnected() && pendingCount(context) > 0) {
                        mainHandler.postDelayed({ flush() }, if (allAccepted) 250L else RETRY_DELAY_MS)
                    }
                }
            }
            if (!emitted) {
                mainHandler.removeCallbacks(timeout)
                ackHandled.set(true)
                flushInFlight.set(false)
            }
        } catch (_: Exception) {
            flushInFlight.set(false)
            Log.w(TAG, "Activity sync deferred")
        }
    }

    private const val ACK_TIMEOUT_MS = 15_000L
    private const val RETRY_DELAY_MS = 5_000L
    private const val LOCATION_TIMEOUT_MS = 10_000L
}
