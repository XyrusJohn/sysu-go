package com.sysu.go.repository

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.sysu.go.model.SecurityGuard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * Reads security guards from Firestore (security_guard collection) and caches
 * them in SharedPreferences. All methods are safe to call on the main thread —
 * network calls are coroutine-backed.
 *
 * Cache key: security_guards → JSON array of SecurityGuard
 */
class SecurityGuardRepository(context: Context) {

    private val context: Context = context.applicationContext
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREF_PREFS_NAME, Context.MODE_PRIVATE)

    private val firestore: FirebaseFirestore
        get() = Firebase.firestore

    private val gson = Gson()

    companion object {
        private const val PREF_PREFS_NAME = "sysu_go_prefs"
        private const val PREF_SECURITY_GUARDS = "security_guards"
        private const val COLLECTION = "security_guard"
        private const val TAG = "SecurityGuardRepo"
    }

    /**
     * Returns the cached list of guards. Returns empty list if never cached.
     * NEVER makes a network call — use [refreshGuards] to populate from Firestore.
     */
    fun getCachedGuards(): List<SecurityGuard> {
        val json = prefs.getString(PREF_SECURITY_GUARDS, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<SecurityGuard>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "Cache parse failed, dropping it", e)
            prefs.edit { remove(PREF_SECURITY_GUARDS) }
            emptyList()
        }
    }

    /**
     * Fetches all active guards from Firestore, caches to SharedPreferences, and
     * returns the list. Falls back to existing cache if Firestore is unreachable.
     */
    suspend fun refreshGuards(): List<SecurityGuard> {
        return try {
            val guards = fetchFromFirestore()
            if (guards.isNotEmpty()) {
                cacheToPrefs(guards)
            } else {
                Log.w(TAG, "Firestore returned 0 guards — keeping existing cache")
            }
            guards
        } catch (e: Exception) {
            Log.e(TAG, "refreshGuards failed: ${e.message}", e)
            // Return whatever is cached on error
            getCachedGuards().also {
                Log.w(TAG, "Falling back to ${it.size} cached guards")
            }
        }
    }

    private fun cacheToPrefs(guards: List<SecurityGuard>) {
        val json = gson.toJson(guards)
        prefs.edit { putString(PREF_SECURITY_GUARDS, json) }
        Log.i(TAG, "Cached ${guards.size} security guards")
    }

    private suspend fun fetchFromFirestore(): List<SecurityGuard> = withContext(Dispatchers.IO) {
        Log.d(TAG, "Fetching guards from Firestore collection '$COLLECTION'")
        try {
            val snapshot = firestore.collection(COLLECTION)
                .whereEqualTo("active", true)
                .get()
                .await()

            Log.d(TAG, "Firestore returned ${snapshot.size()} documents")
            snapshot.documents.mapNotNull { doc ->
                val guardId = doc.getString("guardId") ?: return@mapNotNull null
                val firstName = doc.getString("firstName") ?: return@mapNotNull null
                val middleInitial = doc.getString("middleInitial") ?: ""
                val lastName = doc.getString("lastName") ?: return@mapNotNull null
                val fullName = doc.getString("fullName")
                    ?: "$firstName ${middleInitial.takeIf { it.isNotBlank() }?.plus(" ") ?: ""}$lastName".trim()
                SecurityGuard(
                    guardId = guardId,
                    firstName = firstName,
                    middleInitial = middleInitial,
                    lastName = lastName,
                    fullName = fullName,
                    active = doc.getBoolean("active") ?: true
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "fetchFromFirestore EXCEPTION: ${e.message}", e)
            emptyList()
        }
    }
}