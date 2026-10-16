package com.sysu.go.network

import com.google.firebase.auth.FirebaseAuth
import com.google.gson.Gson
import com.sysu.go.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.tasks.await
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Server-owned atomic OBD/image progress. UI succeeds only after committed response. */
object PodApiService {
    suspend fun updateGroup(shipmentId: String, payload: Map<String, Any?>): Map<String, Any?> {
        val user = FirebaseAuth.getInstance().currentUser ?: error("Please sign in again")
        val token = user.getIdToken(true).await().token ?: error("Please sign in again")
        check(FirebaseAuth.getInstance().currentUser?.uid == user.uid) { "Account changed. Please retry" }
        return withContext(Dispatchers.IO) {
            val segment = URLEncoder.encode(shipmentId, "UTF-8").replace("+", "%20")
            val connection = URL(BuildConfig.API_BASE_URL + "/api/pod/" + segment + "/group").openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "POST"
                connection.connectTimeout = 15000
                connection.readTimeout = 30000
                connection.doOutput = true
                connection.setRequestProperty("Authorization", "Bearer " + token)
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(Gson().toJson(payload)) }
                val code = connection.responseCode
                val body = (if (code in 200..299) connection.inputStream else connection.errorStream)?.bufferedReader()?.use { it.readText() }
                val parsed = runCatching { Gson().fromJson(body, Map::class.java)?.entries?.associate { it.key.toString() to it.value } }.getOrNull()
                    ?: emptyMap()
                check(code in 200..299 && parsed["success"] == true) { "POD group was not saved. Check connection and retry." }
                parsed
            } finally {
                connection.disconnect()
            }
        }
    }

    suspend fun update(shipmentId: String, obdNumber: String, payload: Map<String, Any?>) {
        val user = FirebaseAuth.getInstance().currentUser ?: error("Please sign in again")
        val token = user.getIdToken(true).await().token ?: error("Please sign in again")
        check(FirebaseAuth.getInstance().currentUser?.uid == user.uid) { "Account changed. Please retry" }
        withContext(Dispatchers.IO) {
            fun segment(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
            val connection = URL(BuildConfig.API_BASE_URL + "/api/pod/" + segment(shipmentId) + "/" + segment(obdNumber)).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "POST"
                connection.connectTimeout = 15000
                connection.readTimeout = 15000
                connection.doOutput = true
                connection.setRequestProperty("Authorization", "Bearer " + token)
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(Gson().toJson(payload)) }
                val code = connection.responseCode
                val body = (if (code in 200..299) connection.inputStream else connection.errorStream)?.bufferedReader()?.use { it.readText() }
                val success = runCatching { Gson().fromJson(body, Map::class.java)["success"] == true }.getOrDefault(false)
                check(code in 200..299 && success) { "POD was not saved. Check connection and retry." }
            } finally {
                connection.disconnect()
            }
        }
    }
}
