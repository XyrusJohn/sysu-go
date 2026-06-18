package com.sysu.go.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * Handles photo compression and upload to Firebase Cloud Storage for POD images.
 *
 * Storage path convention:
 *   shipment_pods/{shipmentDocId}/{obdNumber}_{timestamp}.jpg
 *
 * Quality: JPEG 80% with max resolution of 1080p on the longer edge.
 * Estimated output: ~150–300KB per photo.
 *
 * Firestore stores only the download URL, not the image data.
 */
object ImageStorageHelper {

    private const val TAG = "ImageStorageHelper"
    private const val JPEG_QUALITY = 80
    private const val MAX_DIMENSION = 1080 // 1080p on long edge

    /**
     * Compresses and uploads a POD photo to Firebase Cloud Storage.
     *
     * @param photoFile     The raw photo file from the camera intent.
     * @param context      Android context (used for lifecycleScope in Activity, or GlobalScope in Service).
     * @param storagePath  Full path inside Firebase Storage, e.g.
     *                     "shipment_pods/0010128715/6051555386_1718723400000.jpg"
     * @param onSuccess    Called on the main thread with the Firebase download URL.
     * @param onFailure    Called on the main thread with the error message string.
     */
    fun uploadPodImage(
        photoFile: File,
        context: Context,
        storagePath: String,
        onSuccess: (String) -> Unit,
        onFailure: (String) -> Unit
    ) {
        // Decode file to bitmap — use options to avoid OOM on large photos
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeFile(photoFile.absolutePath, options)

        // Calculate inSampleSize to fit within MAX_DIMENSION
        val sampleSize = calculateInSampleSize(options, MAX_DIMENSION, MAX_DIMENSION)
        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
        }

        val bitmap = try {
            BitmapFactory.decodeFile(photoFile.absolutePath, decodeOptions)
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "OOM decoding photo: ${photoFile.name}", e)
            onFailure("Photo too large. Please try again.")
            return
        }

        if (bitmap == null) {
            onFailure("Failed to decode photo.")
            return
        }

        // Resize to max MAX_DIMENSION on the longer edge (maintain aspect ratio)
        val resized = resizeBitmap(bitmap, MAX_DIMENSION)
        bitmap.recycle()

        // Compress to JPEG 80%
        val byteArray = compressToJpeg(resized, JPEG_QUALITY)
        resized.recycle()

        if (byteArray == null) {
            onFailure("Failed to compress photo.")
            return
        }

        uploadBytes(byteArray, context, storagePath, onSuccess, onFailure)
    }

    /**
     * Overload: accepts a Bitmap directly (e.g., from SignaturePadView-style canvas).
     */
    fun uploadPodImage(
        bitmap: Bitmap,
        context: Context,
        storagePath: String,
        onSuccess: (String) -> Unit,
        onFailure: (String) -> Unit
    ) {
        val resized = resizeBitmap(bitmap, MAX_DIMENSION)
        val byteArray = compressToJpeg(resized, JPEG_QUALITY)
        resized.recycle()

        if (byteArray == null) {
            onFailure("Failed to compress photo.")
            return
        }

        uploadBytes(byteArray, context, storagePath, onSuccess, onFailure)
    }

    // ─── Private helpers ───────────────────────────────────────────────────────

    private fun calculateInSampleSize(
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

    /**
     * Resize bitmap so the longer edge == maxDimension, preserving aspect ratio.
     */
    private fun resizeBitmap(bitmap: Bitmap, maxDimension: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height

        if (width <= maxDimension && height <= maxDimension) {
            return bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, false)
        }

        val ratio = width.toFloat() / height.toFloat()
        val (newWidth, newHeight) = if (width > height) {
            maxDimension to (maxDimension / ratio).toInt()
        } else {
            (maxDimension * ratio).toInt() to maxDimension
        }

        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }

    private fun compressToJpeg(bitmap: Bitmap, quality: Int): ByteArray? {
        return try {
            ByteArrayOutputStream().use { baos ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality, baos)
                baos.toByteArray()
            }
        } catch (e: Exception) {
            Log.e(TAG, "JPEG compression failed", e)
            null
        }
    }

    private fun uploadBytes(
        data: ByteArray,
        context: Context,
        storagePath: String,
        onSuccess: (String) -> Unit,
        onFailure: (String) -> Unit
    ) {
        val storageRef = FirebaseStorage.getInstance().reference.child(storagePath)

        storageRef.putBytes(data)
            .addOnSuccessListener { taskSnapshot ->
                Log.d(TAG, "Upload succeeded: $storagePath, size=${data.size} bytes")

                storageRef.downloadUrl
                    .addOnSuccessListener { uri ->
                        Log.d(TAG, "Download URL: ${uri.toString()}")
                        onSuccess(uri.toString())
                    }
                    .addOnFailureListener { e ->
                        Log.e(TAG, "getDownloadUrl failed: ${e.message}")
                        // Upload succeeded but URL retrieval failed — still a partial success
                        onFailure("Image uploaded but failed to get URL: ${e.message}")
                    }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Upload failed: ${e.message}")
                onFailure("Upload failed: ${e.message}")
            }
    }

    /**
     * Builds the standard POD storage path for a given shipment + OBD item.
     *
     * @param shipmentDocId   sap_assignments document ID (e.g. "0010128715")
     * @param obdNumber      The OBD number string (e.g. "6051555386")
     * @param timestamp      System.currentTimeMillis() at capture time
     */
    fun buildStoragePath(shipmentDocId: String, obdNumber: String, timestamp: Long): String {
        val safeObd = obdNumber.replace("/", "_").replace(".", "_").replace(" ", "_")
        return "shipment_pods/${shipmentDocId}/${safeObd}_${timestamp}.jpg"
    }
}