package com.sysu.go.activities

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.widget.FrameLayout
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
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Transaction
import com.sysu.go.R
import com.sysu.go.adapter.ObdTripAdapter
import com.sysu.go.databinding.ActivityMyTripBinding
import com.sysu.go.utils.ImageStorageHelper
import com.sysu.go.utils.SessionManager
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Active Delivery Execution screen.
 *
 * Shows exactly ONE shipment that is currently "in_transit".
 * Renders all OBD line items. Tapping any item opens [ObdStatusBottomSheet]
 * for status selection, which triggers a camera intent to capture the POD photo.
 *
 * State lock: The shipment shown here is the one with tripStatus == "in_transit"
 * for this driver — enforced by Firestore SnapshotListener.
 *
 * Camera lifecycle: Pending OBD state is preserved via [onSaveInstanceState]
 * before launching the camera intent.
 */
class MyTripActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMyTripBinding
    private val db = FirebaseFirestore.getInstance()
    private val sessionManager by lazy { SessionManager(this) }

    // Current shipment data
    private var currentSapDocId: String = ""
    private var currentLineItems: MutableList<Map<String, Any?>> = mutableListOf()

    // Adapter
    private val obdAdapter = ObdTripAdapter { item, position ->
        openStatusSheet(item, position)
    }

    // Firestore listener — kept so we can remove it onDestroy
    private var shipmentListener: com.google.firebase.firestore.ListenerRegistration? = null

    // Camera: the photo is written here before upload
    private var pendingPhotoFile: File? = null
    private var pendingPhotoUri: Uri? = null
    // Lifecycle-safe: state preserved across config change + OS kill
    private var pendingStatus: String? = null
    private var pendingObdNumber: String? = null
    private var pendingPosition: Int = -1

    // ActivityResult launcher for camera
    private lateinit var cameraLauncher: ActivityResultLauncher<Intent>

    companion object {
        private const val TAG = "MyTripActivity"
        private const val REQ_CODE_CAMERA = 1001

        private const val KEY_PENDING_STATUS = "pendingStatus"
        private const val KEY_PENDING_OBD_NUMBER = "pendingObdNumber"
        private const val KEY_PENDING_POSITION = "pendingPosition"
        private const val KEY_PENDING_PHOTO_PATH = "pendingPhotoPath"
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

        setupCameraLauncher()
        setupRecyclerView()
        setupUI()
        startCircleAnimation()
        restoreInstanceState(savedInstanceState)

        // If we were mid-camera when a config change happened, restore and re-launch
        if (pendingPhotoFile != null && pendingStatus != null) {
            // Camera was open — don't re-launch immediately (user might still be in camera)
            // Just log — the onActivityResult will handle it if photo was taken
            Log.d(TAG, "Restored pending camera state — docId=$currentSapDocId, status=$pendingStatus, obd=$pendingObdNumber")
        }
    }

    override fun onResume() {
        super.onResume()
        loadActiveShipment()
    }

    override fun onDestroy() {
        super.onDestroy()
        shipmentListener?.remove()
        // Clean up temp photo file if not consumed
        pendingPhotoFile?.delete()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        pendingStatus?.let { outState.putString(KEY_PENDING_STATUS, it) }
        pendingObdNumber?.let { outState.putString(KEY_PENDING_OBD_NUMBER, it) }
        if (pendingPosition >= 0) outState.putInt(KEY_PENDING_POSITION, pendingPosition)
        pendingPhotoFile?.absolutePath?.let { outState.putString(KEY_PENDING_PHOTO_PATH, it) }
    }

    // ─── Setup ─────────────────────────────────────────────────────────────────

    private fun restoreInstanceState(savedInstanceState: Bundle?) {
        savedInstanceState ?: return
        pendingStatus = savedInstanceState.getString(KEY_PENDING_STATUS)
        pendingObdNumber = savedInstanceState.getString(KEY_PENDING_OBD_NUMBER)
        pendingPosition = savedInstanceState.getInt(KEY_PENDING_POSITION, -1)
        savedInstanceState.getString(KEY_PENDING_PHOTO_PATH)?.let { path ->
            val file = File(path)
            if (file.exists()) pendingPhotoFile = file
        }
    }

    private fun setupCameraLauncher() {
        cameraLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == RESULT_OK) {
                onCameraResult()
            } else {
                // User cancelled or camera failed — clear pending state
                Log.d(TAG, "Camera result not OK — clearing pending state")
                clearPendingState()
            }
        }
    }

    private fun setupRecyclerView() {
        binding.rvObdItems.apply {
            layoutManager = LinearLayoutManager(this@MyTripActivity)
            adapter = obdAdapter
            itemAnimator = androidx.recyclerview.widget.DefaultItemAnimator().apply {
                addDuration = 150
                removeDuration = 150
            }
        }
    }

    private fun setupUI() {
        binding.btnBack.setOnClickListener { finish() }

        binding.cardFinishDelivery.setOnClickListener {
            promptFinishDelivery()
        }
    }

    // ─── Circle animation (matches AssignmentActivity pattern) ──────────────────

    private fun startCircleAnimation() {
        binding.circleContainer.post {
            val containerW = binding.circleContainer.width
            val containerH = binding.circleContainer.height
            if (containerW == 0 || containerH == 0) return@post

            val random = java.util.Random()
            val count = random.nextInt(3) + 3

            for (i in 0 until count) {
                val sizeDp = 28 + random.nextInt(41)
                val sizePx = (sizeDp * resources.displayMetrics.density).toInt()
                val startX = random.nextInt(containerW).toFloat()
                val startY = random.nextInt(containerH).toFloat()
                val minSpeed = 0.10f + random.nextFloat() * 0.12f
                val maxSpeed = minSpeed + 0.12f + random.nextFloat() * 0.18f
                val alpha = 0.20f + random.nextFloat() * 0.25f

                val circle = View(this).apply {
                    layoutParams = FrameLayout.LayoutParams(sizePx, sizePx)
                    background = ContextCompat.getDrawable(this@MyTripActivity, R.drawable.bg_circle_light_red)
                    this.alpha = alpha
                }
                binding.circleContainer.addView(circle)
                startBounceAnimation(circle, sizePx, containerW.toFloat(), containerH.toFloat(), minSpeed, maxSpeed, startX, startY, (i * 120L) + random.nextInt(400))
            }
        }
    }

    private fun startBounceAnimation(
        view: View,
        viewSize: Int,
        containerWidth: Float,
        containerHeight: Float,
        minSpeed: Float,
        maxSpeed: Float,
        initialX: Float,
        initialY: Float,
        startDelayMs: Long
    ) {
        val random = java.util.Random()
        val maxX = (containerWidth - viewSize).coerceAtLeast(0f)
        val maxY = (containerHeight - viewSize).coerceAtLeast(0f)

        view.translationX = initialX
        view.translationY = initialY

        var velX = (random.nextFloat() * (maxSpeed - minSpeed) + minSpeed) *
            if (random.nextBoolean()) 1f else -1f
        var velY = (random.nextFloat() * (maxSpeed - minSpeed) + minSpeed) *
            if (random.nextBoolean()) 1f else -1f

        val animators = mutableListOf<android.animation.Animator>()

        android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = Long.MAX_VALUE
            startDelay = startDelayMs
            addUpdateListener {
                if (random.nextFloat() < 0.02f) {
                    velX = (velX + (random.nextFloat() - 0.5f) * 0.4f).coerceIn(-maxSpeed, maxSpeed)
                }
                if (random.nextFloat() < 0.02f) {
                    velY = (velY + (random.nextFloat() - 0.5f) * 0.3f).coerceIn(-maxSpeed, maxSpeed)
                }
                var newX = (view.translationX + velX).coerceIn(0f, maxX)
                var newY = (view.translationY + velY).coerceIn(0f, maxY)
                view.translationX = newX
                view.translationY = newY
            }
            animators.add(this)
            start()
        }

        android.animation.ObjectAnimator.ofFloat(view, "scaleX", 1f, 1.08f).apply {
            duration = 4000L + random.nextInt(1500)
            repeatMode = android.animation.ObjectAnimator.REVERSE
            repeatCount = android.animation.ValueAnimator.INFINITE
            startDelay = startDelayMs
            animators.add(this)
            start()
        }

        android.animation.ObjectAnimator.ofFloat(view, "scaleY", 1f, 1.06f).apply {
            duration = 4500L + random.nextInt(1500)
            repeatMode = android.animation.ObjectAnimator.REVERSE
            repeatCount = android.animation.ValueAnimator.INFINITE
            startDelay = startDelayMs + 500L
            animators.add(this)
            start()
        }
    }

    // ─── Firestore Loading ─────────────────────────────────────────────────────

    /**
     * Loads the single in_transit shipment for this driver.
     *
     * Query strategy mirrors AssignmentActivity's pattern:
     * 1. Try queue doc ID (sap_assignments.driverId = queue doc ID)
     * 2. Fall back to Firebase UID
     *
     * addSnapshotListener keeps the lock authoritative even if another process
     * changes the tripStatus while this activity is on screen.
     */
    private fun loadActiveShipment() {
        shipmentListener?.remove()

        val driverId = sessionManager.getDriverId() ?: run {
            showNoActiveTrip()
            return
        }
        val queueDocId = sessionManager.getCurrentQueueDocId() ?: ""

        // Override loading state
        showLoading()

        // Approach 1: queue doc ID primary
        val docRef = if (queueDocId.isNotBlank()) {
            db.collection("sap_assignments")
                .whereEqualTo("driverId", queueDocId)
                .whereEqualTo("tripStatus", "in_transit")
        } else null

        if (docRef != null) {
            shipmentListener = docRef.addSnapshotListener { snaps, err ->
                if (snaps != null && snaps.size() >= 1) {
                    loadShipmentFromSnapshot(snaps.documents[0])
                } else {
                    loadActiveShipmentFallback(driverId)
                }
            }
        } else {
            loadActiveShipmentFallback(driverId)
        }
    }

    private fun loadActiveShipmentFallback(driverId: String) {
        shipmentListener = db.collection("sap_assignments")
            .whereEqualTo("driverId", driverId)
            .whereEqualTo("tripStatus", "in_transit")
            .addSnapshotListener { snaps, err ->
                if (err != null) {
                    Log.e(TAG, "loadActiveShipment (fallback) error: ${err.message}")
                    showNoActiveTrip()
                    return@addSnapshotListener
                }
                if (isActivityFinished) return@addSnapshotListener
                if (snaps != null && snaps.size() >= 1) {
                    loadShipmentFromSnapshot(snaps.documents[0])
                } else {
                    showNoActiveTrip()
                }
            }
    }

    private fun loadShipmentFromSnapshot(doc: com.google.firebase.firestore.DocumentSnapshot) {
        currentSapDocId = doc.id

        // Banner
        binding.tvBannerShipmentNumber.text = doc.getString("shipmentNumber") ?: doc.id
        binding.tvBannerPlateNo.text = buildString {
            append(doc.getString("truckPlate") ?: "—")
            val truckCo = doc.getString("truckCompany") ?: doc.getString("truckType") ?: ""
            if (truckCo.isNotBlank()) {
                append(" • $truckCo")
            }
        }

        // Summary card
        binding.tvPlateNo.text = doc.getString("truckPlate") ?: "—"
        binding.tvTruckType.text = doc.getString("truckCompany") ?: doc.getString("truckType") ?: "—"
        binding.tvHelper.text = doc.getString("helperName") ?: doc.getString("helper") ?: "—"

        // Line items
        @Suppress("UNCHECKED_CAST")
        val rawItems = doc.get("lineItems") as? List<Map<String, Any?>> ?: emptyList()
        currentLineItems = rawItems.toMutableList()
        binding.tvObdCount.text = rawItems.size.toString()

        showContent()
        obdAdapter.submitList(rawItems.toList())
        updateFinishDeliveryVisibility()
    }

    // ─── OBD Click → Status Sheet → Camera ───────────────────────────────────

    private fun openStatusSheet(item: Map<String, Any?>, position: Int) {
        // Don't allow reopening if already delivered
        val status = item["deliveryStatus"] as? String
        if (status == getString(R.string.pod_status_delivered) ||
            status == getString(R.string.pod_status_rejected) ||
            status == getString(R.string.pod_status_delivered_rejection)) {
            Toast.makeText(this, "This OBD has already been processed.", Toast.LENGTH_SHORT).show()
            return
        }

        val sheet = ObdStatusBottomSheet.newInstance(item, currentSapDocId)
        sheet.onConfirmWithStatus = { selectedStatus, selectedItem, docId ->
            // Save pending state BEFORE launching camera — survive OS kill
            pendingStatus = selectedStatus
            pendingObdNumber = selectedItem["obdNumber"] as? String
            pendingPosition = position

            Log.d(TAG, "Status confirmed: '$selectedStatus' for OBD: ${pendingObdNumber}")
            launchCameraIntent()
        }
        sheet.show(supportFragmentManager, ObdStatusBottomSheet.TAG)
    }

    // ─── Camera Intent ─────────────────────────────────────────────────────────

    /**
     * Launches the system camera app.
     * Uses MediaStore.EXTRA_OUTPUT for a deterministic save path — survives
     * most OS kills because the file is written directly to disk by the camera app.
     */
    private fun launchCameraIntent() {
        val photoFile = createImageFile()
        pendingPhotoFile = photoFile

        val photoUri: Uri = FileProvider.getUriForFile(
            this,
            "${packageName}.fileprovider", // must match AndroidManifest provider authority
            photoFile
        )
        pendingPhotoUri = photoUri

        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, photoUri)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }

        if (intent.resolveActivity(packageManager) != null) {
            cameraLauncher.launch(intent)
        } else {
            Toast.makeText(this, "No camera app found.", Toast.LENGTH_SHORT).show()
            clearPendingState()
        }
    }

    /** Creates a temp file in app's internal storage for camera to write to. */
    private fun createImageFile(): File {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val storageDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES)
        return File.createTempFile("POD_${timeStamp}_", ".jpg", storageDir)
    }

    // ─── Camera Result Handler ─────────────────────────────────────────────────

    private fun onCameraResult() {
        val photoFile = pendingPhotoFile
        val status = pendingStatus
        val obdNumber = pendingObdNumber
        val position = pendingPosition

        if (photoFile == null || !photoFile.exists() || status == null || obdNumber == null) {
            Log.w(TAG, "onCameraResult called with missing data — file=${photoFile?.exists()}, status=$status, obd=$obdNumber")
            Toast.makeText(this, R.string.pod_upload_failed, Toast.LENGTH_SHORT).show()
            clearPendingState()
            return
        }

        Log.d(TAG, "Camera captured: ${photoFile.absolutePath}, status=$status, obd=$obdNumber")

        uploadAndUpdateFirestore(photoFile, status, obdNumber, position)
    }

    // ─── Upload + Firestore Update ─────────────────────────────────────────────

    /**
     * 1. Compress + upload photo to Firebase Cloud Storage
     * 2. Firestore runTransaction: read lineItems → update target OBD map fields → write back
     * 3. On success: update local adapter list + check if all items complete → show finish card
     */
    private fun uploadAndUpdateFirestore(
        photoFile: File,
        status: String,
        obdNumber: String,
        position: Int
    ) {
        val storagePath = ImageStorageHelper.buildStoragePath(
            shipmentDocId = currentSapDocId,
            obdNumber = obdNumber,
            timestamp = System.currentTimeMillis()
        )

        Log.d(TAG, "Uploading POD to: $storagePath")

        ImageStorageHelper.uploadPodImage(
            photoFile = photoFile,
            context = this,
            storagePath = storagePath,
            onSuccess = { downloadUrl ->
                Log.d(TAG, "Upload succeeded — URL: $downloadUrl")
                savePodToFirestore(downloadUrl, status, obdNumber, position)
            },
            onFailure = { errorMsg ->
                Log.e(TAG, "Upload failed: $errorMsg")
                runOnUiThread {
                    Toast.makeText(this, R.string.pod_upload_failed, Toast.LENGTH_LONG).show()
                    clearPendingState()
                }
            }
        )
    }

    private fun savePodToFirestore(
        downloadUrl: String,
        status: String,
        obdNumber: String,
        position: Int
    ) {
        val docRef = db.collection("sap_assignments").document(currentSapDocId)

        db.runTransaction { transaction ->
            val snapshot = transaction.get(docRef)

            @Suppress("UNCHECKED_CAST")
            val lineItems = (snapshot.get("lineItems") as? List<Map<String, Any?>>)
                ?.toMutableList()
                ?: throw FirebaseFirestoreException(
                    "lineItems not found",
                    com.google.firebase.firestore.FirebaseFirestoreException.Code.ABORTED
                )

            // Find + update the target OBD item
            val index = lineItems.indexOfFirst { item ->
                (item["obdNumber"] as? String) == obdNumber
            }

            if (index < 0) {
                Log.w(TAG, "OBD not found in lineItems: $obdNumber")
                return@runTransaction
            }

            val updatedItem = lineItems[index].toMutableMap()
            updatedItem["deliveryStatus"] = status
            updatedItem["podImageUrl"] = downloadUrl
            updatedItem["podTimestamp"] = Timestamp.now()

            lineItems[index] = updatedItem
            transaction.update(docRef, "lineItems", lineItems)

            Log.d(TAG, "lineItems[$index] updated — status=$status, URL length=${downloadUrl.length}")
        }.addOnSuccessListener {
            Log.d(TAG, "Firestore transaction success for OBD: $obdNumber")
            runOnUiThread {
                onPodUpdateSuccess(obdNumber, position)
            }
        }.addOnFailureListener { e ->
            Log.e(TAG, "Firestore transaction failed: ${e.message}")
            runOnUiThread {
                Toast.makeText(this, R.string.pod_update_failed, Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * Called after a successful Firestore update.
     * Updates the local adapter list and shows a per-item confirmation toast.
     */
    private fun onPodUpdateSuccess(obdNumber: String, position: Int) {
        // Update local mutable list to keep it in sync
        currentLineItems.indexOfFirst { (it["obdNumber"] as? String) == obdNumber }.takeIf { it >= 0 }?.let { idx ->
            val updated = currentLineItems[idx].toMutableMap()
            updated["deliveryStatus"] = pendingStatus
            updated["podImageUrl"] = "uploaded" // placeholder — URL already in Firestore
            updated["podTimestamp"] = Timestamp.now()
            currentLineItems[idx] = updated
        }

        // Notify adapter of the changed row
        val newList = currentLineItems.toList()
        obdAdapter.submitList(newList)

        Toast.makeText(this, getString(R.string.pod_status_updated, obdNumber), Toast.LENGTH_SHORT).show()

        updateFinishDeliveryVisibility()
        clearPendingState()
    }

    // ─── Completion Check ───────────────────────────────────────────────────────

    /** Shows/hides the green "FINISH DELIVERY" card based on whether all OBDs are processed. */
    private fun updateFinishDeliveryVisibility() {
        val allProcessed = currentLineItems.all { item ->
            val status = item["deliveryStatus"] as? String
            status != null && status.isNotBlank()
        }

        binding.cardFinishDelivery.visibility = if (allProcessed) View.VISIBLE else View.GONE

        // Update subtitle with count
        val processed = currentLineItems.count { (it["deliveryStatus"] as? String)?.isNotBlank() == true }
        val total = currentLineItems.size
        if (allProcessed) {
            binding.tvFinishSubtitle.text = "All $total items processed — you're done!"
        } else {
            binding.tvFinishSubtitle.text = "$processed of $total items processed"
        }
    }

    // ─── Finish Delivery ────────────────────────────────────────────────────────

    private fun promptFinishDelivery() {
        AlertDialog.Builder(this)
            .setTitle(R.string.my_trip_finish_confirm_title)
            .setMessage(R.string.my_trip_finish_confirm_message)
            .setPositiveButton(R.string.my_trip_finish_delivery) { _, _ -> finishDelivery() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun finishDelivery() {
        if (currentSapDocId.isBlank()) return

        lifecycleScope.launch {
            try {
                val updates = mapOf(
                    "tripStatus" to "completed",
                    "completedAt" to Timestamp.now()
                )
                db.collection("sap_assignments").document(currentSapDocId)
                    .update(updates)
                    .await()

                // Clear active trip from session
                sessionManager.saveActiveSapDocId(null)
                sessionManager.setTripActive(false)

                Toast.makeText(
                    this@MyTripActivity,
                    R.string.pod_delivery_complete,
                    Toast.LENGTH_LONG
                ).show()
                finish()
            } catch (e: Exception) {
                Log.e(TAG, "finishDelivery failed: ${e.message}")
                Toast.makeText(
                    this@MyTripActivity,
                    "Failed to complete delivery. Check connection.",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    // ─── UI State Helpers ───────────────────────────────────────────────────────

    private fun showLoading() {
        binding.loadingState.visibility = View.VISIBLE
        binding.emptyState.visibility = View.GONE
        binding.rvObdItems.visibility = View.GONE
    }

    private fun showContent() {
        binding.loadingState.visibility = View.GONE
        binding.emptyState.visibility = View.GONE
        binding.rvObdItems.visibility = View.VISIBLE
        binding.badgeInTransit.visibility = View.VISIBLE
    }

    private fun showNoActiveTrip() {
        binding.loadingState.visibility = View.GONE
        binding.emptyState.visibility = View.VISIBLE
        binding.rvObdItems.visibility = View.GONE
        binding.badgeInTransit.visibility = View.GONE
    }

    private fun clearPendingState() {
        pendingStatus = null
        pendingObdNumber = null
        pendingPosition = -1
        pendingPhotoFile?.delete()
        pendingPhotoFile = null
        pendingPhotoUri = null
    }

    private val isActivityFinished: Boolean
        get() = isDestroyed || isFinishing
}