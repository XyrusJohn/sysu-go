package com.sysu.go.network

import com.google.gson.Gson
import com.google.gson.JsonParseException
import com.sysu.go.model.ScanRequest
import com.sysu.go.model.ScanResponse
import com.sysu.go.model.VerifyResponse
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

sealed class QrResult<out T> {
    data class Success<T>(val data: T): QrResult<T>()
    data class Error(val code: Int, val message: String): QrResult<Nothing>()
}

/** Blocking transport: callers must run on their IO dispatcher. No database fallback. */
class QrHttpClient(
    private val baseUrl: String,
    private val tokenProvider: () -> String?,
    private val openConnection: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
    private val forceRefreshTokenProvider: () -> String? = tokenProvider
) {
    private val gson = Gson()
    private fun validToken(token: String) = token.matches(Regex("[A-Za-z0-9_-]{1,128}"))

    fun verifyToken(token: String): QrResult<VerifyResponse> {
        if (!validToken(token)) return QrResult.Error(400, "Invalid QR token")
        val result = exchange("/verify?token=" + URLEncoder.encode(token, "UTF-8"), "GET", null, null, VerifyResponse::class.java)
        return when (result) {
            is QrResult.Success -> if (result.data.success && result.data.token == token) result else QrResult.Error(502, "Invalid verification response")
            is QrResult.Error -> result
        }
    }

    fun scanToken(request: ScanRequest): QrResult<ScanResponse> {
        if (!validToken(request.token)) return QrResult.Error(400, "Invalid QR token")
        val bearer = try { tokenProvider() } catch (_: Exception) { null }
        if (bearer.isNullOrBlank()) return QrResult.Error(401, "Please sign in again")
        val body = gson.toJson(request).toByteArray(Charsets.UTF_8)
        if (body.size > 8192) return QrResult.Error(400, "QR registration is too large")
        var result = exchange("/scan", "POST", bearer, body, ScanResponse::class.java)
        // Only an explicit authentication denial is safe to retry; a timeout may follow a committed admission.
        if (result is QrResult.Error && result.code == 401) {
            val refreshed = try { forceRefreshTokenProvider() } catch (_: Exception) { null }
            if (refreshed.isNullOrBlank()) return QrResult.Error(401, "Please sign in again")
            result = exchange("/scan", "POST", refreshed, body, ScanResponse::class.java)
        }
        return when (result) {
            is QrResult.Success -> if (result.data.success && !result.data.queueId.isNullOrBlank()) result else QrResult.Error(502, "Invalid admission response")
            is QrResult.Error -> result
        }
    }

    private fun <T> exchange(route: String, method: String, bearer: String?, body: ByteArray?, type: Class<T>): QrResult<T> {
        var connection: HttpURLConnection? = null
        return try {
            val url = URL(baseUrl.trimEnd('/') + route)
            if (url.protocol != "https" && !(url.protocol == "http" && url.host in setOf("127.0.0.1", "localhost", "10.0.2.2"))) {
                return QrResult.Error(400, "Unsafe QR service URL")
            }
            connection = openConnection(url)
            connection.instanceFollowRedirects = false
            connection.requestMethod = method
            connection.connectTimeout = 6000
            connection.readTimeout = 6000
            connection.setRequestProperty("Accept", "application/json")
            if (bearer != null) connection.setRequestProperty("Authorization", "Bearer " + bearer)
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body) }
            }
            val code = connection.responseCode
            if (code != 200) {
                connection.errorStream?.close()
                return QrResult.Error(code, when (code) {
                    401 -> "Please sign in again"
                    403 -> "This account cannot perform this action"
                    409 -> "Queue admission requires review or refresh"
                    410 -> "QR token is expired or already used"
                    else -> "QR service could not complete the request"
                })
            }
            val bytes = ByteArrayOutputStream()
            connection.inputStream.use { stream ->
                val buffer = ByteArray(4096)
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    if (bytes.size() + count > 65536) return QrResult.Error(502, "QR response is too large")
                    bytes.write(buffer, 0, count)
                }
            }
            val parsed = gson.fromJson(bytes.toString("UTF-8"), type)
            if (parsed == null) QrResult.Error(502, "Invalid QR response") else QrResult.Success(parsed)
        } catch (_: JsonParseException) {
            QrResult.Error(502, "Invalid QR response")
        } catch (_: IOException) {
            QrResult.Error(-1, "QR service unavailable. Refresh your queue before retrying.")
        } catch (_: Exception) {
            QrResult.Error(502, "Unable to complete QR request")
        } finally {
            connection?.disconnect()
        }
    }
}
