package com.sysu.go.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import java.io.ByteArrayOutputStream

/**
 * Handles photo compression and base64-encodes POD images for storage in Firestore.
 *
 * No Firebase Storage is used — the image bytes are compressed and encoded as a
 * data URI string: "data:image/jpeg;base64,{base64_string}"
 *
 * Quality: JPEG 50% with max resolution of 720p on the longer edge.
 * Estimated output: ~30–80KB per photo (well within Firestore 1MB document limit).
 */
object ImageStorageHelper {

    private const val TAG = "ImageStorageHelper"
    private const val JPEG_QUALITY = 50          // 50% = small base64, still readable
    private const val MAX_DIMENSION = 1280        // 720p on long edge — good enough for POD

    /**
     * Compresses a POD photo and returns it as a base64-encoded data URI.
     * Suitable for storing directly in Firestore as a String field.
     *
     * @param photoFile   The raw photo file from the camera intent.
     * @param context     Android context.
     * @param onSuccess   Called on the main thread with the base64 data URI string:
     *                    "data:image/jpeg;base64,{base64}"
     * @param onFailure   Called on the main thread with the error message string.
     */
    fun uploadPodImage(
        photoFile: java.io.File,
        context: Context,
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

        // Compress to JPEG 50% and base64-encode
        val byteArray = compressToJpeg(resized, JPEG_QUALITY)
        resized.recycle()

        if (byteArray == null) {
            onFailure("Failed to compress photo.")
            return
        }

        val base64String = Base64.encodeToString(byteArray, Base64.NO_WRAP)
        val dataUri = "data:image/jpeg;base64,$base64String"

        Log.d(TAG, "POD image base64 encoded: ${dataUri.length} chars, ~${byteArray.size / 1024}KB")
        onSuccess(dataUri)
    }

    /**
     * Overload: accepts a Bitmap directly (e.g., from SignaturePadView-style canvas).
     */
    fun uploadPodImage(
        bitmap: Bitmap,
        context: Context,
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

        val base64String = Base64.encodeToString(byteArray, Base64.NO_WRAP)
        val dataUri = "data:image/jpeg;base64,$base64String"

        Log.d(TAG, "POD image base64 encoded: ${dataUri.length} chars, ~${byteArray.size / 1024}KB")
        onSuccess(dataUri)
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

    /**
     * Decodes a base64 data URI (e.g. "data:image/jpeg;base64,/9j/4AAQ...")
     * into a Bitmap. Returns null on failure.
     *
     * Call on a background thread if the string is very large (list scrolling).
     */
    fun decodeBase64ToBitmap(dataUri: String): Bitmap? {
        return try {
            val base64Index = dataUri.indexOf("base64,")
            if (base64Index == -1) {
                Log.w(TAG, "decodeBase64ToBitmap: no base64 prefix found in data URI")
                return null
            }
            val base64String = dataUri.substring(base64Index + "base64,".length)
            val decodedBytes = Base64.decode(base64String, Base64.NO_WRAP)
            BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size)
        } catch (e: Exception) {
            Log.e(TAG, "decodeBase64ToBitmap failed", e)
            null
        }
    }

    /**
     * Kept for backwards compatibility — no longer used internally.
     * Storage path is not needed when storing base64 directly in Firestore.
     */
    fun buildStoragePath(shipmentDocId: String, obdNumber: String, timestamp: Long): String {
        val safeObd = obdNumber.replace("/", "_").replace(".", "_").replace(" ", "_")
        return "shipment_pods/${shipmentDocId}/${safeObd}_${timestamp}.jpg"
    }
}