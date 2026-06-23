package com.sysu.go.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.Base64
import android.util.Log
import java.io.ByteArrayOutputStream

/**
 * Handles photo compression and base64-encodes POD images for storage in Firestore.
 *
 * No Firebase Storage is used — the image bytes are compressed and encoded as a
 * data URI string: "data:image/jpeg;base64,{base64_string}"
 *
 * Two-pass compression:
 *   Primary:   1280px max, JPEG 50% → ~30–80KB base64
 *   Fallback:   960px max,  JPEG 30% → smaller still
 *
 * Resilient to OOM and encode failures via automatic fallback pass.
 */
object ImageStorageHelper {

    private const val TAG = "ImageStorageHelper"
    private const val JPEG_QUALITY_PRIMARY = 50 // 50% = ~30–80KB base64 — first attempt
    private const val JPEG_QUALITY_FALLBACK = 30  // 30% = smaller still — second attempt on failure
    private const val MAX_DIMENSION_PRIMARY = 1280   // 720p on long edge — first attempt
    private const val MAX_DIMENSION_FALLBACK = 960   // smaller — second attempt on failure
    // Shared convenience constant for single-pass callers (e.g. bitmap overload)
    private const val MAX_DIMENSION = MAX_DIMENSION_PRIMARY
    private const val JPEG_QUALITY = JPEG_QUALITY_PRIMARY

