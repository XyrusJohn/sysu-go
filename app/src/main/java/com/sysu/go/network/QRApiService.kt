package com.sysu.go.network

import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.FirebaseAuth
import com.sysu.go.BuildConfig
import com.sysu.go.model.ScanRequest
import com.sysu.go.model.ScanResponse
import com.sysu.go.model.VerifyResponse
import java.util.concurrent.TimeUnit

object QRApiService {
    sealed class Result<out T> {
        data class Success<T>(val data: T): Result<T>()
        data class Error(val code: Int, val message: String): Result<Nothing>()
    }

    private val client = QrHttpClient(BuildConfig.API_BASE_URL + "/api/qr", {
        val user = FirebaseAuth.getInstance().currentUser
        // Firebase refreshes expired tokens; the server checks revocation and profile status.
        if (user == null) null else Tasks.await(user.getIdToken(false), 10, TimeUnit.SECONDS).token
    })

    fun verifyToken(token: String): Result<VerifyResponse> = adapt(client.verifyToken(token))

    fun scanToken(request: ScanRequest): Result<ScanResponse> {
        val user = FirebaseAuth.getInstance().currentUser
        if (user == null || user.uid != request.driverId) return Result.Error(401, "Please sign in again")
        return adapt(client.scanToken(request))
    }

    private fun <T> adapt(result: QrResult<T>): Result<T> = when (result) {
        is QrResult.Success -> Result.Success(result.data)
        is QrResult.Error -> Result.Error(result.code, result.message)
    }
}
