package com.sysu.go.network

import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Drivers receive only their rank; private queue documents remain owner/staff-only.
fun observeQueuePosition(owner: LifecycleOwner, queueId: String, receive: (Int?) -> Unit): Job {
    val uid = FirebaseAuth.getInstance().currentUser?.uid
    return owner.lifecycleScope.launch {
            while (isActive && uid != null && FirebaseAuth.getInstance().currentUser?.uid == uid) {
                if (!owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                    delay(5000)
                    continue
                }
                val position = withContext(Dispatchers.IO) {
                    try { QRApiService.ownPosition(queueId) }
                    catch (error: CancellationException) { throw error }
                    catch (_: Exception) { Log.w("QueuePosition", "Queue position unavailable"); null }
                }
                if (isActive && owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
                    && FirebaseAuth.getInstance().currentUser?.uid == uid) receive(position)
                delay(5000)
            }
    }
}
