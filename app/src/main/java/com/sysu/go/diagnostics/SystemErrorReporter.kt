package com.sysu.go.diagnostics

import android.content.Context
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.sysu.go.BuildConfig
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** Sends minimal, authenticated operational diagnostics without exception messages or payloads. */
object SystemErrorReporter {
    private const val TAG = "SystemErrorReporter"
    private const val PREFS = "system_diagnostics"
    private const val PENDING_CRASH = "pending_crash_name"
    private const val PENDING_CRASH_UID = "pending_crash_uid"
    private val executor = Executors.newSingleThreadExecutor()
    private val recentReports = ConcurrentHashMap<String, Long>()
    @Volatile private var installed = false

    @Synchronized
    fun install(context: Context) {
        if (installed) return
        installed = true
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val name = safeErrorName(error.javaClass.simpleName)
                val uid = FirebaseAuth.getInstance().currentUser?.uid
                if (uid != null) appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putString(PENDING_CRASH, name).putString(PENDING_CRASH_UID, uid).commit()
            } catch (_: Exception) {
                Log.w(TAG, "Could not save crash diagnostic")
            } finally {
                if (previous != null) previous.uncaughtException(thread, error)
                else {
                    android.os.Process.killProcess(android.os.Process.myPid())
                    kotlin.system.exitProcess(10)
                }
            }
        }
    }

    fun flushPendingCrash(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val name = prefs.getString(PENDING_CRASH, null) ?: return false
        val currentUid = FirebaseAuth.getInstance().currentUser?.uid ?: return false
        if (prefs.getString(PENDING_CRASH_UID, null) != currentUid) {
            prefs.edit().remove(PENDING_CRASH).remove(PENDING_CRASH_UID).apply()
            return false
        }
        sendAuthenticated("mobile_uncaught_exception", "app-runtime", name) {
            prefs.edit().remove(PENDING_CRASH).remove(PENDING_CRASH_UID).apply()
        }
        return true
    }

    fun reportOperationFailure(operation: String, error: Throwable) {
        reportOperationFailure(operation, error.javaClass.simpleName)
    }

    fun reportOperationFailure(operation: String, errorName: String) {
        if (!operation.matches(Regex("^[a-z][a-z0-9_-]{0,47}$"))) return
        sendAuthenticated("mobile_operation_failure", operation, safeErrorName(errorName)) {}
    }

    private fun sendAuthenticated(eventCode: String, operation: String, errorName: String, onSuccess: () -> Unit) {
        val key = "$eventCode:$operation:$errorName"
        val now = System.currentTimeMillis()
        val prior = recentReports.put(key, now)
        if (prior != null && now - prior < 60_000) return
        val user = FirebaseAuth.getInstance().currentUser ?: return
        user.getIdToken(false).addOnSuccessListener { result ->
            val token = result.token ?: return@addOnSuccessListener
            executor.execute {
                var connection: HttpURLConnection? = null
                try {
                    connection = URL(BuildConfig.API_BASE_URL.trimEnd('/') + "/api/system-errors/report")
                        .openConnection() as HttpURLConnection
                    connection.requestMethod = "POST"
                    connection.connectTimeout = 8000
                    connection.readTimeout = 8000
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json")
                    connection.setRequestProperty("Authorization", "Bearer $token")
                    val body = JSONObject().put("eventCode", eventCode).put("operation", operation)
                        .put("errorName", errorName).toString()
                    connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                    if (connection.responseCode in 200..299) onSuccess()
                } catch (error: Exception) {
                    Log.w(TAG, "Could not submit diagnostic report (${error.javaClass.simpleName})")
                } finally {
                    connection?.disconnect()
                }
            }
        }.addOnFailureListener { error ->
            Log.w(TAG, "Could not authenticate diagnostic report (${error.javaClass.simpleName})")
        }
    }

    private fun safeErrorName(value: String): String =
        value.takeIf { it.matches(Regex("^[A-Za-z][A-Za-z0-9_.]{0,79}$")) } ?: "Error"
}
