package com.sysu.go.utils

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.sysu.go.utils.TrackingStateMonitor
import java.util.concurrent.CopyOnWriteArrayList

object ConnectivityMonitor {
    private const val TAG = "ConnectivityMonitor"

    enum class Status {
        ONLINE_WIFI,
        ONLINE_CELLULAR,
        ONLINE_ETHERNET,
        ONLINE_UNKNOWN,
        OFFLINE
    }

    interface ConnectivityChangeListener {
        fun onConnectivityChanged(status: Status)
    }

    private val listeners = CopyOnWriteArrayList<ConnectivityChangeListener>()
    private var currentStatus: Status = Status.OFFLINE
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    fun addListener(listener: ConnectivityChangeListener) {
        listeners.add(listener)
        // Immediately notify the new listener of the current status
        listener.onConnectivityChanged(currentStatus)
    }

    fun removeListener(listener: ConnectivityChangeListener) {
        listeners.remove(listener)
    }

    private fun notifyListeners(status: Status) {
        if (status != currentStatus) {
            Log.d(TAG, "Connectivity status changed: $currentStatus -> $status")
            currentStatus = status
            listeners.forEach { it.onConnectivityChanged(status) }
            // Mirror OFFLINE/ONLINE to TrackingStateMonitor so Activities only need one listener
            when (status) {
                Status.OFFLINE -> TrackingStateMonitor.setState(TrackingStateMonitor.State.OFFLINE)
                else -> { /* non-offline states don't change TrackingStateMonitor unless already OFFLINE */
                    if (TrackingStateMonitor.getCurrentState() == TrackingStateMonitor.State.OFFLINE) {
                        TrackingStateMonitor.setState(TrackingStateMonitor.State.ONLINE)
                    }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun start(context: Context) {
        if (connectivityManager != null && networkCallback != null) {
            Log.w(TAG, "ConnectivityMonitor already started.")
            return
        }

        connectivityManager = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                super.onAvailable(network)
                Log.w(TAG, ">>> NetworkCallback: onAvailable(network=$network)")
                updateConnectivityStatus()
            }

            override fun onLost(network: Network) {
                super.onLost(network)
                Log.w(TAG, ">>> NetworkCallback: onLost(network=$network)")
                updateConnectivityStatus()
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                super.onCapabilitiesChanged(network, networkCapabilities)
                Log.w(TAG, ">>> NetworkCallback: onCapabilitiesChanged(network=$network, caps=${
                    listOfNotNull(
                        if (networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) "WIFI" else null,
                        if (networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) "CELLULAR" else null,
                        if (networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) "ETHERNET" else null
                    ).joinToString().ifEmpty { "UNKNOWN" }
                })")
                updateConnectivityStatus()
            }
        }

        val networkRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        connectivityManager?.registerNetworkCallback(networkRequest, networkCallback!!)
        Log.d(TAG, "ConnectivityMonitor started with NetworkCallback")

        // Initial check
        updateConnectivityStatus()
    }

    fun stop() {
        if (connectivityManager != null && networkCallback != null) {
            connectivityManager?.unregisterNetworkCallback(networkCallback!!)
            connectivityManager = null
            networkCallback = null
            Log.d(TAG, "ConnectivityMonitor stopped.")
        }
        listeners.clear()
    }

    @SuppressLint("MissingPermission")
    private fun updateConnectivityStatus() {
        val cm = connectivityManager ?: return
        val activeNetwork = cm.activeNetwork
        val caps = cm.getNetworkCapabilities(activeNetwork)

        val newStatus = if (caps == null) {
            Status.OFFLINE
        } else {
            when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Status.ONLINE_WIFI
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Status.ONLINE_CELLULAR
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Status.ONLINE_ETHERNET
                else -> Status.ONLINE_UNKNOWN
            }
        }

        if (newStatus != currentStatus) {
            val driverName = FirebaseAuth.getInstance().currentUser?.displayName ?: "Driver"
            when (newStatus) {
                Status.OFFLINE -> Log.w(TAG, "[$driverName] 🌐 OFFLINE")
                Status.ONLINE_WIFI -> Log.i(TAG, "[$driverName] 🌐 ONLINE — WIFI")
                Status.ONLINE_CELLULAR -> Log.i(TAG, "[$driverName] 🌐 ONLINE — CELLULAR")
                Status.ONLINE_ETHERNET -> Log.i(TAG, "[$driverName] 🌐 ONLINE — ETHERNET")
                Status.ONLINE_UNKNOWN -> Log.i(TAG, "[$driverName] 🌐 ONLINE — UNKNOWN")
            }
            // Log network switch event if coming from another online state
            if (currentStatus != Status.OFFLINE && newStatus != Status.OFFLINE) {
                val from = currentStatus.name.removePrefix("ONLINE_")
                val to = newStatus.name.removePrefix("ONLINE_")
                Log.i(TAG, "[$driverName] 🔄 Network switched: $from → $to")
            }
            notifyListeners(newStatus)
        }
    }

    // Expose current status
    fun getCurrentStatus(): Status = currentStatus
}