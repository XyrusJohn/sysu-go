package com.sysu.go.network

import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.FirebaseAuth
import com.sysu.go.BuildConfig
import com.sysu.go.model.ScanRequest
import com.sysu.go.model.ScanResponse
import com.sysu.go.model.VerifyResponse
import java.util.concurrent.TimeUnit
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

object QRApiService {
    sealed class Result<out T> {
        data class Success<T>(val data: T): Result<T>()
        data class Error(val code: Int, val message: String): Result<Nothing>()
    }

    private val verifyClient = QrHttpClient(BuildConfig.API_BASE_URL + "/api/qr", { null })

    fun verifyToken(token: String): Result<VerifyResponse> = adapt(verifyClient.verifyToken(token))

    fun scanToken(request: ScanRequest): Result<ScanResponse> {
        val user = FirebaseAuth.getInstance().currentUser
        if (user == null || user.uid != request.driverId) return Result.Error(401, "Please sign in again")
        val client = QrHttpClient(BuildConfig.API_BASE_URL + "/api/qr",
            tokenProvider = { getOwnedToken(request.driverId, false) },
            forceRefreshTokenProvider = { getOwnedToken(request.driverId, true) })
        return adapt(client.scanToken(request))
    }

    private fun getOwnedToken(driverId: String, forceRefresh: Boolean): String? {
        val auth = FirebaseAuth.getInstance()
        val user = auth.currentUser ?: return null
        if (user.uid != driverId) return null
        val token = Tasks.await(user.getIdToken(forceRefresh), 10, TimeUnit.SECONDS).token
        return if (auth.currentUser?.uid == driverId) token else null
    }

    fun ownPosition(queueId: String): Int? {
        if (!queueId.matches(Regex("[A-Za-z0-9_-]{1,128}"))) return null
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return null
        val token = getOwnedToken(uid, false) ?: return null
        val connection = URL(BuildConfig.API_BASE_URL + "/api/qr/own-position?queueId=" + queueId)
            .openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 6000
            connection.readTimeout = 6000
            connection.setRequestProperty("Authorization", "Bearer $token")
            if (connection.responseCode != 200) return null
            val text = connection.inputStream.bufferedReader().use { reader ->
                val buffer = CharArray(1024)
                val result = StringBuilder()
                while (true) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    if (result.length + count > 4096) return null
                    result.append(buffer, 0, count)
                }
                result.toString()
            }
            val body = JSONObject(text)
            if (FirebaseAuth.getInstance().currentUser?.uid != uid || !body.optBoolean("success")
                || body.optString("queueId") != queueId || body.isNull("position")) return null
            val position = body.optInt("position", -1)
            return position.takeIf { it in 1..2000 }
        } finally { connection.disconnect() }
    }

    private fun <T> adapt(result: QrResult<T>): Result<T> = when (result) {
        is QrResult.Success -> Result.Success(result.data)
        is QrResult.Error -> Result.Error(result.code, result.message)
    }
}
