package com.sysu.go

import android.app.Application
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.sysu.go.utils.DriverShipmentSync

/**
 * Application singleton. Runs before any Activity.
 *
 * Critical: Enable Firestore offline persistence ONCE here. Without this,
 * Firestore SDK writes (like `drivers/{uid}.status = "offline"`) are
 * dropped immediately when the network is unreachable — even if the
 * data is in-flight. With persistence enabled:
 *   - Writes go to local cache (Room/SQLite) first
 *   - Firestore SDK retries the write when network/DNS recovers
 *   - The cached value is what reads return offline
 *
 * This is the missing piece that explains why `drivers/{uid}.status` was
 * stuck at "online" even though the app correctly detected the offline
 * state: the Firestore write was failing with `UnknownHostException`
 * and the SDK had no local queue to fall back to.
 */
class GoApplication : Application() {
    private val TAG = "GoApplication"

    override fun onCreate() {
        super.onCreate()
        val firebaseApp = com.google.firebase.FirebaseApp.getInstance()
        com.sysu.go.config.EnvironmentPolicy.validate(BuildConfig.DEBUG, firebaseApp.options.projectId,
            BuildConfig.API_BASE_URL, BuildConfig.SOCKET_URL, BuildConfig.FRONTEND_URL)
        // Configure every SDK before AuthManager, activities or listeners can use it.
        if (BuildConfig.DEBUG) {
            FirebaseAuth.getInstance().useEmulator("10.0.2.2", 9099)
            FirebaseFirestore.getInstance().useEmulator("10.0.2.2", 8085)
            com.google.firebase.storage.FirebaseStorage.getInstance().useEmulator("10.0.2.2", 9199)
        }
        try {
            val settings = FirebaseFirestoreSettings.Builder()
                .setPersistenceEnabled(true)
                // Real-time listeners (WatchStream) cache locally with persistence
                // so reads offline return the last-known value. Writes queue in
                // local cache and flush when network returns.
                .setCacheSizeBytes(FirebaseFirestoreSettings.CACHE_SIZE_UNLIMITED)
                .build()
            FirebaseFirestore.getInstance().firestoreSettings = settings
            Log.d(TAG, "Firestore offline persistence enabled")
        } catch (e: Exception) {
            // Persistence can only be set before any other Firestore use. If another
            // module already initialized Firestore without persistence, this will
            // throw IllegalStateException — log and continue. The cost: no offline
            // queue, but the app still works when online.
            Log.e(TAG, "Failed to enable Firestore persistence: ${e.message}")
        }

        // Initialize FirebaseAuthManager globally
        try {
            com.sysu.go.firebase.FirebaseAuthManager.initialize(this)
            Log.d(TAG, "FirebaseAuthManager initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing FirebaseAuthManager: ${e.message}", e)
        }

        // Initialize Global Strict Permission Monitor across all Activities
        com.sysu.go.utils.StrictPermissionMonitor.initialize(this)

        // Note: DriverShipmentSync is intentionally deprecated and removed.
        // Active queue state, shipments, and capacity now live strictly in the `queue` collection.
        // `drivers/{uid}` is preserved as a clean, static driver profile document.
    }
}