    /**
     * Compresses a POD photo and returns it as a base64-encoded data URI.
     * Suitable for storing directly in Firestore as a String field.
     *
     * Uses a two-pass approach for resilience:
     *   1. Primary: MAX_DIMENSION_PRIMARY (1280px) + JPEG_QUALITY_PRIMARY (50%)
     *   2. Fallback: MAX_DIMENSION_FALLBACK (960px) + JPEG_QUALITY_FALLBACK (30%)
     *
     * If the primary pass fails (OOM, encode failure), the fallback pass is tried
     * automatically before returning onFailure.
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
        // ── Step 1: Decode + resize at primary resolution ─────────────────────
        val primaryBitmap = decodeAndResize(photoFile, MAX_DIMENSION_PRIMARY)
        if (primaryBitmap == null) {
            onFailure("Photo too large to load. Please try a smaller photo.")
            return
        }

        // ── Step 2: Compress + encode ─────────────────────────────────────────
        var encoded: String? = compressToBase64DataUri(primaryBitmap, JPEG_QUALITY_PRIMARY)
        primaryBitmap.recycle()

        if (encoded == null) {
            Log.w(TAG, "Primary encode failed — attempting fallback compression (960px / 30%)")
            // ── Fallback pass: smaller image, lower quality ─────────────────
            val fallbackBitmap = decodeAndResize(photoFile, MAX_DIMENSION_FALLBACK)
            if (fallbackBitmap != null) {
                encoded = compressToBase64DataUri(fallbackBitmap, JPEG_QUALITY_FALLBACK)
                fallbackBitmap.recycle()
            }
        }

        if (encoded != null) {
            Log.d(TAG, "POD image encoded: ${encoded.length} chars (~${encoded.substringAfter("base64,").length / 1024}KB raw)")
            onSuccess(encoded)
        } else {
            onFailure("Failed to process photo. Please try again.")
        }
    }

    /**
     * Decodes a photo file to Bitmap, resized so the longer edge ≤ maxDimension.
     * Returns null on OOM or decode failure.
     * Must be called on a background thread for large files.
     */
    private fun decodeAndResize(photoFile: java.io.File, maxDimension: Int): Bitmap? {
        return try {
            // Calculate inSampleSize to avoid OOM during decode
            val boundOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(photoFile.absolutePath, boundOptions)

            val sampleSize = calculateInSampleSize(boundOptions, maxDimension, maxDimension)
            val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }

            val bitmap = BitmapFactory.decodeFile(photoFile.absolutePath, decodeOptions)
                ?: return null

            // Resize to exact max dimension (maintaining aspect ratio)
            val resized = resizeBitmap(bitmap, maxDimension)
            if (resized !== bitmap) bitmap.recycle()
            resized
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "OOM decoding photo (maxDimension=$maxDimension): ${photoFile.name}", e)
            null
        } catch (e: Exception) {
            Log.e(TAG, "decodeAndResize failed: ${e.message}", e)
            null
        }
    }

    /**
     * Compresses bitmap to JPEG at given quality and returns as base64 data URI.
     * Returns null on compress/encode failure.
     */
    private fun compressToBase64DataUri(bitmap: Bitmap, quality: Int): String? {
        return try {
            val byteArray = ByteArrayOutputStream().use { baos ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality, baos)
                baos.toByteArray()
            }
            val base64String = Base64.encodeToString(byteArray, Base64.NO_WRAP)
            "data:image/jpeg;base64,$base64String"
        } catch (e: Exception) {
            Log.e(TAG, "JPEG compress/encode failed (quality=$quality)", e)
            null
        }
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

    /**
     * Compresses a POD photo with a visible timestamp overlay and returns it as a
     * base64-encoded data URI. Suitable for storing directly in Firestore.
     *
     * Flow:
     *   1. Draw timestamp watermark (bottom-left, white-on-black strip)
     *   2. Resize to ≤1280px max dimension
     *   3. Compress to JPEG 50%
     *   4. Base64-encode to data URI
     *
     * @param bitmap         The raw photo bitmap to process.
     * @param timestampMillis Epoch millis for the timestamp shown in the watermark.
     * @param context        Android context (for display metrics).
     * @param onSuccess      Called on main thread with the base64 data URI string.
     * @param onFailure      Called on main thread with the error message string.
     */
    fun uploadPodImage(
        bitmap: Bitmap,
        timestampMillis: Long,
        context: Context,
        onSuccess: (String) -> Unit,
        onFailure: (String) -> Unit
    ) {
        // Step 1: Draw timestamp overlay (bottom-left corner, white on 80% black strip)
        val stampedBitmap = try {
            drawTimestampOverlay(bitmap, timestampMillis)
        } catch (e: Exception) {
            Log.w(TAG, "drawTimestampOverlay failed — encoding without watermark: ${e.message}")
            // Fallback: use original bitmap if overlay drawing fails
            bitmap.copy(Bitmap.Config.ARGB_8888, false)
        }

        // Step 2: Resize (same logic as existing single-bitmap overload)
        val resized = resizeBitmap(stampedBitmap, MAX_DIMENSION)
        if (resized !== stampedBitmap) stampedBitmap.recycle()

        // Step 3: Compress to JPEG
        val byteArray = compressToJpeg(resized, JPEG_QUALITY)
        resized.recycle()

        if (byteArray == null) {
            onFailure("Failed to compress photo.")
            return
        }

        // Step 4: Base64-encode
        val base64String = Base64.encodeToString(byteArray, Base64.NO_WRAP)
        val dataUri = "data:image/jpeg;base64,$base64String"

        Log.d(TAG, "POD timestamped image encoded: ${dataUri.length} chars, ~${byteArray.size / 1024}KB")
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

    /**
     * Draws a timestamp watermark in the bottom-left corner of the given bitmap.
     * Returns a NEW bitmap (caller must recycle it) with the watermark drawn on top.
     * The original bitmap is NOT modified.
     *
     * Watermark style (Shopee/Lalamove-standard — large and instantly readable):
     *   - Background strip: #E6000000 (90% black), full-width, tall padding
     *   - Text: 32sp bold, white (#FFFFFF), system sans-serif
     *   - Format: "Jun 23, 2026 • 2:30:45 PM"
     *   - Position: 24px margin from bottom edge, left-aligned, 24px left padding
     */
    private fun drawTimestampOverlay(bitmap: Bitmap, timestampMillis: Long): Bitmap {
        val result = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(result)

        // Format timestamp
        val dateFormat = java.text.SimpleDateFormat("MMM dd, yyyy • h:mm:ss a", java.util.Locale.US)
        val dateStr = dateFormat.format(java.util.Date(timestampMillis))

        // Text paint — 32sp BOLD, always same physical size regardless of screen density
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            textSize = 32f * android.content.res.Resources.getSystem().displayMetrics.scaledDensity
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }

        // Measure text to size the background strip
        val textWidth = textPaint.measureText(dateStr)
        val textHeight = textPaint.descent() - textPaint.ascent()
        val textBottomY = result.height - 24f

        // Background strip rect: full-width strip to accommodate large text
        val stripLeft = 0f
        val stripRight = textWidth + 48f  // 24dp horizontal padding each side
        val stripTop = textBottomY - textHeight - 24f  // 12dp vertical padding above text
        val stripBottom = textBottomY + 16f

        val stripRect = RectF(stripLeft, stripTop, stripRight, stripBottom)

        // Draw background strip — 90% black, a bit taller for 32sp text
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xE6000000.toInt()  // #E6000000 — 90% black, more opaque than before
        }
        canvas.drawRoundRect(stripRect, 8f, 8f, bgPaint)

        // Draw text on top of background
        val textX = stripLeft + 24f  // 24dp left padding inside strip
        val textY = stripBottom - 16f - textPaint.descent()  // align baseline
        canvas.drawText(dateStr, textX, textY, textPaint)

        return result
    }
}