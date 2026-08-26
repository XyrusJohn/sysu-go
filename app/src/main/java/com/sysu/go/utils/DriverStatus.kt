package com.sysu.go.utils

import android.content.Context
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.sysu.go.BuildConfig
import com.sysu.go.network.SocketManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * Single point of truth for `drivers/{uid}.status` writes.
 *
 * Lifecycle writers (each owns its own transitions per design):
 *   - [ConnectivityMonitor]        → "offline" on disconnect, restores last online on reconnect
 *   - [com.sysu.go.activities.MainActivity]  → "in_transit" on START TRIP
 *   - [com.sysu.go.activities.ReturnToBaseBottomSheet]
 *                                    → "returning_to_base" / "online" (after confirm)
 *
 * Profile reads the same field via a Socket.IO `driver_status_change` listener
 * (no Firestore snapshot listener — we are deliberately moving away from
 * snapshot listeners so MySQL can become the primary database later).
 *
 * Real-time path (best effort, falls through on failure):
 *   1. write() persists to Firestore via direct SDK write
 *   2. write() emits `driver_status_change` via Socket.IO
 *   3. Backend persists + broadcasts to room `driver:{uid}` and globally
 *   4. Web app + other ProfileActivity instances receive instant update
 *
 * Fallback path (when Firestore DNS is broken on device):
 *   5. If Firestore write fails AND Socket.IO emit fails, write() calls
 *      the backend REST proxy at POST /api/driver/status. The backend
 *      uses Firebase Admin SDK with its own DNS to write to Firestore,
 *      then fans out via Socket.IO to room `driver:{uid}` and globally.
 */
object DriverStatus {
    private const val TAG = "DriverStatus"
    private val db = FirebaseFirestore.getInstance()

    // Coroutine scope for the backend proxy fallback (network IO on IO dispatcher)
    private val proxyScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Canonical driver lifecycle values written to drivers/{uid}.status.
     * Exactly 4 states — see the simple badge logic in [renderStatusBadge].
     *   - ONLINE:            default / has internet
     *   - OFFLINE:           no internet
     *   - IN_TRANSIT:        actively delivering shipments
     *   - RETURNING_TO_BASE: heading back to warehouse
     */
    object State {
        const val ONLINE = "online"
        const val OFFLINE = "offline"
        const val IN_TRANSIT = "in_transit"
        const val RETURNING_TO_BASE = "returning_to_base"
    }

    /**
     * Last non-offline status observed for this process.
     * Lets us restore "online" (or "in_transit"/"returning_to_base") when the
     * driver reconnects.
     *
     * Public read via [getLastOnlineState] so other modules (e.g. SocketManager
     * on EVENT_CONNECT) can read the current state without writing.
     */
    @Volatile
    private var lastOnlineState: String = State.ONLINE

    /**
     * Returns the current cached non-offline status. Defaults to [State.ONLINE]
     * at process start. Updated whenever [write] is called with a non-OFFLINE value.
     */
    fun getLastOnlineState(): String = lastOnlineState

    /**
     * Writes the given status to drivers/{uid}.status AND emits
     * driver_status_change via Socket.IO so other clients (Profile app on
     * another device, web app) get instant real-time updates without polling.
     *
     * Fire-and-forget — caller does not need to await. Falls back to the
     * backend REST proxy at /api/driver/status when direct Firestore write
     * and Socket.IO emit both fail.
     */
    fun write(uid: String?, status: String) {
        if (uid.isNullOrBlank()) {
            Log.w(TAG, "write skipped — uid is null")
            return
        }
        // Update cache so the offline→online restoration has a source of truth
        if (status != State.OFFLINE) {
            lastOnlineState = status
        }

        var firestoreWriteFailed = false
        var socketEmitFailed = false

        // 1. Persist to Firestore (last-known-truth backup + offline read for Profile)
        try {
            val updatePayload = mutableMapOf<String, Any>(
                "status" to status,
                "isReturning" to (status == State.RETURNING_TO_BASE)
            )
            db.collection("drivers").document(uid)
                .set(updatePayload, SetOptions.merge())
                .addOnFailureListener { e ->
                    Log.w(TAG, "write($status) Firestore failed for $uid: ${e.message}")
                    firestoreWriteFailed = true
                    // Try the backend proxy as a fallback
                    tryProxyFallback(uid, status)
                }
                .addOnSuccessListener {
                    Log.d(TAG, "write($status) → $uid [Firestore OK]")
                }
        } catch (e: Exception) {
            Log.w(TAG, "write($status) Firestore exception: ${e.message}")
            firestoreWriteFailed = true
        }

        // 2. Real-time broadcast via Socket.IO — backend fans out to subscribers
        val socketOk = SocketManager.emitStatusChange(uid, status)
        if (!socketOk) {
            Log.w(TAG, "write($status) Socket.IO emit skipped for $uid")
            socketEmitFailed = true
        }

        // If both failed, immediately fall back to backend proxy
        if (firestoreWriteFailed && socketEmitFailed) {
            tryProxyFallback(uid, status)
        }
    }

    /**
     * Convenience: mark driver offline (called by ConnectivityMonitor).
     */
    fun markOffline(uid: String?) = write(uid, State.OFFLINE)

    /**
     * Convenience: restore the last non-offline state. Called when the
     * device reconnects to the network.
     */
    fun restoreOnline(uid: String?) = write(uid, lastOnlineState)

    /**
     * Seed the cached lastOnlineState at app start (e.g. read from local
     * prefs if persisted). Optional — defaults to ONLINE.
     */
    fun seedLastOnlineState(state: String) {
        if (state != State.OFFLINE) lastOnlineState = state
    }

    /**
     * POSTs the status update to the backend REST endpoint as a fallback
     * when direct Firestore writes and Socket.IO emit both fail (DNS broken
     * on the device). The backend uses its own Firebase Admin SDK to write
     * to Firestore, then fans out via Socket.IO.
     *
     * This is the path that survives DNS-level outages on the device —
     * the backend's DNS is independent of the device's broken DNS.
     */
    private fun tryProxyFallback(uid: String, status: String) {
        proxyScope.launch {
            try {
                val user = FirebaseAuth.getInstance().currentUser
                if (user == null) {
                    Log.w(TAG, "proxy fallback skipped — no current user")
                    return@launch
                }
                val tokenResult = user.getIdToken(false).await()
                val token = tokenResult.token ?: run {
                    Log.w(TAG, "proxy fallback skipped — empty id token")
                    return@launch
                }

                val url = URL("${BuildConfig.API_BASE_URL}/api/driver/status")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 5_000
                conn.readTimeout = 5_000
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Authorization", "Bearer $token")

                val body = JSONObject().apply {
                    put("status", status)
                    put("name", SocketManager.getDriverName())
                }.toString()

                conn.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }

                val responseCode = conn.responseCode
                if (responseCode in 200..299) {
                    Log.d(TAG, "proxy fallback OK: $uid → $status (HTTP $responseCode)")
                } else {
                    val errBody = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                    Log.w(TAG, "proxy fallback failed: HTTP $responseCode $errBody")
                }
                conn.disconnect()
            } catch (e: Exception) {
                Log.w(TAG, "proxy fallback exception: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }
}