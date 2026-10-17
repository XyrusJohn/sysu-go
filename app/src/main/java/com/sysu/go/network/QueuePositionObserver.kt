package com.sysu.go.network

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Job

// The backend emits only this authenticated driver's FIFO rank, never queue records.
fun observeQueuePosition(owner: LifecycleOwner, queueId: String, receive: (Int?) -> Unit): Job {
    val uid = FirebaseAuth.getInstance().currentUser?.uid
    val job = Job()
    val mainHandler = Handler(Looper.getMainLooper())
    var unsubscribe: (() -> Unit)? = null

    fun stopSubscription() {
        unsubscribe?.invoke()
        unsubscribe = null
    }

    fun startSubscription() {
        if (job.isCancelled || unsubscribe != null || uid == null
            || FirebaseAuth.getInstance().currentUser?.uid != uid) return
        unsubscribe = SocketManager.subscribeRealtime(
            topic = "queuePosition",
            params = mapOf("queueId" to queueId),
            onData = { payload ->
                val data = payload as? Map<*, *> ?: return@subscribeRealtime
                if (data["queueId"] != queueId || FirebaseAuth.getInstance().currentUser?.uid != uid) return@subscribeRealtime
                receive((data["position"] as? Number)?.toInt())
            },
            onError = { error ->
                Log.w("QueuePosition", "Queue position unavailable: $error")
                receive(null)
            },
        )
    }

    val observer = LifecycleEventObserver { _, event ->
        when (event) {
            Lifecycle.Event.ON_START -> startSubscription()
            Lifecycle.Event.ON_STOP -> stopSubscription()
            Lifecycle.Event.ON_DESTROY -> job.cancel()
            else -> Unit
        }
    }
    owner.lifecycle.addObserver(observer)
    job.invokeOnCompletion {
        mainHandler.post {
            stopSubscription()
            owner.lifecycle.removeObserver(observer)
        }
    }
    if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) startSubscription()
    return job
}
