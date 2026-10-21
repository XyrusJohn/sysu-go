package com.sysu.go.repository

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.sysu.go.model.SecurityGuard

/** Caches active security guards delivered by the authenticated Socket.IO stream. */
class SecurityGuardRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREF_PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    companion object {
        private const val PREF_PREFS_NAME = "sysu_go_prefs"
        private const val PREF_SECURITY_GUARDS = "security_guards"
        private const val TAG = "SecurityGuardRepo"
    }

    /** Returns the last known list without performing a network request. */
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

    /** Replaces the local cache from a full active-guard snapshot. Empty snapshots clear stale entries. */
    fun updateFromRealtime(data: Any?): List<SecurityGuard> {
        val guards = (data as? List<*>)?.mapNotNull { row ->
            val fields = row as? Map<*, *> ?: return@mapNotNull null
            val guardId = fields["guardId"] as? String ?: return@mapNotNull null
            val firstName = fields["firstName"] as? String ?: return@mapNotNull null
            val lastName = fields["lastName"] as? String ?: return@mapNotNull null
            val active = fields["active"] as? Boolean ?: false
            if (!active) return@mapNotNull null

            val middleInitial = fields["middleInitial"] as? String ?: ""
            val fullName = fields["fullName"] as? String
                ?: "$firstName ${middleInitial.takeIf { it.isNotBlank() }?.plus(" ") ?: ""}$lastName".trim()
            SecurityGuard(guardId, firstName, middleInitial, lastName, fullName, active)
        } ?: emptyList()
        prefs.edit { putString(PREF_SECURITY_GUARDS, gson.toJson(guards)) }
        return guards
    }
}
