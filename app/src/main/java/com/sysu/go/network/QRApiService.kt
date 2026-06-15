package com.sysu.go.network

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

    fun verifyToken(token: String): Result<VerifyResponse> {
        return try {
            val encodedToken = URLEncoder.encode(token, "UTF-8")
            val url = URL("$BASE_URL/verify?token=$encodedToken")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 15000
            conn.readTimeout = 15000

            val responseCode = conn.responseCode
            val inputStream = if (responseCode in 200..299) conn.inputStream else conn.errorStream
            val body = inputStream?.bufferedReader()?.readText() ?: "{}"
            
            android.util.Log.d("QRApiService", "Verify Response ($responseCode): $body")
            
            val parsed = gson.fromJson(body, VerifyResponse::class.java)

            if (responseCode == 200 && parsed.success) {
                Result.Success(parsed)
            } else {
                Result.Error(responseCode, parsed.message ?: "Verification failed (Code: $responseCode)")
            }
        } catch (e: Exception) {
            android.util.Log.e("QRApiService", "Verify error: ${e.message}", e)
            Result.Error(-1, "Network error: ${e.javaClass.simpleName} - ${e.message}")
        }
    }

    fun scanToken(request: ScanRequest): Result<ScanResponse> {
        return try {
            val url = URL("$BASE_URL/scan")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "application/json")
            conn.connectTimeout = 15000
            conn.readTimeout = 15000

            OutputStreamWriter(conn.outputStream).use { w ->
                w.write(gson.toJson(request))
            }

            val responseCode = conn.responseCode
            val inputStream = if (responseCode in 200..299) conn.inputStream else conn.errorStream
            val body = inputStream?.bufferedReader()?.readText() ?: "{}"
            
            android.util.Log.d("QRApiService", "Scan Response ($responseCode): $body")
            
            val parsed = gson.fromJson(body, ScanResponse::class.java)

            if (responseCode == 200 && parsed.success) {
                Result.Success(parsed)
            } else {
                Result.Error(responseCode, parsed.message ?: "Scan failed (Code: $responseCode)")
            }
        } catch (e: Exception) {
            android.util.Log.e("QRApiService", "scanToken error: ${e.message}", e)
            Result.Error(-1, "Network error: ${e.javaClass.simpleName} - ${e.message}")
        }
    }
}