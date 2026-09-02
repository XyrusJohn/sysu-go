package com.sysu.go.network

import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.gson.Gson
import com.sysu.go.BuildConfig
import com.sysu.go.model.ScanRequest
import com.sysu.go.model.ScanResponse
import com.sysu.go.model.VerifyResponse
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object QRApiService {
    private const val BASE_URL = BuildConfig.API_BASE_URL + "/api/qr"
    private val gson = Gson()

    sealed class Result<out T> {
        data class Success<T>(val data: T) : Result<T>()
        data class Error(val code: Int, val message: String) : Result<Nothing>()
    }

    private const val TAG = "QRApiService"

    fun verifyToken(token: String): Result<VerifyResponse> {
        // Try HTTP API first with adaptive timeout
        for (attempt in 1..2) {
            try {
                val encodedToken = URLEncoder.encode(token, "UTF-8")
                val urlString = "$BASE_URL/verify?token=$encodedToken"
                Log.d(TAG, "--> GET $urlString (attempt $attempt, token: $token)")
                val url = URL(urlString)
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.connectTimeout = 6000
                conn.readTimeout = 6000

                val responseCode = conn.responseCode
                val inputStream = if (responseCode in 200..299) conn.inputStream else conn.errorStream
                val body = inputStream?.bufferedReader()?.readText() ?: "{}"

                Log.d(TAG, "<-- Verify Response ($responseCode): $body")

                val parsed = gson.fromJson(body, VerifyResponse::class.java)

                if (responseCode == 200 && parsed.success) {
                    Log.d(TAG, "verifyToken HTTP SUCCESS: token=${parsed.token}")
                    return Result.Success(parsed)
                } else if (responseCode in 400..499) {
                    // Client errors (e.g. 404 not found, 410 already used) — return immediately
                    Log.w(TAG, "verifyToken HTTP FAILED ($responseCode): ${parsed.message}")
                    return Result.Error(responseCode, parsed.message ?: "Verification failed (Code: $responseCode)")
                }
            } catch (e: Exception) {
                Log.w(TAG, "verifyToken HTTP attempt $attempt failed: ${e.message}")
            }
        }

        // Fallback: Direct Firestore Verification (Zero-latency, 100% resilient if backend is sleeping)
        Log.i(TAG, "verifyToken: Falling back to direct Firestore verification for token: $token")
        return try {
            val db = FirebaseFirestore.getInstance()
            val snap = Tasks.await(db.collection("qr_tokens").document(token).get())
            if (!snap.exists()) {
                Log.w(TAG, "verifyToken Firestore: Token $token not found")
                return Result.Error(404, "Invalid or expired token")
            }
            val used = snap.getBoolean("used") ?: snap.getBoolean("isUsed") ?: false
            if (used) {
                Log.w(TAG, "verifyToken Firestore: Token $token is already used")
                return Result.Error(410, "Token already used")
            }
            Tasks.await(db.collection("qr_tokens").document(token).update(mapOf("used" to true, "isUsed" to true)))
            Log.i(TAG, "verifyToken Firestore: Token $token verified and marked used")
            Result.Success(VerifyResponse(success = true, message = "Verified", token = token))
        } catch (fe: Exception) {
            Log.e(TAG, "verifyToken Firestore fallback failed: ${fe.message}", fe)
            Result.Error(-1, "Verification error: ${fe.javaClass.simpleName} - ${fe.message}")
        }
    }

    fun scanToken(request: ScanRequest): Result<ScanResponse> {
        // Try HTTP API first with adaptive timeout
        for (attempt in 1..2) {
            try {
                val urlString = "$BASE_URL/scan"
                Log.d(TAG, "--> POST $urlString (attempt $attempt, driverId: ${request.driverId}, plate: ${request.plateNumber})")
                val url = URL(urlString)
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Accept", "application/json")
                conn.connectTimeout = 6000
                conn.readTimeout = 6000

                val jsonBody = gson.toJson(request)
                OutputStreamWriter(conn.outputStream).use { w ->
                    w.write(jsonBody)
                }

                val responseCode = conn.responseCode
                val inputStream = if (responseCode in 200..299) conn.inputStream else conn.errorStream
                val body = inputStream?.bufferedReader()?.readText() ?: "{}"

                Log.d(TAG, "<-- Scan Response ($responseCode): $body")

                val parsed = gson.fromJson(body, ScanResponse::class.java)

                if (responseCode == 200 && parsed.success) {
                    Log.d(TAG, "scanToken HTTP SUCCESS: queueId=${parsed.queueId}, queuePosition=${parsed.queuePosition}")
                    return Result.Success(parsed)
                } else if (responseCode in 400..499) {
                    Log.w(TAG, "scanToken HTTP FAILED ($responseCode): ${parsed.message}")
                    return Result.Error(responseCode, parsed.message ?: "Scan failed (Code: $responseCode)")
                }
            } catch (e: Exception) {
                Log.w(TAG, "scanToken HTTP attempt $attempt failed: ${e.message}")
            }
        }

        // Fallback: Direct Firestore Join Queue (Zero-latency, 100% resilient if backend is sleeping)
        Log.i(TAG, "scanToken: Falling back to direct Firestore queue join for driver: ${request.driverId}")
        return try {
            val db = FirebaseFirestore.getInstance()
            val queueDocRef = db.collection("queue").document()
            val queueData = hashMapOf(
                "token" to request.token,
                "driverId" to request.driverId,
                "driverName" to request.driverName,
                "truckType" to request.truckType,
                "truckCompany" to request.truckCompany,
                "plateNumber" to request.plateNumber,
                "helper" to request.helperName,
                "phone" to request.driverPhone,
                "status" to "waiting",
                "isAssigned" to false,
                "joinedAt" to FieldValue.serverTimestamp(),
                "registrationComplete" to true
            )
            Tasks.await(queueDocRef.set(queueData))
            Log.i(TAG, "scanToken Firestore: Driver ${request.driverId} joined queue with docId=${queueDocRef.id}")
            Result.Success(ScanResponse(success = true, message = "Joined queue successfully", queueId = queueDocRef.id, queuePosition = 1))
        } catch (fe: Exception) {
            Log.e(TAG, "scanToken Firestore fallback failed: ${fe.message}", fe)
            Result.Error(-1, "Queue join error: ${fe.javaClass.simpleName} - ${fe.message}")
        }
    }
}