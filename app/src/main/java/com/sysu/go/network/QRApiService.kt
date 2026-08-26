package com.sysu.go.network

import android.util.Log
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
        return try {
            val encodedToken = URLEncoder.encode(token, "UTF-8")
            val urlString = "$BASE_URL/verify?token=$encodedToken"
            Log.d(TAG, "--> GET $urlString (token: $token)")
            val url = URL(urlString)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 15000
            conn.readTimeout = 15000

            val responseCode = conn.responseCode
            val inputStream = if (responseCode in 200..299) conn.inputStream else conn.errorStream
            val body = inputStream?.bufferedReader()?.readText() ?: "{}"
            
            Log.d(TAG, "<-- Verify Response ($responseCode): $body")
            
            val parsed = gson.fromJson(body, VerifyResponse::class.java)

            if (responseCode == 200 && parsed.success) {
                Log.d(TAG, "verifyToken SUCCESS: token=${parsed.token}")
                Result.Success(parsed)
            } else {
                Log.w(TAG, "verifyToken FAILED ($responseCode): ${parsed.message}")
                Result.Error(responseCode, parsed.message ?: "Verification failed (Code: $responseCode)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Verify error: ${e.message}", e)
            Result.Error(-1, "Network error: ${e.javaClass.simpleName} - ${e.message}")
        }
    }

    fun scanToken(request: ScanRequest): Result<ScanResponse> {
        return try {
            val urlString = "$BASE_URL/scan"
            Log.d(TAG, "--> POST $urlString (driverId: ${request.driverId}, plate: ${request.plateNumber}, token: ${request.token})")
            val url = URL(urlString)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "application/json")
            conn.connectTimeout = 15000
            conn.readTimeout = 15000

            val jsonBody = gson.toJson(request)
            Log.d(TAG, "Scan request body: $jsonBody")
            OutputStreamWriter(conn.outputStream).use { w ->
                w.write(jsonBody)
            }

            val responseCode = conn.responseCode
            val inputStream = if (responseCode in 200..299) conn.inputStream else conn.errorStream
            val body = inputStream?.bufferedReader()?.readText() ?: "{}"
            
            Log.d(TAG, "<-- Scan Response ($responseCode): $body")
            
            val parsed = gson.fromJson(body, ScanResponse::class.java)

            if (responseCode == 200 && parsed.success) {
                Log.d(TAG, "scanToken SUCCESS: queueId=${parsed.queueId}, queuePosition=${parsed.queuePosition}")
                Result.Success(parsed)
            } else {
                Log.w(TAG, "scanToken FAILED ($responseCode): ${parsed.message}")
                Result.Error(responseCode, parsed.message ?: "Scan failed (Code: $responseCode)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "scanToken error: ${e.message}", e)
            Result.Error(-1, "Network error: ${e.javaClass.simpleName} - ${e.message}")
        }
    }
}