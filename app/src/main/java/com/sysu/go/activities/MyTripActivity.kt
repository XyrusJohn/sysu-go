package com.sysu.go.activities

import android.Manifest
import android.Manifest.permission.ACCESS_FINE_LOCATION
import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import com.sysu.go.R
import com.sysu.go.activities.ObdItemsBottomSheet
import com.sysu.go.activities.GuardVerificationBottomSheet
import com.sysu.go.activities.TransitActivity
import com.sysu.go.adapter.MyTripTabAdapter
import com.sysu.go.databinding.ActivityMyTripBinding
import com.sysu.go.model.Assignment
import com.sysu.go.network.SocketManager
import com.sysu.go.utils.ImageStorageHelper
import com.sysu.go.utils.SessionManager
import com.sysu.go.services.getLastKnownLocationPair as getLocationPair
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.io.File

/**
 * MY TRIP — shows all confirmed shipments (guardVerified && driverConfirmed)
 * as cards with START TRIP buttons. Mirrors AssignmentActivity's structure.
 *
 * Red banner: bouncing circles + pulse rings (same animation as AssignmentActivity).
 * RecyclerView: one card per confirmed shipment using item_my_trip_tab_card.
 * Tapping START TRIP on a card → confirmShipment() + startSingleShipment()
 * → saves activeSapDocId → launches MyTripActivity (OBD delivery execution).
 * Tapping "VIEW OBD ITEMS" on an in_transit card → launches MyTripActivity
 * scoped to that shipment.
 *
 * Bouncing circles and pulse rings are cleaned up in onDestroy.
 */
class MyTripActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMyTripBinding
    private val db = FirebaseFirestore.getInstance()
    private val sessionManager by lazy { SessionManager(this) }

    /** Shared adapter — created once in onCreate */
    private lateinit var shipmentAdapter: MyTripTabAdapter

    /** Firestore snapshot listener removed onDestroy */
    private var assignmentsListener: com.google.firebase.firestore.ListenerRegistration? = null

    /** Bouncing circles — cancelled onDestroy */
    private val circleAnimators = mutableListOf<android.animation.Animator>()
    private val circleViews = mutableListOf<View>()

    /** Prevents double-init of bouncing circles */
    private var circlesInitialized = false

    // ─── POD Camera state ─────────────────────────────────────────────────────

    private lateinit var takePictureLauncher: ActivityResultLauncher<Uri>
    private lateinit var cameraPermissionLauncher: ActivityResultLauncher<String>
    private lateinit var locationPermissionLauncher: ActivityResultLauncher<String>
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    /** Carries the selected item+shipment while location permission is being resolved */
    private var pendingObdItem: Map<String, Any?>? = null
    /** Carries the shipment docId while location permission is being resolved */
    private var pendingObdShipmentDocId: String? = null
    /** Captured timestamp while awaiting location resolution */
    private var pendingCapturedAtMillis: Long = 0L
    // ⚠️ These three are the main camera-flow state — still used throughout the camera path
    private var pendingPodData: PendingPodData? = null
    private var pendingPhotoFile: File? = null
    private var uploadProgressDialog: android.app.AlertDialog? = null

    /** Reference to the currently-open OBD items bottom sheet, so we can refresh it in-place */
    private var currentObdBottomSheet: ObdItemsBottomSheet? = null

    // Socket.IO event listeners — registered in onResume, removed in onPause
    private val socketSapUpdatedListener: (data: Any?) -> Unit = { data ->
        // Server emits sap_updated with the full driver assignment list after any change.
        // Data shape: JSON array of assignment objects, each identical to a Firestore
        // sap_assignments document fields.
        runOnUiThread {
            if (isFinishing) return@runOnUiThread
            try {
                val list = parseSapAssignmentsFromSocket(data)
                val sessionDriverId = sessionManager.getDriverId() ?: return@runOnUiThread
                fetchDriverData(sessionDriverId, list) { enriched ->
                    enrichWithQueueData(enriched) { final ->
                        val intentDocId = intent.getStringExtra("shipment_doc_id")
                        handleAssignmentList(final, intentDocId)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "sap_updated listener parse error: ${e.message}")
            }
        }
    }

    private val socketPodUpdatedListener: (data: Any?) -> Unit = { data ->
        // Server emits pod_updated after a POD photo is successfully saved to MySQL.
        // Data shape: { "shipmentDocId": "...", "lineItems": [...] }
        runOnUiThread {
            try {
                if (data is org.json.JSONObject) {
                    val shipmentDocId = data.optString("shipmentDocId")
                    val bottomSheet = currentObdBottomSheet
                    if (bottomSheet != null && shipmentDocId.isNotBlank()) {
                        // Re-fetch fresh lineItems from Firestore (POD write succeeded, data is fresh)
                        lifecycleScope.launch {
                            try {
                                val docSnap = db.collection("sap_assignments")
                                    .document(shipmentDocId)
                                    .get()
                                    .await()
                                val fresh = docSnap.get("lineItems") as? List<Map<String, Any>>
                                if (!fresh.isNullOrEmpty()) {
                                    bottomSheet.refreshWith(fresh.filterIsInstance<Map<String, Any?>>())
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "pod_updated refresh failed: ${e.message}")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "pod_updated listener error: ${e.message}")
            }
        }
    }

    /** Holds the camera → status selection → upload pipeline state */
    private data class PendingPodData(
        val status: String,
        val lineItem: Map<String, Any?>,
        val shipmentDocId: String,
        val capturedAtMillis: Long,
        val latitude: Double? = null,
        val longitude: Double? = null
    )

    // Register camera result handler before onCreate finishes
    private fun registerCameraLauncher() {
        cameraPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { isGranted ->
            if (isGranted) {
                // Camera permission granted — proceed to camera
                pendingPodData?.let { takePicture() }
            } else {
                pendingPodData = null
                pendingPhotoFile = null
                pendingObdItem = null
                pendingObdShipmentDocId = null
                Toast.makeText(
                    this,
                    "Camera permission is required to capture POD photos.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        takePictureLauncher = registerForActivityResult(
            ActivityResultContracts.TakePicture()
        ) { success ->
            handleCameraResult(success)
        }

        // Location permission launcher — requests fine location for GPS stamp on POD.
        // Uses the captured item/shipment args stored in pendingObdItem/pendingObdShipmentDocId.
        locationPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { isGranted ->
            if (isGranted) {
                // Permission granted — do a one-shot GPS fix, then resume showObdStatusSheet
                requestFreshLocationAndProceed()
            } else {
                // Denied — proceed without location stamp
                proceedToCamera(null, null)
            }
        }
    }

    private fun showUploadProgress() {
        uploadProgressDialog = android.app.AlertDialog.Builder(this)
            .setMessage("Uploading photo…")
            .setCancelable(false)
            .create().apply { show() }
    }

    private fun hideUploadProgress() {
        runOnUiThread {
            uploadProgressDialog?.dismiss()
            uploadProgressDialog = null
        }
    }

    // ─── Location helpers for GPS stamp ───────────────────────────────────────

    /**
     * Called by the location permission launcher callback after permission is granted.
     * Attempts a fresh one-shot GPS fix via FusedLocationProviderClient, then proceeds
     * to show the OBD status sheet with the resolved lat/lng.
     *
     * If getLastLocation() returns null (no active LocationServices fix in flight),
     * registers a brief single-update callback that resolves within ~2s of a GPS lock.
     * Falls back to the LocationServices cache (getLocationPair()) if all else fails.
     */
    private fun requestFreshLocationAndProceed() {
        val capturedAtMillis = pendingCapturedAtMillis
        val item = pendingObdItem
        val shipmentDocId = pendingObdShipmentDocId
        if (item == null || shipmentDocId == null) {
            proceedToCamera(null, null)
            return
        }

        @SuppressLint("MissingPermission")
        fusedLocationClient.lastLocation.addOnSuccessListener { location ->
            Log.d(TAG, "requestFreshLocationAndProceed: getLastLocation result = $location")
            if (location != null) {
                Log.d(TAG, "Fresh GPS fix from FusedLocationProviderClient: ${location.latitude}, ${location.longitude}")
                proceedToCamera(location.latitude, location.longitude, capturedAtMillis, item, shipmentDocId)
            } else {
                // No cached fix available — request a single fresh update.
                // This takes ~1-3s after GPS lock. Register a one-shot callback.
                Log.d(TAG, "No cached fix; requesting single GPS update...")
                requestOneShotLocation(item, shipmentDocId, capturedAtMillis)
            }
        }.addOnFailureListener {
            Log.e(TAG, "getLastLocation failed: ${it.message}")
            // Fall back to LocationServices cache
            val cachePair = getLocationPair()
            proceedToCamera(
                cachePair?.first, cachePair?.second,
                capturedAtMillis, item, shipmentDocId
            )
        }
    }

    /**
     * Does a one-shot GPS update (Priority.PRIORITY_HIGH_ACCURACY, 10s timeout).
     * On success: calls proceedToCamera with fresh lat/lng.
     * On timeout or failure: falls back to LocationServices cache → proceedToCamera.
     *
     * This bridges the gap when the driver is NOT in transit mode
     * (i.e., LocationServices never ran, so getLocationPair() returned null).
     */
    @SuppressLint("MissingPermission")
    private fun requestOneShotLocation(
        item: Map<String, Any?>,
        shipmentDocId: String,
        capturedAtMillis: Long
    ) {
        val locationRequest = com.google.android.gms.location.LocationRequest.Builder(
            com.google.android.gms.location.Priority.PRIORITY_HIGH_ACCURACY,
            10000L  // interval doesn't matter for one-shot
        ).setMaxUpdates(1).setMinUpdateIntervalMillis(1000).build()

        val oneShotCallback = object : com.google.android.gms.location.LocationCallback() {
            override fun onLocationResult(result: com.google.android.gms.location.LocationResult) {
                val loc = result.lastLocation
                Log.d(TAG, "requestOneShotLocation onLocationResult: loc=$loc")
                if (loc != null) {
                    Log.d(TAG, "One-shot GPS resolve: ${loc.latitude}, ${loc.longitude}")
                    proceedToCamera(loc.latitude, loc.longitude, capturedAtMillis, item, shipmentDocId)
                } else {
                    val cached = getLocationPair()
                    proceedToCamera(cached?.first, cached?.second, capturedAtMillis, item, shipmentDocId)
                }
                fusedLocationClient.removeLocationUpdates(this)
            }
        }

        fusedLocationClient.requestLocationUpdates(
            locationRequest,
            oneShotCallback,
            mainLooper
        ).addOnFailureListener {
            Log.e(TAG, "One-shot location request failed: ${it.message}")
            val cached = getLocationPair()
            proceedToCamera(cached?.first, cached?.second, capturedAtMillis, item, shipmentDocId)
        }

        // Timeout fallback: if no GPS fix within 10s, use cache and proceed
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            try {
                fusedLocationClient.removeLocationUpdates(oneShotCallback)
            } catch (_: Exception) { }
            val cached = getLocationPair()
            proceedToCamera(cached?.first, cached?.second, capturedAtMillis, item, shipmentDocId)
        }, 10_000L)
    }

    /**
     * Ends the location-request flow by showing the OBD status bottom sheet
     * with the resolved/resolved lat/lng baked into the pendingPodData.
     */
    private fun proceedToCamera(
        lat: Double?,
        lng: Double?,
        capturedAtMillis: Long = pendingCapturedAtMillis,
        item: Map<String, Any?>? = pendingObdItem,
        shipmentDocId: String? = pendingObdShipmentDocId
    ) {
        if (item == null || shipmentDocId == null) return
        showObdStatusSheetWithLocation(item, shipmentDocId, capturedAtMillis, lat, lng)
    }

    // ─── Camera launch + handling ─────────────────────────────────────────────

    /** Creates a temp JPEG file, gets a FileProvider content:// URI, launches camera. */
    private fun takePicture(): Boolean {
        val capturedAtMillis = pendingPodData?.capturedAtMillis ?: System.currentTimeMillis()
        val picturesDir = getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES)
            ?: return false
        if (!picturesDir.exists()) picturesDir.mkdirs()

        val photoFile = File(picturesDir, "pod_${capturedAtMillis}.jpg")
        pendingPhotoFile = photoFile

        val photoUri = FileProvider.getUriForFile(
            this,
            "${packageName}.fileprovider",
            photoFile
        )

        takePictureLauncher.launch(photoUri)
        return true
    }

    /**
     * Opens ObdStatusBottomSheet (status selection), then launches the camera
     * via the registered TakePicture launcher. Handles runtime CAMERA + LOCATION
     * permissions. GPS stamp flow:
     *
     *   1. Store item/docId as pending for use after permission resolves.
     *   2. Check if ACCESS_FINE_LOCATION is already granted.
     *   3. If granted → request fresh GPS fix (FusedLocationProviderClient), or use
     *      LocationServices cache if the driver is already in transit mode.
     *   4. If not granted → request permission.
     *   5. After GPS is resolved → show ObdStatusBottomSheet with lat/lng baked
     *      into PendingPodData so the POD timestamp watermark includes the location.
     */
    private fun showObdStatusSheet(
        item: Map<String, Any?>,
        shipmentDocId: String
    ) {
        val capturedAtMillis = System.currentTimeMillis()

        // Store args so the permission-callback lambda can access them
        pendingObdItem = item
        pendingObdShipmentDocId = shipmentDocId
        pendingCapturedAtMillis = capturedAtMillis
        Log.d(TAG, "showObdStatusSheet: stored item=${item["obdNumber"]}, docId=$shipmentDocId, capturedAt=$capturedAtMillis")

        val hasLocationPermission = ContextCompat.checkSelfPermission(
            this, ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (hasLocationPermission) {
            // Permission already granted — go straight to GPS fix + show sheet
            requestFreshLocationAndProceed()
        } else {
            // First GPS permission request — show rationale toast, then request
            Toast.makeText(
                this,
                "Location is needed to stamp your POD photo with the delivery address.",
                Toast.LENGTH_LONG
            ).show()
            locationPermissionLauncher.launch(ACCESS_FINE_LOCATION)
        }
    }

    /**
     * Internal: called after a GPS fix is obtained (either from cache, one-shot fix,
     * or fallback). Shows the OBD status bottom sheet with lat/lng embedded.
     */
    private fun showObdStatusSheetWithLocation(
        item: Map<String, Any?>,
        shipmentDocId: String,
        capturedAtMillis: Long,
        lat: Double?,
        lng: Double?
    ) {
        Log.d(TAG, "showObdStatusSheetWithLocation: lat=$lat, lng=$lng, capturedAt=$capturedAtMillis, item=${item["obdNumber"]}")
        // Reverse-geocode — uses Android's on-device Geocoder, no internet needed (~30ms)
        val locationLabel: String? = if (lat != null && lng != null) {
            ImageStorageHelper.formatLocationLabel(lat, lng, this)
        } else null

        ObdStatusBottomSheet.newInstance(item, shipmentDocId).apply {
            onConfirmWithStatus = { status, lineItem, docId ->
                pendingPodData = PendingPodData(
                    status = status,
                    lineItem = lineItem,
                    shipmentDocId = docId,
                    capturedAtMillis = capturedAtMillis,
                    latitude = lat,
                    longitude = lng
                )
                Log.d(TAG, "onConfirmWithStatus: lat=$lat, lng=$lng, status=$status, obdNumber=${(lineItem["obdNumber"] as? String) ?: "?"}")

                if (ContextCompat.checkSelfPermission(
                        this@MyTripActivity,
                        Manifest.permission.CAMERA
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    val launched = takePicture()
                    if (!launched) {
                        pendingPodData = null
                        pendingPhotoFile = null
                        pendingObdItem = null
                        pendingObdShipmentDocId = null
                        Toast.makeText(
                            this@MyTripActivity,
                            "Camera not available. Please try again.",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                } else {
                    cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                }
            }
        }.show(supportFragmentManager, ObdStatusBottomSheet.TAG)
    }

    /**
     * Decodes a base64 data URI and shows it as a full-screen preview dialog.
     * Called when tapping a green thumbnail square on a completed OBD row.
     */
    /**
     * Opens a fullscreen black overlay dialog showing the POD photo.
     * The image is decoded from a base64 data URI string.
     *
     * Layout: dialog_image_preview.xml — semi-transparent black background,
     * center-fitted ImageView, accessible close (X) button top-right.
     *
     * @param base64ImageString Full base64 data URI (data:image/jpeg;base64,...).
     *                           The prefix is stripped before decoding.
     */
    private fun showPodFullScreenPreview(base64ImageString: String) {
        val tag = "showPodFullScreenPreview"
        Log.d(tag, "dataUri length=${base64ImageString.length}")

        val bitmap: Bitmap? = decodeBase64ToBitmap(base64ImageString)
        if (bitmap == null) {
            Log.e(tag, "decodeBase64ToBitmap returned null")
            Toast.makeText(this, "Could not load photo preview", Toast.LENGTH_SHORT).show()
            return
        }
        Log.d(tag, "bitmap decoded: ${bitmap.width}x${bitmap.height}")

        val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.setContentView(R.layout.dialog_image_preview)

        val previewImageView = dialog.findViewById<ImageView>(R.id.previewImageView)
            .apply { setImageBitmap(bitmap) }

        val closeBtn = dialog.findViewById<ImageButton>(R.id.closePreviewButton)
        closeBtn.setOnClickListener { dialog.dismiss() }

        // Tap anywhere on the image to dismiss
        previewImageView.setOnClickListener { dialog.dismiss() }

        dialog.show()
    }

    /**
     * Decodes a base64-encoded data URI string into a Bitmap.
     *
     * Handles the optional "data:image/jpeg;base64," prefix that Firestore
     * stores alongside the raw base64 bytes.
     *
     * @param base64Str  A base64 string, optionally prefixed with
     *                    "data:image/jpeg;base64," or similar.
     * @return Decoded Bitmap, or null on failure (bad data, OOM, etc.)
     */
    private fun decodeBase64ToBitmap(base64Str: String): Bitmap? {
        return try {
            // Strip the "data:image/jpeg;base64," prefix if present
            val cleanBase64 = if (base64Str.contains(",")) {
                base64Str.substring(base64Str.indexOf(",") + 1)
            } else {
                base64Str
            }
            val decodedBytes = Base64.decode(cleanBase64, Base64.NO_WRAP)
            BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size)
        } catch (e: Exception) {
            Log.e(TAG, "decodeBase64ToBitmap failed: ${e.message}", e)
            null
        }
    }

    private fun handleCameraResult(success: Boolean) {
        val podData = pendingPodData
        val photoFile = pendingPhotoFile

        if (!success || podData == null || photoFile == null) {
            // Camera was cancelled or failed
            pendingPodData = null
            pendingPhotoFile = null
            return
        }

        pendingPodData = null
        pendingPhotoFile = null

        showUploadProgress()

        // Decode photo to Bitmap, embed timestamp, encode and upload
        encodePodImageWithTimestamp(
            photoFile = photoFile,
            timestampMillis = podData.capturedAtMillis,
            onSuccess = { base64DataUri ->
                Log.d(TAG, "POD image base64 encoded: ${base64DataUri.length} chars")

                lifecycleScope.launch {
                    try {
                        updateObdLineItemInFirestore(
                            shipmentDocId = podData.shipmentDocId,
                            lineItem = podData.lineItem,
                            status = podData.status,
                            podImageUrl = base64DataUri,
                            capturedAtMillis = podData.capturedAtMillis
                        )
                        hideUploadProgress()

                        // ── Real-time socket broadcast ─────────────────────────
                        // Tell the server this POD was saved so it can emit pod_updated.
                        // When backend migrates to MySQL: the REST/PUT endpoint should
                        // emit pod_updated with {shipmentDocId, lineItems} to this driver's socket.
                        SocketManager.emitPodUpdated(podData.shipmentDocId)

                        // ── Immediate bottom sheet refresh ─────────────────────
                        // Two-path update guarantees the status pill updates even if the
                        // socket event is lost or arrives after the sheet is reopened.
                        //
                        // Path 1 — socket listener: socketPodUpdatedListener re-fetches
                        //          Firestore and calls bottomSheet.refreshWith() (reliable).
                        // Path 2 — showRefreshing() fallback: shows spinner and fires
                        //          onRefreshRequest, which does a direct Firestore read
                        //          and calls refreshWith (covers race conditions).
                        val bottomSheet = (supportFragmentManager.findFragmentByTag(ObdItemsBottomSheet.TAG)
                            as? ObdItemsBottomSheet)
                        bottomSheet?.showRefreshing()  // spinner ON immediately
                        bottomSheet?.onRefreshRequest?.invoke()  // triggers Firestore reload

                        Toast.makeText(
                            this@MyTripActivity,
                            "Status updated!",
                            Toast.LENGTH_SHORT
                        ).show()
                    } catch (e: Exception) {
                        hideUploadProgress()
                        Log.e(TAG, "Firestore update failed: ${e.message}")
                        Toast.makeText(
                            this@MyTripActivity,
                            "Photo captured but status save failed. Retry.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            },
            onFailure = { errorMsg ->
                hideUploadProgress()
                Log.e(TAG, "POD image processing failed: $errorMsg")
                Toast.makeText(
                    this@MyTripActivity,
                    "Photo processing failed: $errorMsg",
                    Toast.LENGTH_LONG
                ).show()
            }
        )
    }

    /**
     * Firestore transaction: finds the correct OBD item in lineItems by obdNumber
     * and atomically updates that array element with deliveryStatus, podImageUrl,
     * podCapturedAt, and isPodCompleted.
     */
    private suspend fun updateObdLineItemInFirestore(
        shipmentDocId: String,
        lineItem: Map<String, Any?>,
        status: String,
        podImageUrl: String,
        capturedAtMillis: Long
    ) {
        val obdNumber = lineItem["obdNumber"] as? String
            ?: throw IllegalArgumentException("Missing obdNumber in lineItem")

        val docRef = db.collection("sap_assignments").document(shipmentDocId)

        db.runTransaction { transaction ->
            val snap = transaction.get(docRef)
            if (!snap.exists()) {
                throw IllegalStateException("Shipment document not found: $shipmentDocId")
            }

            val lineItemsRaw = snap.get("lineItems") as? List<*> ?: emptyList<Any>()
            val lineItems = lineItemsRaw.filterIsInstance<Map<String, Any?>>().toMutableList()

            // Find and update the matching OBD item by obdNumber
            val targetIndex = lineItems.indexOfFirst { item ->
                (item["obdNumber"] as? String) == obdNumber
            }

            if (targetIndex == -1) {
                throw IllegalStateException("OBD item not found in lineItems: $obdNumber")
            }

            val original = lineItems[targetIndex].toMutableMap()
            original["deliveryStatus"] = status
            original["podImageUrl"] = podImageUrl
            original["podCapturedAt"] = Timestamp(java.util.Date(capturedAtMillis))
            original["isPodCompleted"] = true

            lineItems[targetIndex] = original
            transaction.update(docRef, "lineItems", lineItems)
        }.await()

        Log.d(TAG, "OBD lineItem updated for obdNumber=$obdNumber in doc=$shipmentDocId")
    }

    companion object {
        private const val TAG = "MyTripActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityMyTripBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // FusedLocationProviderClient for one-shot GPS fixes during POD capture
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        // Register camera result BEFORE adapter so callbacks can use it
        registerCameraLauncher()

        // Create adapter once — stored as class field so we can update state without re-creating
        shipmentAdapter = MyTripTabAdapter(
            hasActiveTrip = false,
            activeShipmentId = null,
            onTripActionClicked = { assignment -> onTripActionClicked(assignment) },
            onObdTap = { lineItems, docId -> openObdBottomSheet(lineItems, docId) },
            onShowVerification = { assignment -> showGuardVerificationSheet(assignment) }
        )

        setupRecyclerView()
        setupUI()
        startCircleAnimation()
        startPulseRings()
    }

    override fun onResume() {
        super.onResume()
        // ── Register Socket.IO listeners ──────────────────────────────────────
        // sap_updated  → rebuilds the full shipment list in real-time
        // pod_updated  → refreshes the bottom sheet in-place after POD capture
        // Both fire immediately when the backend starts emitting (after MySQL migration).
        // MyTripActivity stays in sync without needing Firestore addSnapshotListener.
        SocketManager.addEventListener("sap_updated", socketSapUpdatedListener)
        SocketManager.addEventListener("pod_updated", socketPodUpdatedListener)

        // ── Load initial data ─────────────────────────────────────────────────
        // Falls back to Firestore only if the server hasn't emitted sap_updated yet
        // (e.g., during development before backend MySQL migration is complete).
        // Once MySQL migration is done, server emits sap_updated immediately on connect.
        loadAssignments()
    }

    override fun onPause() {
        super.onPause()
        // Remove socket listeners so we don't get callbacks while paused.
        // The socket connection itself stays alive (managed by LocationServices/TransitActivity).
        SocketManager.removeEventListener("sap_updated", socketSapUpdatedListener)
        SocketManager.removeEventListener("pod_updated", socketPodUpdatedListener)
    }

    override fun onDestroy() {
        super.onDestroy()
        circleAnimators.forEach { it.cancel() }
        circleAnimators.clear()
        circleViews.forEach { binding.circleContainer.removeView(it) }
        circleViews.clear()
        assignmentsListener?.remove()
        uploadProgressDialog?.dismiss()
        uploadProgressDialog = null
    }

    // ─── UI setup ──────────────────────────────────────────────────────────────

    private fun setupUI() {
        binding.btnBack.setOnClickListener { finish() }
    }

    private fun setupRecyclerView() {
        val rv = binding.rvShipments
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = shipmentAdapter
        rv.overScrollMode = RecyclerView.OVER_SCROLL_ALWAYS
        rv.itemAnimator = androidx.recyclerview.widget.DefaultItemAnimator().apply {
            addDuration = 200
            removeDuration = 200
            moveDuration = 200
            changeDuration = 200
        }
    }

    // ─── Firestore loading (Socket.IO fallback / pre-MySQL fallback) ───────────

    /**
     * Loads assignments via Firestore snapshot listener. Used as a one-shot fallback
     * when Socket.IO hasn't delivered an initial sap_updated event yet (e.g., before
     * the backend MySQL migration is complete, or during initial app load).
     *
     * Once sap_updated events start flowing from the server, they replace all
     * subsequent Firestore updates — socket data is the source of truth post-migration.
     */
    private fun loadAssignments() {
        assignmentsListener?.remove()
        val sessionDriverId = sessionManager.getDriverId() ?: return
        val intentDocId = intent.getStringExtra("shipment_doc_id")

        val queueDocId = sessionManager.getCurrentQueueDocId()
        if (!queueDocId.isNullOrBlank()) {
            assignmentsListener = db.collection("sap_assignments")
                .whereEqualTo("driverId", queueDocId)
                .addSnapshotListener { snaps, err ->
                    if (err != null || snaps == null) {
                        loadAssignmentsFallback(sessionDriverId, intentDocId)
                        return@addSnapshotListener
                    }
                    if (isFinishing) return@addSnapshotListener
                    val list = snaps.documents.mapNotNull { snap ->
                        // Reuse buildAssignmentFromMap — .data is a Map
                        @Suppress("UNCHECKED_CAST")
                        val data = snap.data as? Map<String, Any?> ?: return@mapNotNull null
                        buildAssignmentFromMap(data + ("docId" to snap.id))
                    }
                    fetchDriverData(sessionDriverId, list) { enriched ->
                        enrichWithQueueData(enriched) { final ->
                            handleAssignmentList(final, intentDocId)
                        }
                    }
                }
        } else {
            loadAssignmentsFallback(sessionDriverId, intentDocId)
        }
    }

    private fun loadAssignmentsFallback(sessionDriverId: String, intentDocId: String?) {
        assignmentsListener = db.collection("sap_assignments")
            .whereEqualTo("driverId", sessionDriverId)
            .addSnapshotListener { snaps, err ->
                if (err != null || snaps == null) {
                    Log.e(TAG, "loadAssignments fallback error: ${err?.message}")
                    return@addSnapshotListener
                }
                if (isFinishing) return@addSnapshotListener
                val list = snaps.documents.mapNotNull { snap ->
                    @Suppress("UNCHECKED_CAST")
                    val data = snap.data as? Map<String, Any?> ?: return@mapNotNull null
                    buildAssignmentFromMap(data + ("docId" to snap.id))
                }
                fetchDriverData(sessionDriverId, list) { enriched ->
                    enrichWithQueueData(enriched) { final ->
                        handleAssignmentList(final, intentDocId)
                    }
                }
            }

    }

    /**
     * Second pass: fetch the queue document (source of truth for truckType and helper).
     * Sap_assignments only holds a driverId reference pointing to the queue doc;
     * the queue doc itself carries the canonical truckType ("AUV") and helper ("Hshs A. Dnzb").
     * Mirrors AssignmentActivity.submitToAdapter's queue lookup pattern.
     *
     * This is a fire-and-forget parallel fetch — each assignment fires its own
     * queue doc lookup; all results are collected before the onReady callback fires.
     */
    private fun enrichWithQueueData(
        assignments: List<Assignment>,
        onReady: (List<Assignment>) -> Unit
    ) {
        val empty = assignments.isEmpty()
        if (empty) { onReady(assignments); return }

        val mutableList = assignments.toMutableList()
        var pending = assignments.count { it.queueId.isNotBlank() }

        // No assignments have a queueId — skip the queue doc fetch entirely
        if (pending == 0) { onReady(assignments); return }

        for (idx in assignments.indices) {
            val assignment = assignments[idx]
            if (assignment.queueId.isBlank()) continue
            val queueId = assignment.queueId
            db.collection("queue").document(queueId).get()
                .addOnSuccessListener { queueDoc ->
                    if (queueDoc.exists()) {
                        mutableList[idx] = assignment.copy(
                            truckType = queueDoc.getString("truckType") ?: assignment.truckType,
                            helper = queueDoc.getString("helper") ?: assignment.helper
                        )
                    }
                    pending--
                    if (pending <= 0) onReady(mutableList.toList())
                }
                .addOnFailureListener {
                    pending--
                    if (pending <= 0) onReady(mutableList.toList())
                }
        }
    }

    /**
     * Fetches driver metadata from the drivers/{driverId} document:
     *   - requirements: Map<String, Boolean>  (aircon, reflectorizedVest, etc.)
     *   - truckingCompany: String             → Assignment.truckCompany (company name, separate from truckType)
     *
     * truckType ("AUV") and helper ("Hshs A. Dnzb") come from the queue document
     * in the second pass (applied after this call). This function only provides
     * the requirements map and company name.
     */
    private fun fetchDriverData(
        driverId: String,
        assignments: List<Assignment>,
        onReady: (List<Assignment>) -> Unit
    ) {
        db.collection("drivers").document(driverId)
            .get()
            .addOnSuccessListener { doc ->
                val reqMap = doc.get("requirements") as? Map<String, Boolean> ?: emptyMap()
                val truckingCompany = doc.getString("truckingCompany") ?: ""

                // Enrich each assignment with driver-sourced fields
                val enriched = assignments.map { assignment ->
                    assignment.copy(
                        requirements = reqMap,
                        // truckingCompany (driver doc) → truckCompany field only;
                        // truckType ("AUV") is preserved as-is — updated via queue doc in second pass
                        truckCompany = truckingCompany.ifBlank { assignment.truckCompany },
                        truckType = assignment.truckType,
                        helper = assignment.helper
                    )
                }
                onReady(enriched)
            }
            .addOnFailureListener {
                // Driver doc not found — proceed with sap_assignments data as-is
                onReady(assignments)
            }
    }

    private fun handleAssignmentList(list: List<Assignment>, intentDocId: String?) {
        // If launched with a specific shipment_doc_id — delegate to that shipment
        if (!intentDocId.isNullOrBlank()) {
            val target = list.find { it.docId == intentDocId }
            if (target != null) {
                loadSpecificShipment(target)
                return
            }
        }

        // Auto-navigate if any shipment is already in_transit
        val inTransit = list.find { it.tripStatus == "in_transit" }
        if (inTransit != null) {
            launchDeliveryActivity(inTransit.docId)
            return
        }

        // Show confirmed-but-not-in-transit shipments as cards
        val confirmed = list.filter {
            it.guardVerified && it.driverConfirmed && it.tripStatus != "in_transit" && it.tripStatus != "completed"
        }

        shipmentAdapter.hasActiveTrip = false
        shipmentAdapter.activeShipmentId = null
        shipmentAdapter.submitList(confirmed.toList())

        updateAggregateBadge(confirmed)
        updateEmptyState(confirmed.isEmpty(), list.isEmpty())
        binding.tvBadgeText.text = getString(R.string.my_trip_badge_trips_ready)
    }

    /**
     * Shows the shipment's card in MyTripActivity.
     * Does NOT auto-navigate — the card's action button (START TRIP or VIEW OBD ITEMS)
     * handles navigation. This prevents double-navigation when confirmAndStartTrip saves
     * tripStatus=in_transit and re-launches MyTripActivity via Intent with shipment_doc_id.
     */
    private fun loadSpecificShipment(shipment: Assignment) {
        val isInTransit = shipment.tripStatus == "in_transit"

        shipmentAdapter.hasActiveTrip = isInTransit
        shipmentAdapter.activeShipmentId = if (isInTransit) shipment.docId else null
        shipmentAdapter.submitList(listOf(shipment))

        // Do NOT auto-navigate here — let the card's action button handle it.
        // Auto-navigation only happens in handleAssignmentList for non-intentDocId cases.
        updateAggregateBadge(listOf(shipment))
        binding.tvBadgeText.text = if (isInTransit) {
            getString(R.string.cta_status_transit)
        } else {
            shipment.shipmentNumber.ifBlank { shipment.docId }
        }
        binding.emptyState.visibility = View.GONE
    }

    private fun updateEmptyState(listEmpty: Boolean, totalEmpty: Boolean) {
        if (totalEmpty) {
            // No assignments at all
            binding.rvShipments.visibility = View.GONE
            binding.emptyState.visibility = View.VISIBLE
            binding.badgeVerified.visibility = View.GONE
            binding.aggregateBadgeContainer.visibility = View.GONE
        } else if (listEmpty) {
            // Assignments exist but none confirmed yet
            binding.rvShipments.visibility = View.GONE
            binding.emptyState.visibility = View.VISIBLE
        } else {
            binding.rvShipments.visibility = View.VISIBLE
            binding.emptyState.visibility = View.GONE
        }
    }

    // ─── Per-card action ───────────────────────────────────────────────────────

    private fun onTripActionClicked(assignment: Assignment) {
        when {
            assignment.tripStatus == "in_transit" -> {
                // Open delivery execution for this shipment
                launchDeliveryActivity(assignment.docId)
            }
            assignment.guardVerified && assignment.driverConfirmed -> {
                // Confirmed but no active trip — show START TRIP dialog
                AlertDialog.Builder(this)
                    .setTitle(R.string.confirm_start_trip_title)
                    .setMessage(R.string.confirm_start_trip_message)
                    .setPositiveButton(R.string.btn_start_trip) { _, _ ->
                        confirmAndStartTrip(assignment)
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
            assignment.guardVerified && !assignment.driverConfirmed -> {
                // Guard verified but driver not yet confirmed → confirm first
                AlertDialog.Builder(this)
                    .setTitle(R.string.confirm_shipment_title)
                    .setMessage(getString(R.string.confirm_shipment_message, assignment.shipmentNumber))
                    .setPositiveButton(R.string.btn_confirm_shipment) { _, _ ->
                        confirmShipment(assignment) {
                            confirmAndStartTrip(assignment)
                        }
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
            else -> {
                // Not yet guard verified — redirect to AssignmentActivity
                Toast.makeText(this, "Please complete guard verification first.", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    private fun confirmShipment(assignment: Assignment, onSuccess: () -> Unit) {
        lifecycleScope.launch {
            try {
                val updates = mapOf("driverConfirmed" to true)
                db.collection("sap_assignments").document(assignment.docId)
                    .update(updates)
                    .await()
                Log.d(TAG, "Shipment confirmed: ${assignment.docId}")
                onSuccess()
            } catch (e: Exception) {
                Log.e(TAG, "confirmShipment error: ${e.message}")
                Toast.makeText(this@MyTripActivity, "Failed to confirm. Try again.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun confirmAndStartTrip(assignment: Assignment) {
        lifecycleScope.launch {
            try {
                // Save as active trip in session FIRST
                sessionManager.saveActiveSapDocId(assignment.docId)
                sessionManager.saveCurrentSapDocId(assignment.docId)
                sessionManager.setTripActive(true)

                val updates = mapOf(
                    "tripStatus" to "in_transit",
                    "startedAt" to Timestamp.now()
                )
                db.collection("sap_assignments").document(assignment.docId)
                    .update(updates)
                    .await()

                Log.d(TAG, "startTrip: ${assignment.docId} → in_transit")
                Toast.makeText(this@MyTripActivity, "Trip started!", Toast.LENGTH_SHORT).show()

                // Navigate to TransitActivity (live map / delivery tracking screen) — NOT MyTripActivity
                val intent = Intent(this@MyTripActivity, TransitActivity::class.java).apply {
                    putExtra("shipment_doc_id", assignment.docId)
                    flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
                startActivity(intent)
                finish()
            } catch (e: Exception) {
                Log.e(TAG, "confirmAndStartTrip error: ${e.message}")
                sessionManager.saveActiveSapDocId(null)
                sessionManager.clearCurrentSapDocId()
                sessionManager.setTripActive(false)
                Toast.makeText(this@MyTripActivity, "Failed to start trip. Check connection.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun launchDeliveryActivity(sapDocId: String) {
        sessionManager.saveCurrentSapDocId(sapDocId)
        // Launch the live map / tracking screen — NOT MyTripActivity (prevents loop)
        val intent = Intent(this, TransitActivity::class.java).apply {
            putExtra("shipment_doc_id", sapDocId)
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        startActivity(intent)
        finish()
    }

    /**
     * Opens the OBD items bottom sheet for any shipment state.
     * For in-transit shipments, tapping a row navigates to that shipment.
     * For confirmed/not-yet-in-transit shipments, items are read-only.
     * Pending OBD rows show a camera button that triggers showObdStatusSheet().
     */
    private fun openObdBottomSheet(lineItems: List<Map<String, Any>>, shipmentDocId: String) {
        if (lineItems.isEmpty()) {
            Toast.makeText(this, "No OBD items available", Toast.LENGTH_SHORT).show()
            return
        }

        val bottomSheet = ObdItemsBottomSheet.newInstance(
            lineItems = lineItems,
            shipmentDocId = shipmentDocId,
            onItemClick = { item ->
                val isCompleted = item["isPodCompleted"] as? Boolean ?: false
                val dataUri = item["podImageUrl"] as? String ?: ""
                Log.d(TAG, "onItemClick: isCompleted=$isCompleted, dataUri len=${dataUri.length}")
                if (isCompleted && dataUri.isNotBlank()) {
                    showPodFullScreenPreview(dataUri)
                }
            },
            onObdCameraTap = { item, docId ->
                showObdStatusSheet(item, docId)
            }
        ).also { sheet ->
            // SAFETY NET: if socket pod_updated never fires (race, server issue, app kill),
            // this fallback re-fetches the shipment doc directly from Firestore and calls
            // sheet.refreshWith() to update status pills in real-time without reopening the sheet.
            // Uses lifecycleScope so the coroutine outlives any UI state changes.
            sheet.onRefreshRequest = {
                Log.d(TAG, "onRefreshRequest: re-fetching lineItems for shipment=$shipmentDocId")
                lifecycleScope.launch {
                    try {
                        val docSnap = db.collection("sap_assignments")
                            .document(shipmentDocId)
                            .get()
                            .await()
                        @Suppress("UNCHECKED_CAST")
                        val fresh = docSnap.get("lineItems") as? List<Map<String, Any?>>
                        if (!fresh.isNullOrEmpty()) {
                            sheet.refreshWith(fresh)
                            Log.d(TAG, "onRefreshRequest: refreshWith called, ${fresh.size} items")
                        } else {
                            Log.w(TAG, "onRefreshRequest: lineItems was null/empty in Firestore")
                            sheet.hideRefreshing()
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "onRefreshRequest: Firestore reload failed: ${e.message}")
                        sheet.hideRefreshing()
                    }
                }
            }
        }

        currentObdBottomSheet = bottomSheet
        bottomSheet.show(supportFragmentManager, ObdItemsBottomSheet.TAG)
    }

    /**
     * Fetches current guard verification data from Firestore and shows the
     * GuardVerificationBottomSheet in read-only mode.
     * Safe to call even if the data is stale in the local Assignment object.
     */
    private fun showGuardVerificationSheet(assignment: Assignment) {
        lifecycleScope.launch {
            try {
                val docSnap = db.collection("sap_assignments")
                    .document(assignment.docId)
                    .get()
                    .await()

                if (!docSnap.exists()) {
                    Toast.makeText(this@MyTripActivity, "Verification data not found", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                val firstName = docSnap.getString("guardFirstName") ?: assignment.guardFirstName
                val lastName  = docSnap.getString("guardLastName")  ?: assignment.guardLastName
                val mi        = docSnap.getString("guardMI") ?: ""
                val sigBase64 = docSnap.getString("guardSignatureBase64")
                    ?: assignment.guardSignature.ifBlank { docSnap.getString("guardSignature") ?: "" }

                val ts = docSnap.getTimestamp("guardVerifiedAt")
                val verifiedAt = ts?.toDate()?.time ?: 0L

                GuardVerificationBottomSheet.newInstance(
                    firstName  = firstName,
                    lastName   = lastName,
                    mi         = mi,
                    signature  = sigBase64,
                    verifiedAt = verifiedAt
                ).apply {
                    onSubmitConfirmed = { }  // read-only; guard already confirmed
                }.show(supportFragmentManager, GuardVerificationBottomSheet.TAG)
            } catch (e: Exception) {
                Log.e(TAG, "showGuardVerificationSheet error: ${e.message}")
                Toast.makeText(this@MyTripActivity, "Failed to load verification details", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ─── Assignment builder ────────────────────────────────────────────────────

    /**
     * Builds Assignment from a Map (used by both Firestore DocumentSnapshot
     * and Socket.IO JSON data — the data shape is identical).
     * Handles docId differently:
     *   - Firestore path: snap.id (auto-generated Firestore document ID)
     *   - Socket path   : data["docId"] explicitly sent by server
     */
    private fun buildAssignmentFromMap(data: Map<String, Any?>): Assignment? {
        return try {
            // docId: Firestore sends it via snap.id; socket sends it in the data map as "docId"
            val queueId = data["driverId"] as? String ?: ""
            val tripStatus = data["tripStatus"] as? String
            val guardVerified = data["guardVerified"] as? Boolean ?: false
            val driverConfirmed = data["driverConfirmed"] as? Boolean ?: false
            val guardFirstName = data["guardFirstName"] as? String ?: ""
            val guardLastName = data["guardLastName"] as? String ?: ""
            val guardSignature = data["guardSignature"] as? String ?: ""
            val shipmentNumberRaw = data["shipmentNumber"] as? String ?: ""

            val lineItemsList = data["lineItems"] as? List<*> ?: emptyList<Any>()
            val lineItems = lineItemsList.filterIsInstance<Map<String, Any?>>()
            val firstItem = lineItems.firstOrNull()
            val delTsRaw = firstItem?.get("actualDelDate") as? String
            val deliveryTs = try {
                delTsRaw?.removePrefix("/Date(")?.removeSuffix(")/")?.toLong() ?: 0L
            } catch (e: Exception) { 0L }

            val obdCount = lineItems.size
            val obdNumber = firstItem?.get("obdNumber") as? String ?: ""

            Assignment(
                docId = (data["docId"] as? String) ?: queueId, // socket sends "docId", fallback to driverId
                queueId = queueId,
                driverId = sessionManager.getDriverId() ?: "",
                status = data["status"] as? String ?: "",
                tripStatus = tripStatus,
                plateNumber = data["truckPlate"] as? String ?: "",
                truckType = data["truckType"] as? String ?: "",
                truckCompany = "",
                helper = data["helper"] as? String ?: "",
                guardVerified = guardVerified,
                driverConfirmed = driverConfirmed,
                guardFirstName = guardFirstName,
                guardLastName = guardLastName,
                guardSignature = guardSignature,
                deliveryTimestamp = deliveryTs,
                obdCount = obdCount,
                requirements = emptyMap(),
                lineItems = lineItems as List<Map<String, Any>>,
                obdNumber = obdNumber,
                shipmentNumberRaw = shipmentNumberRaw
            )
        } catch (e: Exception) {
            Log.e(TAG, "buildAssignmentFromMap error: ${e.message}")
            null
        }
    }

    private fun parseSapAssignmentsFromSocket(data: Any?): List<Assignment> {
        return when (data) {
            is org.json.JSONArray -> {
                val result = mutableListOf<Assignment>()
                for (i in 0 until data.length()) {
                    val obj = data.getJSONObject(i)
                    val map = mutableMapOf<String, Any?>()
                    obj.keys().forEach { key ->
                        val value = obj.get(key)
                        map[key] = if (value == org.json.JSONObject.NULL) null else value
                    }
                    buildAssignmentFromMap(map)?.let { result.add(it) }
                }
                result
            }
            is org.json.JSONObject -> {
                // Single object (edge case — server sent one assignment)
                val map = mutableMapOf<String, Any?>()
                data.keys().forEach { key ->
                    val value = data.get(key)
                    map[key] = if (value == org.json.JSONObject.NULL) null else value
                }
                listOfNotNull(buildAssignmentFromMap(map))
            }
            else -> {
                Log.w(TAG, "sap_updated: unexpected data type ${data?.javaClass?.name}")
                emptyList()
            }
        }
    }

    // ─── Badge updates ──────────────────────────────────────────────────────────

    private fun updateAggregateBadge(list: List<Assignment>) {
        if (list.isEmpty()) {
            binding.aggregateBadgeContainer.visibility = View.GONE
            binding.badgeVerified.visibility = View.GONE
            return
        }
        binding.aggregateBadgeContainer.visibility = View.VISIBLE
        binding.badgeVerified.visibility = View.VISIBLE

        // Count any in_transit shipment
        val inTransitCount = list.count { it.tripStatus == "in_transit" }

        if (inTransitCount > 0) {
            // Active trip running
            binding.aggregateBadgeContainer.setBackgroundResource(R.drawable.bg_status_pill_green)
            binding.aggregateBadgeDot.setBackgroundResource(R.drawable.bg_status_dot_green)
            binding.tvAggregateBadge.text = "$inTransitCount in transit"
            binding.tvAggregateBadge.setTextColor(ContextCompat.getColor(this, R.color.white))

            binding.badgeVerified.setBackgroundResource(R.drawable.bg_status_pill_green)
            binding.badgeDot.setBackgroundResource(R.drawable.bg_status_dot_green)
            binding.tvBadgeText.text = getString(R.string.cta_status_transit)
        } else {
            // Confirmed, ready to start (list already filtered to confirmed-only)
            binding.aggregateBadgeContainer.setBackgroundResource(R.drawable.bg_status_pill_green)
            binding.aggregateBadgeDot.setBackgroundResource(R.drawable.bg_status_dot_green)
            binding.tvAggregateBadge.text = "${list.size} ready"
            binding.tvAggregateBadge.setTextColor(ContextCompat.getColor(this, R.color.white))

            binding.badgeVerified.setBackgroundResource(R.drawable.bg_status_pill_green)
            binding.badgeDot.setBackgroundResource(R.drawable.bg_status_dot_green)
            binding.tvBadgeText.text = getString(R.string.my_trip_badge_trips_ready)
        }
    }

    // ═══ Bouncing Circles + Pulse Rings (AssignmentActivity pattern) ═══════

    private fun startCircleAnimation() {
        if (circlesInitialized) return
        circlesInitialized = true

        binding.circleContainer.post {
            val containerW = binding.circleContainer.width
            val containerH = binding.circleContainer.height
            if (containerW == 0 || containerH == 0) return@post

            val random = java.util.Random()
            val count = random.nextInt(4) + 3  // 3–6 circles

            for (i in 0 until count) {
                val sizeDp = 28 + random.nextInt(61)
                val sizePx = (sizeDp * resources.displayMetrics.density).toInt()
                val startX = random.nextInt(containerW).toFloat()
                val startY = random.nextInt(containerH).toFloat()
                val minSpeed = 0.12f + random.nextFloat() * 0.15f
                val maxSpeed = minSpeed + 0.15f + random.nextFloat() * 0.2f
                val alpha = 0.25f + random.nextFloat() * 0.3f

                val circle = View(this).apply {
                    layoutParams = FrameLayout.LayoutParams(sizePx, sizePx)
                    background = ContextCompat.getDrawable(this@MyTripActivity, R.drawable.bg_circle_light_red)
                    this.alpha = alpha
                }
                binding.circleContainer.addView(circle)
                circleViews.add(circle)

                startBounceAnimation(
                    view = circle,
                    viewSize = sizePx,
                    containerWidth = containerW,
                    containerHeight = containerH,
                    minSpeed = minSpeed,
                    maxSpeed = maxSpeed,
                    initialX = startX,
                    initialY = startY,
                    startDelayMs = (i * 150L) + random.nextInt(500)
                )
            }
        }
    }

    private fun startPulseRings() {
        startPulseRing(binding.pulseRing1, 0L)
        startPulseRing(binding.pulseRing2, 1200L)
    }

    private fun startPulseRing(ring: View, delayMs: Long) {
        ring.visibility = View.VISIBLE
        ring.alpha = 0f

        val scaleUp = android.animation.ObjectAnimator.ofFloat(ring, "scaleX", 0.3f, 1.5f).apply {
            duration = 2000L
            repeatMode = android.animation.ObjectAnimator.RESTART
            repeatCount = android.animation.ValueAnimator.INFINITE
            startDelay = delayMs
        }
        val scaleUpY = android.animation.ObjectAnimator.ofFloat(ring, "scaleY", 0.3f, 1.5f).apply {
            duration = 2000L
            repeatMode = android.animation.ObjectAnimator.RESTART
            repeatCount = android.animation.ValueAnimator.INFINITE
            startDelay = delayMs
        }
        val fadeOut = android.animation.ObjectAnimator.ofFloat(ring, "alpha", 0.6f, 0f).apply {
            duration = 2000L
            repeatMode = android.animation.ObjectAnimator.RESTART
            repeatCount = android.animation.ValueAnimator.INFINITE
            startDelay = delayMs
        }

        circleAnimators.addAll(listOf(scaleUp, scaleUpY, fadeOut))
        scaleUp.start()
        scaleUpY.start()
        fadeOut.start()
    }

    private fun startBounceAnimation(
        view: View,
        viewSize: Int,
        containerWidth: Int,
        containerHeight: Int,
        minSpeed: Float,
        maxSpeed: Float,
        initialX: Float,
        initialY: Float,
        startDelayMs: Long
    ) {
        val random = java.util.Random()
        val maxX = (containerWidth - viewSize).toFloat().coerceAtLeast(0f)
        val maxY = (containerHeight - viewSize).toFloat().coerceAtLeast(0f)

        view.translationX = initialX
        view.translationY = initialY

        var velX = (random.nextFloat() * (maxSpeed - minSpeed) + minSpeed) *
            if (random.nextBoolean()) 1f else -1f
        var velY = (random.nextFloat() * (maxSpeed - minSpeed) + minSpeed) *
            if (random.nextBoolean()) 1f else -1f

        android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = Long.MAX_VALUE
            repeatCount = android.animation.ValueAnimator.INFINITE
            startDelay = startDelayMs
            addUpdateListener {
                if (random.nextFloat() < 0.02f) {
                    velX += (random.nextFloat() - 0.5f) * 0.4f
                    velX = velX.coerceIn(-maxSpeed, maxSpeed)
                }
                if (random.nextFloat() < 0.02f) {
                    velY += (random.nextFloat() - 0.3f) * 0.4f
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
            repeatCount = android.animation.ObjectAnimator.INFINITE
            repeatMode = android.animation.ObjectAnimator.REVERSE
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            startDelay = startDelayMs
            circleAnimators.add(this)
            start()
        }

        android.animation.ObjectAnimator.ofFloat(view, "scaleY", 1f, 1.06f).apply {
            duration = 4500L + random.nextInt(1500)
            repeatCount = android.animation.ObjectAnimator.INFINITE
            repeatMode = android.animation.ObjectAnimator.REVERSE
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            startDelay = startDelayMs + 600L
            circleAnimators.add(this)
            start()
        }
    }

    /**
     * Decodes a photo file to Bitmap (with inSampleSize to avoid OOM), embeds a
     * timestamp overlay using capturedAtMillis, then passes the stamped bitmap
     * to ImageStorageHelper for compression and base64-encoding.
     *
     * Mirrors the two-step decode → process flow already in ImageStorageHelper,
     * separated here so the PhotoFile overload can participate in timestamp
     * embedding without duplicating ImageStorageHelper internals.
     */
    private fun encodePodImageWithTimestamp(
        photoFile: File,
        timestampMillis: Long,
        onSuccess: (String) -> Unit,
        onFailure: (String) -> Unit
    ) {
        val boundOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(photoFile.absolutePath, boundOptions)

        val sampleSize = calculateInSampleSizeForFile(boundOptions, 1280, 1280)
        val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }

        val bitmap: Bitmap? = try {
            BitmapFactory.decodeFile(photoFile.absolutePath, decodeOptions)
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "OOM decoding photo for timestamp encoding: ${photoFile.name}", e)
            null
        } catch (e: Exception) {
            Log.e(TAG, "decode failed during timestamp encoding: ${e.message}", e)
            null
        }

        if (bitmap == null) {
            onFailure("Failed to load photo for timestamp embedding. Please try again.")
            return
        }

        // Fetch lat/lng from pendingPodData before entering a lambda chain (smart-cast
        // is not available inside a let lambda as pendingPodData is captured).
        val podLat: Double? = pendingPodData?.latitude
        val podLng: Double? = pendingPodData?.longitude
        Log.d(TAG, "encodePodImageWithTimestamp: podLat=$podLat, podLng=$podLng, timestampMillis=$timestampMillis")
        val locationLabel: String? = podLat?.let { lat ->
            podLng?.let { lng ->
                ImageStorageHelper.formatLocationLabel(lat, lng, this@MyTripActivity)
            }
        }
        Log.d(TAG, "encodePodImageWithTimestamp: locationLabel=${locationLabel ?: "null"}")

        ImageStorageHelper.uploadPodImage(
            bitmap = bitmap,
            timestampMillis = timestampMillis,
            context = this,
            onSuccess = { encoded ->
                bitmap.recycle()
                onSuccess(encoded)
            },
            onFailure = { errorMsg ->
                bitmap.recycle()
                onFailure(errorMsg)
            },
            locationLabel = locationLabel
        )
    }

    /**
     * Copy of BitmapFactory's calculateInSampleSize for use with File decode path.
     * Mirrors the logic already in ImageStorageHelper.decodeAndResize() — kept here
     * so encodePodImageWithTimestamp is fully self-contained.
     */
    private fun calculateInSampleSizeForFile(
        options: BitmapFactory.Options,
        reqWidth: Int,
        reqHeight: Int
    ): Int {
        val (height, width) = options.outHeight to options.outWidth
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    // Stub for compatibility — the original MyTripActivity had these UI helpers
    // but the new design doesn't need them in this screen
    private val isActivityFinished: Boolean
        get() = isFinishing || isDestroyed
}