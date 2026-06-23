package com.sysu.go.activities

import android.Manifest
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.io.File

/**
 * MY TRIP — shows all confirmed shipments (guardVerified && driverConfirmed)
 * as cards with START TRIP buttons. Mirrors AssignmentActivity's structure.
 *
 * Red banner: bouncing circles + pulse rings (same animation as AssignmentActivity).
 * RecyclerView: one card per confirmed shipment using item_my_trip_tab_card.
 *
 * Navigation flow:
 *   - START TRIP (confirmed, not yet in_transit) → confirmShipment() + confirmAndStartTrip()
 *     → saves session state → launches TransitActivity (live map / OBD delivery execution).
 *   - Tapping "VIEW OBD ITEMS" on an in_transit card → openObdBottomSheet() → ObdItemsBottomSheet.
 *
 * Real-time sync:
 *   - sap_updated socket event rebuilds the full shipment list (primary source of truth).
 *   - pod_updated socket event refreshes the open ObdItemsBottomSheet in-place.
 *   - Intent-shipped shipment_doc_id is consumed once on first load, then cleared so
   socket updates show the full list (prevents UI from being locked to one shipment).
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
    private var pendingPodData: PendingPodData? = null
    private var pendingPhotoFile: File? = null
    private var uploadProgressDialog: android.app.AlertDialog? = null

    /** Reference to the currently-open OBD items bottom sheet, so we can refresh it in-place */
    private var currentObdBottomSheet: ObdItemsBottomSheet? = null

    /**
     * Cached copy of the launching shipment_doc_id.
     *
     * Captured ONCE in onCreate and consumed by the first handleAssignmentList() call
     * (via loadAssignments / socketSapUpdatedListener), causing loadSpecificShipment() to
     * render that single shipment. After that first load, it is cleared (set to null) so
     * that all subsequent sap_updated socket events render the full list — not a single
     * locked shipment.
     *
     * Without this, every sap_updated re-reads intent.getStringExtra() and every
     * handleAssignmentList call does loadSpecificShipment() → UI stays stuck on one
     * shipment indefinitely.
     */
    private var intentShipmentDocId: String? = null

    /**
     * Clears intentShipmentDocId after the first handleAssignmentList call.
     * After this is called, subsequent handleAssignmentList calls receive null
     * so sap_updated events render the full shipment list, not a single shipment.
     */
    private fun consumeIntentDocId() {
        if (intentShipmentDocId != null) {
            Log.d(TAG, "consumeIntentDocId: consumed=${intentShipmentDocId}, subsequent updates will show full list")
        }
        intentShipmentDocId = null
    }

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
                        // intentShipmentDocId is consumed on first load only — see onCreate init.
                        handleAssignmentList(final, intentShipmentDocId)
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
                        // Show loading feedback immediately so driver knows data is refreshing
                        bottomSheet.showRefreshing()
                        lifecycleScope.launch {
                            try {
                                val docSnap = db.collection("sap_assignments")
                                    .document(shipmentDocId)
                                    .get()
                                    .await()
                                val fresh = docSnap.get("lineItems") as? List<Map<String, Any>>
                                if (!fresh.isNullOrEmpty()) {
                                    bottomSheet.refreshWith(fresh.filterIsInstance<Map<String, Any?>>(), null)
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "pod_updated refresh failed: ${e.message}")
                                bottomSheet.hideRefreshing()
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
        val capturedAtMillis: Long
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
     * via the registered TakePicture launcher. Handles runtime CAMERA permission.
     */
    private fun showObdStatusSheet(
        item: Map<String, Any?>,
        shipmentDocId: String
    ) {
        val capturedAtMillis = System.currentTimeMillis()

        ObdStatusBottomSheet.newInstance(item, shipmentDocId).apply {
            onConfirmWithStatus = { status, lineItem, docId ->
                pendingPodData = PendingPodData(
                    status = status,
                    lineItem = lineItem,
                    shipmentDocId = docId,
                    capturedAtMillis = capturedAtMillis
                )

                if (ContextCompat.checkSelfPermission(
                        this@MyTripActivity,
                        Manifest.permission.CAMERA
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    val launched = takePicture()
                    if (!launched) {
                        pendingPodData = null
                        pendingPhotoFile = null
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
        }.show(supportFragmentManager, ObdItemsBottomSheet.TAG)
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

        Log.d(TAG, "handleCameraResult: success=$success, podData=${podData != null}, photoFile=${photoFile?.exists()}")

        fun clearPending() {
            pendingPodData = null
            pendingPhotoFile = null
        }

        // ── Early exit: camera cancelled or state corrupted ────────────────────
        if (!success || podData == null || photoFile == null) {
            Log.d(TAG, "handleCameraResult: early-exit — camera cancelled or state null")
            clearPending()
            return
        }

        Log.d(TAG, "  → podData: shipmentDocId=${podData.shipmentDocId}, obdNumber=${podData.lineItem["obdNumber"]}, status=${podData.status}")

        showUploadProgress()

        // ── Upload + Firestore update with retry ──────────────────────────────
        // Decode the photo file to a Bitmap, resized to 1280px max dimension
        val boundOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(photoFile.absolutePath, boundOptions)

        encodePodImageWithTimestamp(
            photoFile = photoFile,
            boundOptions = boundOptions,
            timestampMillis = podData.capturedAtMillis,
            onSuccess = { dataUri ->
                Log.d(TAG, "POD image encoded SUCCESS: ${dataUri.length} chars. Proceeding to Firestore update...")
                Log.d(TAG, "  → shipmentDocId=${podData.shipmentDocId}, obdNumber=${podData.lineItem["obdNumber"]}, status=${podData.status}")

                lifecycleScope.launch {
                    var attempts = 0
                    val maxAttempts = MAX_FIRESTORE_RETRY_ATTEMPTS
                    var lastError: Exception? = null

                    // Retry loop: transient Firestore errors (network blip, timeout) don't have to mean failure
                    while (attempts < maxAttempts) {
                        attempts++
                        try {
                            updateObdLineItemInFirestore(
                                shipmentDocId = podData.shipmentDocId,
                                lineItem = podData.lineItem,
                                status = podData.status,
                                podImageUrl = dataUri,
                                capturedAtMillis = podData.capturedAtMillis
                            )
                            lastError = null
                            break  // success
                        } catch (e: Exception) {
                            lastError = e
                            Log.w(TAG, "  Firestore update attempt $attempts/$maxAttempts failed: ${e.message}")
                            if (attempts < maxAttempts) {
                                val delay = RETRY_DELAY_BASE_MS * (1 shl (attempts - 1))  // exponential backoff
                                Log.d(TAG, "  Retrying in ${delay}ms...")
                                kotlinx.coroutines.delay(delay)
                            }
                        }
                    }

                    hideUploadProgress()
                    deletePhotoFile(photoFile)  // clean up temp file on success or permanent failure

                    if (lastError != null) {
                        // All retries exhausted — show specific error
                        Log.e(TAG, "❌ PodUpload all $maxAttempts retries FAILED: ${lastError?.message}", lastError)
                        showToast("Photo captured but save failed (${lastError?.message}). Please try again.")
                    } else {
                        // ── Success path ──────────────────────────────────────
                        Log.d(TAG, "✅ POD saved successfully for obdNumber=${podData.lineItem["obdNumber"]}")

                        // Immediate Firestore re-fetch — the Firestore write is ALREADY DONE
                        // so data is fresh. Trigger showRefreshing immediately so driver sees feedback
                        // even on fast connections. Then update UI when data arrives.
                        val affectedObdNumber = podData.lineItem["obdNumber"]?.toString() ?: ""
                        val sheet = currentObdBottomSheet

                        if (sheet != null) {
                            Log.d(TAG, "↻ Bottom sheet found — triggering showRefreshing + Firestore re-fetch for OBD=$affectedObdNumber")
                            sheet.showRefreshing(affectedObdNumber)
                            lifecycleScope.launch {
                                try {
                                    val docSnap = db.collection("sap_assignments")
                                        .document(podData.shipmentDocId)
                                        .get()
                                        .await()
                                    @Suppress("UNCHECKED_CAST")
                                    val fresh = docSnap.get("lineItems") as? List<Map<String, Any?>>
                                    if (!fresh.isNullOrEmpty()) {
                                        Log.d(TAG, "  ↻ Firestore re-fetch done — ${fresh.size} items, calling refreshWith")
                                        sheet.refreshWith(fresh, affectedObdNumber)
                                    } else {
                                        Log.d(TAG, "  ↻ Firestore re-fetch: empty — calling refreshWith with empty list")
                                        sheet.refreshWith(emptyList(), affectedObdNumber)
                                    }
                                } catch (e: Exception) {
                                    Log.e(TAG, "  ❌ Firestore re-fetch FAILED: ${e.message}")
                                    sheet.refreshWith(emptyList(), affectedObdNumber)
                                }
                            }
                        } else {
                            // Bottom sheet not open — try to find it by tag (cross-check)
                            Log.d(TAG, "⚠ currentObdBottomSheet is null — trying findFragmentByTag fallback")
                            try {
                                val fallback = supportFragmentManager.findFragmentByTag(ObdItemsBottomSheet.TAG) as? ObdItemsBottomSheet
                                if (fallback != null) {
                                    Log.d(TAG, "↻ Fallback found sheet — same refresh cascade")
                                    fallback.showRefreshing(affectedObdNumber)
                                    lifecycleScope.launch {
                                        try {
                                            val docSnap = db.collection("sap_assignments")
                                                .document(podData.shipmentDocId)
                                                .get()
                                                .await()
                                            @Suppress("UNCHECKED_CAST")
                                            val fresh = docSnap.get("lineItems") as? List<Map<String, Any?>>
                                            if (!fresh.isNullOrEmpty()) {
                                                fallback.refreshWith(fresh, affectedObdNumber)
                                            } else {
                                                fallback.refreshWith(emptyList(), affectedObdNumber)
                                            }
                                        } catch (e: Exception) {
                                            Log.e(TAG, "Fallback Firestore FAILED: ${e.message}")
                                            fallback.refreshWith(emptyList(), affectedObdNumber)
                                        }
                                    }
                                } else {
                                    Log.w(TAG, "❌ NO bottom sheet found — refresh skipped (was sheet closed?)")
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "❌ Bottom sheet refresh error: ${e.message}")
                            }
                        }

                        SocketManager.emitPodUpdated(podData.shipmentDocId)
                        showToast("Status updated!")
                    }

                    clearPending()
                }
            },
            onFailure = { errorMsg ->
                Log.e(TAG, "❌ POD image encoding FAILED: $errorMsg")
                hideUploadProgress()
                deletePhotoFile(photoFile)
                clearPending()
                showToast("Photo processing failed: $errorMsg")
            }
        )
        // Note: pending state is cleared inside each exit path above (clearPending())
        // Do NOT set pendingPodData/pendingPhotoFile = null here — the async callbacks own this.
    }

    /**
     * Deletes the temp photo file after upload completes or fails.
     * Quietly fails if the file is already gone (benign).
     */
    private fun deletePhotoFile(file: File?) {
        file?.let {
            try {
                if (it.exists()) {
                    val deleted = it.delete()
                    Log.d(TAG, "deletePhotoFile: ${if (deleted) "deleted" else "FAILED to delete"} ${it.name}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "deletePhotoFile: failed to delete ${it.name}: ${e.message}")
            }
        }
    }

    /** Shortcut to show toast from any thread (runOnUiThread). */
    private fun showToast(message: String) {
        runOnUiThread { Toast.makeText(this@MyTripActivity, message, Toast.LENGTH_LONG).show() }
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
        // Type-safe obdNumber extraction — handles Number (Long/Integer) from raw data.
        val rawObdNumber = lineItem["obdNumber"]
        val obdNumber = when (rawObdNumber) {
            is String -> rawObdNumber.trim()
            is Number -> rawObdNumber.toString().trim()
            else -> (rawObdNumber as? String)?.trim() ?: ""
        }
        if (obdNumber.isBlank()) {
            throw IllegalArgumentException("Missing/empty obdNumber in lineItem. Actual type=${rawObdNumber?.javaClass?.name}, value=$rawObdNumber")
        }

        Log.d(TAG, "updateObdLineItemInFirestore: docId=$shipmentDocId, searching obdNumber=[$obdNumber]")

        val docRef = db.collection("sap_assignments").document(shipmentDocId)

        db.runTransaction { transaction ->
            val snap = transaction.get(docRef)
            if (!snap.exists()) {
                throw IllegalStateException("Shipment document not found in Firestore: $shipmentDocId")
            }

            @Suppress("UNCHECKED_CAST")
            val lineItemsRaw = snap.get("lineItems") as? List<*> ?: emptyList<Any>()
            val lineItems = lineItemsRaw.toMutableList()

            Log.d(TAG, "  Firestore lineItems count=${lineItems.size}")

            // Safely coerce any numeric or string obdNumber to comparable String.
            // Handles Firestore storing obdNumber as Integer/Long (from numeric data import).
            fun coerceToObdString(value: Any?): String = when (value) {
                is String -> value.trim()
                is Number -> value.toString().trim()
                else -> (value as? String)?.trim() ?: ""
            }

            // ── Diagnostic: log every obdNumber in Firestore with its actual type ──
            for (rawItem in lineItems) {
                val item = rawItem as? Map<String, Any?> ?: continue
                val rawNn = item["obdNumber"]
                Log.d(TAG, "  [OBD] value=${rawNn} type=${rawNn?.javaClass?.name} coerced=[${coerceToObdString(rawNn)}]")
            }

            // Find matching OBD item: string comparison after type-safe coercion
            val targetIndex = lineItems.indexOfFirst { rawItem ->
                val item = rawItem as? Map<String, Any?> ?: return@indexOfFirst false
                coerceToObdString(item["obdNumber"]) == obdNumber
            }

            if (targetIndex == -1) {
                val allObdNumbers = lineItems.mapNotNull { raw ->
                    (raw as? Map<String, Any?>)?.get("obdNumber")?.let { coerceToObdString(it) }.takeIf { it?.isNotBlank() == true }
                }
                Log.e(TAG, "  OBD item NOT FOUND. Looking for=[$obdNumber], available=$allObdNumbers")
                throw IllegalStateException("OBD item not found: $obdNumber — available=$allObdNumbers")
            }

            @Suppress("UNCHECKED_CAST")
            val rawItem = lineItems[targetIndex] as? Map<String, Any?>
                ?: throw IllegalStateException("lineItems[$targetIndex] is not a Map, actual type=${lineItems[targetIndex]?.javaClass?.name}")
            val original = rawItem.toMutableMap()

            original["deliveryStatus"] = status
            original["podImageUrl"] = podImageUrl
            original["podCapturedAt"] = Timestamp(java.util.Date(capturedAtMillis))
            original["isPodCompleted"] = true

            lineItems[targetIndex] = original
            transaction.update(docRef, "lineItems", lineItems)

            Log.d(TAG, "  ✅ Transaction updated lineItems[$targetIndex] for obdNumber=[$obdNumber]")
        }.await()

        Log.d(TAG, "updateObdLineItemInFirestore: SUCCESS for obdNumber=[$obdNumber] in doc=$shipmentDocId")
    }

    companion object {
        private const val TAG = "MyTripActivity"
        private const val MAX_FIRESTORE_RETRY_ATTEMPTS = 3  // retry Firestore transaction up to 3 times
        private const val RETRY_DELAY_BASE_MS = 1500L       // 1.5s base delay, exponential backoff (1.5s, 3s, 6s)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityMyTripBinding.inflate(layoutInflater)
        setContentView(binding.root)

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

        // Capture and consume the launching shipment_doc_id ONCE.
        // Cleared after the first load so socket updates show the full list.
        intentShipmentDocId = intent.getStringExtra("shipment_doc_id")
        Log.d(TAG, "onCreate: intentShipmentDocId=${intentShipmentDocId}")
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

        // ── Clean up any orphaned temp photo files ─────────────────────────────
        // If os killed the activity mid-upload (e.g., after user pressed Home),
        // pendingPhotoFile still exists on disk but upload never completed.
        // Delete it to prevent storage leaks.
        if (pendingPhotoFile != null) {
            Log.d(TAG, "onDestroy: cleaning orphaned photo file: ${pendingPhotoFile?.name}")
            deletePhotoFile(pendingPhotoFile)
        }
        pendingPodData = null
        pendingPhotoFile = null
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
        // Use cached intentShipmentDocId — cleared after first load.
        val intentDocId = intentShipmentDocId
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
                            consumeIntentDocId()  // clear after first consume
                            handleAssignmentList(final, intentDocId)
                        }
                    }
                }
        } else {
            loadAssignmentsFallback(sessionDriverId, intentDocId)
        }
    }

    private fun loadAssignmentsFallback(sessionDriverId: String, intentDocId: String?) {
        // Fallback chain mirrors MainActivity's dual-approach pattern:
        //   Approach A: driverId == Queue Doc ID   (older docs)
        //   Approach B: driverId == Firebase UID (newer docs)
        val queueDocId = sessionManager.getCurrentQueueDocId()

        // Approach A: Query by Queue Doc ID
        if (!queueDocId.isNullOrBlank()) {
            Log.d(TAG, "loadAssignmentsFallback: trying Approach A (Queue Doc ID=$queueDocId)")
            assignmentsListener = db.collection("sap_assignments")
                .whereEqualTo("driverId", queueDocId)
                .addSnapshotListener { snaps, err ->
                    if (err != null || snaps == null || snaps.isEmpty) {
                        Log.d(TAG, "loadAssignmentsFallback: Approach A empty/err — trying Approach B (Firebase UID=$sessionDriverId)")
                        listenToAssignmentsViaFirebaseUid(sessionDriverId, intentDocId)
                        return@addSnapshotListener
                    }
                    if (isFinishing) return@addSnapshotListener
                    syncAndHandle(snaps, sessionDriverId, intentDocId)
                }
        } else {
            // No queueDocId available — skip Approach A and go straight to Firebase UID
            Log.d(TAG, "loadAssignmentsFallback: no queueDocId — going straight to Approach B (Firebase UID=$sessionDriverId)")
            listenToAssignmentsViaFirebaseUid(sessionDriverId, intentDocId)
        }
    }

    /**
     * Approach B (Firebase UID): catches all new-style docs where driverId == Firebase UID.
     * Also a valid fallback when the snapshot listener in loadAssignments fails/returns empty.
     */
    private fun listenToAssignmentsViaFirebaseUid(sessionDriverId: String, intentDocId: String?) {
        assignmentsListener?.remove()
        assignmentsListener = db.collection("sap_assignments")
            .whereEqualTo("driverId", sessionDriverId)
            .addSnapshotListener { snaps, err ->
                if (err != null) {
                    Log.e(TAG, "loadAssignmentsFallback: Approach B error: ${err.message}")
                    // Both approaches failed — show empty state using cached list
                    return@addSnapshotListener
                }
                if (isFinishing) return@addSnapshotListener
                val list = snaps?.documents?.mapNotNull { snap ->
                    @Suppress("UNCHECKED_CAST")
                    val data = snap.data as? Map<String, Any?> ?: return@mapNotNull null
                    buildAssignmentFromMap(data + ("docId" to snap.id))
                } ?: emptyList()
                fetchDriverData(sessionDriverId, list) { enriched ->
                    enrichWithQueueData(enriched) { final ->
                        consumeIntentDocId()
                        handleAssignmentList(final, intentDocId)
                    }
                }
            }
    }

    /**
     * Shared snapshot handler used by Approach A of loadAssignmentsFallback.
     * On success: enriches + dispatches to handleAssignmentList.
     * Clears intentDocId after first consume so subsequent updates show full list.
     */
    private fun syncAndHandle(
        snaps: com.google.firebase.firestore.QuerySnapshot,
        sessionDriverId: String,
        intentDocId: String?
    ) {
        Log.d(TAG, "syncAndHandle (Approach A): ${snaps.size()} sap_assignments found")
        val list = snaps.documents.mapNotNull { snap ->
            @Suppress("UNCHECKED_CAST")
            val data = snap.data as? Map<String, Any?> ?: return@mapNotNull null
            buildAssignmentFromMap(data + ("docId" to snap.id))
        }
        fetchDriverData(sessionDriverId, list) { enriched ->
            enrichWithQueueData(enriched) { final ->
                consumeIntentDocId()
                handleAssignmentList(final, intentDocId)
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
            val containerH = binding.container.height
            if (containerW == 0 || containerH == 0) return@post

            val random = java.util.Random()
            val count = random.nextInt(4) + 3  // 3–6 circles

            for (i in 0 until count) {
                val sizeDp = 28 + random.nextInt(61)
                val sizePx = (sizeDp * resources.displayMetrics.density).toInt()
                val startX = random.nextInt(containerW).toFloat()
                val startY = random.nextInt(containerH).toFloat()
                const val minSpeed = 0.12f
                const val maxSpeed = 0.3f
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

    // Stub for compatibility — the original MyTripActivity had these UI helpers
    // but the new design doesn't need them in this screen
    private val isActivityFinished: Boolean
        get() = isFinishing || isDestroyed

    /**
     * Helper function to encode a POD image with timestamp watermark.
     *
     * @param photoFile The photo file to process
     * @param boundOptions BitmapFactory.Options with inJustDecodeBounds set to true
     * @param timestampMillis Timestamp to overlay on the image
     * @param onSuccess Callback with the resulting data URI
     * @param onFailure Callback with error message
     */
    private fun encodePodImageWithTimestamp(
        photoFile: File,
        boundOptions: BitmapFactory.Options,
        timestampMillis: Long,
        onSuccess: (dataUri: String) -> Unit,
        onFailure: (errorMsg: String) -> Unit
    ) {
        // Calculate sample size for 1280px max dimension (same as used in uploadPodImage for File)
        val MAX_DIMENSION_PRIMARY = 1280
        val sampleSize = calculateInSampleSizeForFile(boundOptions, MAX_DIMENSION_PRIMARY, MAX_DIMENSION_PRIMARY)
        val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val bitmap = BitmapFactory.decodeFile(photoFile.absolutePath, decodeOptions)

        if (bitmap == null) {
            onFailure("Photo too large to load. Please try a smaller photo.")
            return
        }

        // Use the new timestamped overload — draws watermark before encoding
        ImageStorageHelper.uploadPodImage(
            bitmap = bitmap,
            timestampMillis = timestampMillis,
            context = this,
            onSuccess = { dataUri ->
                bitmap.recycle()
                onSuccess(dataUri)
            },
            onFailure = { errorMsg ->
                bitmap.recycle()
                onFailure(errorMsg)
            }
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
}