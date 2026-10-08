package com.sysu.go.network
import com.sysu.go.model.ScanRequest
import com.sysu.go.model.ScanResponse
import com.sysu.go.model.VerifyResponse
import java.net.HttpURLConnection
import java.net.URL

sealed class QrResult<out T> {
    data class Success<T>(val data: T): QrResult<T>()
    data class Error(val code: Int, val message: String): QrResult<Nothing>()
}
class QrHttpClient(private val baseUrl: String, private val tokenProvider: () -> String?, private val openConnection: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection }) {
    fun verifyToken(token: String): QrResult<VerifyResponse> = TODO("read-only verification")
    fun scanToken(request: ScanRequest): QrResult<ScanResponse> = TODO("authenticated server-only admission")
}
