package com.sysu.go.utils

import android.util.Log
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Mirrors sap_assignments state into drivers/{uid}.assignedShipments + activeTrip.
 *
 * Why this exists:
 *   Previously, ProfileActivity and other UI screens had to query the
 *   sap_assignments collection directly to render "Assigned: N shipments" or
 *   the current trip. That requires a separate Firestore read (or listener)
 *   per screen, and forces sysu-web / future admin tools to do the same.
 *
 *   With this utility, drivers/{uid} becomes a single self-contained
 *   snapshot — the assignedShipments array and activeTrip object tell any
 *   reader what the driver is working on without joining sap_assignments.
 *
 * Schema written to drivers/{uid}:
 *   - assignedShipments: [string] — docIds of all non-completed sap_assignments
 *     (active + waiting + loading). Excludes completed/delivered so the
 *     array stays small and stable.
 *   - activeTrip: {
 *       sapDocId: string?,         // primary in_transit doc, or null
 *       sapDocIds: [string]?,      // all in_transit docIds (multi-shipment trip)
 *       startedAt: Timestamp?,     // from sap_assignments.startedAt
 *       updatedAt: Timestamp       // when we last wrote this
 *     } | null
 *
 * Sync strategy:
 *   - Single Firestore listener per driver on sap_assignments where driverId == uid
 *   - On every snapshot: rebuild the assignedShipments array + activeTrip object
 *     and write to drivers/{uid} with merge=true (never clobbers other driver fields)
 *   - Idempotent — re-running on the same data produces the same write.
 *
 * Lifecycle:
 *   - Started once per app session in GoApplication.onCreate()
 *   - Reads uid from FirebaseAuth.currentUser; no-op when logged out
 *   - Listener survives Activity rotations (singleton object)
 */
object DriverShipmentSync {
    private const val TAG = "DriverShipmentSync"
    private val db = FirebaseFirestore.getInstance()

    // Listener handle so we can re-attach on uid change (login/logout)
    private var sapListener: ListenerRegistration? = null
    private var currentUid: String? = null

    // Background scope for the Firestore write that mirrors the snapshot
    private val syncScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Start the sync for the given driver UID. Idempotent — safe to call multiple
     * times. If called with a different uid, the old listener is torn down and a
     * new one is attached.
     *
     * @param uid Firebase UID of the driver (or null to skip — login not yet done)
     */
    fun start(uid: String?) {
        val target = uid ?: run {
            Log.d(TAG, "start() skipped — no uid (login not done yet)")
            return
        }
        if (currentUid == target && sapListener != null) {
            Log.d(TAG, "start() — already syncing for $target")
            return
        }
        // Tear down old listener (uid changed, e.g. logout/login)
        stop()

        currentUid = target
        Log.d(TAG, "start() — attaching sap_assignments listener for $target")

        sapListener = db.collection("sap_assignments")
            .whereEqualTo("driverId", target)
            .addSnapshotListener { snap, err ->
                if (err != null) {
                    Log.w(TAG, "snapshot error: ${err.message}")
                    return@addSnapshotListener
                }
                if (snap == null) {
                    Log.d(TAG, "snapshot null — skipping mirror")
                    return@addSnapshotListener
                }
                mirrorToDriverDoc(target, snap.documents)
            }
    }

    /**
     * Stop the sync. Safe to call when not started.
     */
    fun stop() {
        sapListener?.remove()
        sapListener = null
        currentUid = null
        Log.d(TAG, "stop() — listener detached")
    }

    /**
     * Build the assignedShipments array + activeTrip object from the snapshot
     * and write to drivers/{uid}. Fire-and-forget on a background coroutine.
     *
     * Definitions:
     *   - assignedShipments = docIds where tripStatus != "completed" AND != "delivered"
     *     (delivered-but-trip-not-closed still counts as "assigned")
     *   - activeTrip.sapDocIds = docIds where tripStatus == "in_transit"
     *   - activeTrip.sapDocId = first in_transit docId (primary), or null if none
     *   - activeTrip.startedAt = earliest startedAt across in_transit docs
     */
    private fun mirrorToDriverDoc(uid: String, docs: List<com.google.firebase.firestore.DocumentSnapshot>) {
        val assigned = mutableListOf<String>()
        val inTransit = mutableListOf<String>()
        var earliestStart: Timestamp? = null

        for (doc in docs) {
            val tripStatus = doc.getString("tripStatus") ?: continue
            when (tripStatus) {
                "in_transit" -> {
                    inTransit.add(doc.id)
                    assigned.add(doc.id)  // in_transit is also "assigned"
                    val startedAt = doc.getTimestamp("startedAt")
                    if (startedAt != null && (earliestStart == null || startedAt.seconds < earliestStart.seconds)) {
                        earliestStart = startedAt
                    }
                }
                "completed", "delivered" -> {
                    // Excluded from assignedShipments — driver is done with these.
                    // We still log them in the snapshot path so a sysu-web
                    // operator can audit "what was assigned today" via Firestore
                    // history, but the array stays lean.
                }
                else -> {
                    // "loading", "fully_loaded", "loaded", null/anything else
                    // → counts as assigned (driver has work pending)
                    assigned.add(doc.id)
                }
            }
        }

        // Sort for stable diffs (helps the Firestore write dedupe)
        assigned.sort()
        inTransit.sort()

        val updates = mutableMapOf<String, Any>(
            "assignedShipments" to assigned,
            "lastUpdated" to FieldValue.serverTimestamp(),
        )

        if (inTransit.isEmpty()) {
            // No active trip → set activeTrip = null so readers can branch on it
            updates["activeTrip"] = FieldValue.delete()
        } else {
            val activeTrip = mutableMapOf<String, Any>(
                "sapDocId" to (inTransit.firstOrNull() ?: ""),
                "sapDocIds" to inTransit,
                "updatedAt" to FieldValue.serverTimestamp(),
            )
            if (earliestStart != null) {
                activeTrip["startedAt"] = earliestStart
            }
            updates["activeTrip"] = activeTrip
        }

        syncScope.launch {
            try {
                db.collection("drivers").document(uid)
                    .set(updates, SetOptions.merge())
                    .await()
                Log.d(TAG, "✓ mirrored ${assigned.size} assigned, ${inTransit.size} in_transit → drivers/$uid")
            } catch (e: Exception) {
                Log.w(TAG, "mirror write failed for $uid: ${e.message}")
            }
        }
    }

    /**
     * Convenience helper for callers that want to ensure a single docId is in
     * the assignedShipments array (e.g. immediately after creating a new
     * sap_assignment for the driver). Writes through Firestore via
     * arrayUnion — safe to call before our listener fires.
     *
     * Currently unused but kept here for future write-paths that may not
     * trigger a snapshot (e.g. queue.assignedToDriverId server-side).
     */
    suspend fun ensureAssigned(uid: String, sapDocId: String) {
        try {
            db.collection("drivers").document(uid)
                .set(
                    mapOf(
                        "assignedShipments" to com.google.firebase.firestore.FieldValue.arrayUnion(sapDocId),
                        "lastUpdated" to FieldValue.serverTimestamp(),
                    ),
                    SetOptions.merge()
                )
                .await()
        } catch (e: Exception) {
            Log.w(TAG, "ensureAssigned($sapDocId) failed for $uid: ${e.message}")
        }
    }
}