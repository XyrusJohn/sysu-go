package com.sysu.go.network

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.sysu.go.BuildConfig
import com.sysu.go.utils.ConnectivityMonitor
import io.socket.client.IO
import io.socket.client.Socket
import org.json.JSONObject
import java.net.URISyntaxException

object SocketManager : ConnectivityMonitor.ConnectivityChangeListener { // Added interface
    private const val TAG = "SocketManager"
    private const val SOCKET_URL_GO = BuildConfig.SOCKET_URL
    private const val SOCKET_URL_APP = "https://sysu-app-production.up.railway.app"
    private const val PREFS_NAME = "driver_session"
    private const val KEY_DRIVER_NAME = "driver_name"
    private const val KEY_LAST_EMIT_LAT = "last_emit_lat"
    private const val KEY_LAST_EMIT_LNG = "last_emit_lng"

    private var mSocketGo: Socket? = null
    private var mSocketApp: Socket? = null
    private var prefs: SharedPreferences? = null

    private val connectListeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()
    @Volatile
    private var listenersNotified = false  // one-shot: fire only on first socket connect
    @Volatile
    private var offlineEmitted = false  // guard: emit offline at most once per disconnect cycle

    // ─── Generic event listener system ─────────────────────────────────────────

    /** Maps event name → list of listeners invoked on that event */
    private val eventListeners = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.CopyOnWriteArrayList<(data: Any?) -> Unit>>()

    /** Tracks which events are registered on each socket to avoid double-registration */
    private val registeredEvents = java.util.concurrent.ConcurrentHashMap<Socket, MutableSet<String>>()

    /**
     * Registers a listener for the named Socket.IO event on both sockets.
     * When the socket reconnects, the listener is automatically re-registered.
     * Listeners are deduplicated per event per socket.
     */
    fun addEventListener(event: String, listener: (data: Any?) -> Unit) {
        val listeners = eventListeners.getOrPut(event) {
            java.util.concurrent.CopyOnWriteArrayList()
        }
        listeners.add(listener)

        // Register on sockets that are already connected
        listOf(mSocketGo, mSocketApp).forEach { socket ->
            registerEventListenerOnSocket(event, listener, socket)
        }
    }

    fun removeEventListener(event: String) {
        eventListeners.remove(event)
        listOf(mSocketGo, mSocketApp).forEach { socket ->
            unregisterEventListenerOnSocket(event, null, socket)
        }
    }

    /**
     * Removes a previously registered listener. Safe to call even if the
     * socket has since disconnected — the event key is cleaned up on unregister.
     */
    fun removeEventListener(event: String, listener: (data: Any?) -> Unit) {
        eventListeners[event]?.remove(listener)
        if (eventListeners[event]?.isEmpty() == true) {
            eventListeners.remove(event)
            // Unregister the socket listener so Socket.IO stops dispatching this event
            listOf(mSocketGo, mSocketApp).forEach { socket ->
                unregisterEventListenerOnSocket(event, listener, socket)
            }
        }
    }

    private fun registerEventListenerOnSocket(event: String, listener: (data: Any?) -> Unit, socket: Socket?) {
        socket ?: return
        val registered = registeredEvents.getOrPut(socket) { mutableSetOf() }
        val wrappedListener: (Array<Any>) -> Unit = { args ->
            try { listener(args.firstOrNull()) } catch (e: Exception) {
                Log.e(TAG, "Event listener for '$event' threw: ${e.message}")
            }
        }
        // Store wrapped listener so we can unregister the exact same reference
        socket.on(event, wrappedListener)
        registered.add(event)
        Log.d(TAG, "Registered socket listener for event='$event' on ${socket.id()}")
    }

    private fun unregisterEventListenerOnSocket(event: String, listener: ((data: Any?) -> Unit)?, socket: Socket?) {
        socket ?: return
        // Socket.IO's off() needs the exact same function reference that was passed to on()
        // Since we wrapped it, we need to emit a no-op registration to remove all listeners for this event
        // Socket.IO 3.x supports socket.off(event) which removes ALL listeners for that event
        try {
            socket.emit(event) // no-op emit — won't send since emit bypasses event system on server
        } catch (_: Exception) { }
        socket.off(event)
        registeredEvents[socket]?.remove(event)
    }

    /**
     * Registers all currently known event listeners on a newly-connected socket.
     * Called after each socket connect so reconnection automatically restores listeners.
     */
    private fun registerAllListenersOnSocket(socket: Socket) {
        val registered = registeredEvents.getOrPut(socket) { mutableSetOf() }
        eventListeners.forEach { (event, listeners) ->
            if (event !in registered) {
                listeners.forEach { listener ->
                    registerEventListenerOnSocket(event, listener, socket)
                }
            } else {
                // Event already registered — skip (handles duplicate connect events)
            }
        }
    }

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        // Start ConnectivityMonitor and add this SocketManager as a listener
        ConnectivityMonitor.start(context.applicationContext)
        ConnectivityMonitor.addListener(this)
        Log.d(TAG, "SocketManager initialized and registered as ConnectivityMonitor listener.")
    }

    fun addOnConnectListener(listener: () -> Unit) {
        connectListeners.add(listener)
        // If the socket is already connected by the time this listener is registered
        // (can happen on rapid connect in RESTORE path where socket starts async
        // connect in onCreate but listener is added after), fire immediately.
        if (mSocketGo?.connected() == true || mSocketApp?.connected() == true) {
            try { listener() } catch (e: Exception) { Log.e(TAG, "Connect listener threw: ${e.message}") }
        }
    }

    fun removeOnConnectListener(listener: () -> Unit) {
        connectListeners.remove(listener)
    }

    private fun notifyConnectListeners() {
        // Always notify — both fresh connect and reconnect. Listeners perform idempotent
        // operations (emitDriverMoving to restore session, Firestore idempotent update).
        listenersNotified = true
        connectListeners.forEach { listener ->
            try { listener() } catch (e: Exception) { Log.e(TAG, "Connect listener threw: ${e.message}") }
        }
    }

    fun connect() {
        Log.i(TAG, ">>> connect() called — resetting offlineEmitted=false, listenersNotified=false")
        offlineEmitted = false
        listenersNotified = false
        Log.i(TAG, ">>> Attempting connectGo()...")
        connectGo()
        Log.i(TAG, ">>> After connectGo(), mSocketGo.connected()=${mSocketGo?.connected()}")
        Log.i(TAG, ">>> Attempting connectApp()...")
        connectApp()
        Log.i(TAG, ">>> After connectApp(), mSocketApp.connected()=${mSocketApp?.connected()}")
    }

    private fun connectGo() {
        Log.i(TAG, ">>> connectGo() called. current mSocketGo=connected:${mSocketGo?.connected()}")
        if (mSocketGo?.connected() == true) {
            Log.i(TAG, ">>> connectGo(): Already connected, skipping")
            return
        }
        connectTo(SOCKET_URL_GO) { socket -> mSocketGo = socket }
    }

    private fun connectApp() {
        Log.i(TAG, ">>> connectApp() called. current mSocketApp=connected:${mSocketApp?.connected()}")
        if (mSocketApp?.connected() == true) {
            Log.i(TAG, ">>> connectApp(): Already connected, skipping")
            return
        }
        connectTo(SOCKET_URL_APP) { socket -> mSocketApp = socket }
    }

    private fun connectTo(url: String, onSocket: (Socket) -> Unit) {
        val user = FirebaseAuth.getInstance().currentUser
        if (user == null) {
            Log.w(TAG, ">>> connectTo($url): NO authenticated user — cannot connect")
            return
        }
        Log.i(TAG, ">>> connectTo($url): User=${user.uid}, requesting ID token...")

        user.getIdToken(true).addOnSuccessListener { result ->
            val token = result.token
            Log.i(TAG, ">>> connectTo($url): Got token, connecting socket to $url...")
            try {
                val opts = IO.Options().apply {
                    reconnection = true
                    reconnectionAttempts = 10
                    reconnectionDelay = 2000
                    reconnectionDelayMax = 5000
                    auth = mapOf("token" to token)
                }
                val socket = IO.socket(url, opts)
                Log.i(TAG, ">>> connectTo($url): Socket.IO created. opts.reconnection=true, attempts=10, delay=2000ms")

                socket.on(Socket.EVENT_CONNECT) {
                    Log.i(TAG, ">>> EVENT_CONNECT! $url → socket.id=${socket.id()}")
                    // Re-register all event listeners on this newly-connected socket
                    registerAllListenersOnSocket(socket)
                    offlineEmitted = false
                    emitStatusUpdateOnSocket(socket, "online", getDriverName())
                    notifyConnectListeners()
                    Log.i(TAG, ">>> update_status ONLINE emitted for $url")
                }

                socket.on(Socket.EVENT_DISCONNECT) {
                    Log.w(TAG, ">>> EVENT_DISCONNECT! $url → socket.id=${socket.id()}")
                    Log.w(TAG, ">>> Current offlineEmitted=$offlineEmitted")
                    if (!offlineEmitted) {
                        offlineEmitted = true
                        val liveSocket = if (socket === mSocketGo) mSocketApp else mSocketGo
                        Log.w(TAG, ">>> Emitting OFFLINE via remaining live socket ${liveSocket?.id()}")
                        emitStatusUpdateOnSocket(liveSocket, "offline", getDriverName())
                    } else {
                        Log.w(TAG, ">>> offlineEmitted already true, skipping offline emit")
                    }
                }

                socket.on(Socket.EVENT_CONNECT_ERROR) { args ->
                    Log.e(TAG, ">>> EVENT_CONNECT_ERROR! $url → ${args.joinToString()}")
                }

                socket.on("update_admin_map") { args ->
                    if (args.isNotEmpty()) {
                        Log.d(TAG, "[$url] update_admin_map: ${args[0]}")
                    }
                }

                Log.i(TAG, ">>> Calling socket.connect() for $url")
                socket.connect()
                onSocket(socket)
                Log.i(TAG, ">>> socket.connect() initiated for $url")
            } catch (e: URISyntaxException) {
                Log.e(TAG, ">>> connectTo($url): URISyntaxException: ${e.message}")
            } catch (e: Exception) {
                Log.e(TAG, ">>> connectTo($url): Exception: ${e.message}")
            }
        }.addOnFailureListener { e ->
            Log.e(TAG, ">>> connectTo($url): getIdToken FAILED: ${e.message}")
        }
    }

    fun connect(context: Context) {
        init(context)
        connect()
    }

    fun getDriverName(): String {
        return prefs?.getString(KEY_DRIVER_NAME, null)
            ?: FirebaseAuth.getInstance().currentUser?.displayName
            ?: "Driver"
    }

    fun emitStatusUpdate(status: String, name: String, lat: Double? = null, lng: Double? = null) {
        emitStatusUpdateOnSocket(mSocketGo, status, name, lat, lng)
        emitStatusUpdateOnSocket(mSocketApp, status, name, lat, lng)
    }

    private fun emitStatusUpdateOnSocket(socket: Socket?, status: String, name: String, explicitLat: Double? = null, explicitLng: Double? = null) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        if (socket?.connected() != true) return
        try {
            val json = JSONObject().apply {
                put("driverId", uid)
                put("status", status)
                put("name", name)
                // For "online" signals, include last known GPS so sysu-app can place
                // the GREEN marker with a position immediately — no movement needed.
                // Reads from SharedPreferences which are updated before every driver_moving emit.
                // For "offline" signals, omit lat/lng here — the backend's disconnect handler
                // will emit vendor_status_update with the freshest stored coords from onlineDriver.
                if (status == "online") {
                    val srcLat = explicitLat ?: prefs?.getFloat(KEY_LAST_EMIT_LAT, 0f)?.toDouble()?.takeIf { it != 0.0 }
                    val srcLng = explicitLng ?: prefs?.getFloat(KEY_LAST_EMIT_LNG, 0f)?.toDouble()?.takeIf { it != 0.0 }
                    if (srcLat != null && srcLng != null) {
                        put("lat", srcLat)
                        put("lng", srcLng)
                    }
                }
            }
            socket.emit("update_status", json)
            Log.d(TAG, "Emitted update_status: $status for $name")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to emit update_status: ${e.message}")
        }
    }

    fun emitDriverMoving(lat: Double, lng: Double, sapDocId: String?, status: String = "online") {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return

        val json = JSONObject().apply {
            put("driverId", uid)
            put("sapDocId", sapDocId)
            put("lat", lat)
            put("lng", lng)
            put("status", status)
            put("name", getDriverName())
        }

        emitDriverMovingOnSocket(mSocketGo, json, lat, lng, sapDocId)
        emitDriverMovingOnSocket(mSocketApp, json, lat, lng, sapDocId)
    }

    private fun emitDriverMovingOnSocket(socket: Socket?, json: JSONObject, lat: Double, lng: Double, sapDocId: String?) {
        if (socket?.connected() != true) {
            Log.w(TAG, "Socket not connected, cannot emit driver_moving")
            return
        }
        try {
            socket.emit("driver_moving", json)
            val name = json.optString("name", "?")
            Log.d(TAG, "Emitted driver_moving: lat=$lat, lng=$lng, sapDocId=$sapDocId, name=$name")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to emit driver_moving: ${e.message}")
        }
    }

    fun emitDriverStopped(lat: Double, lng: Double, sapDocId: String?) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val name = getDriverName()

        val json = JSONObject().apply {
            put("driverId", uid)
            put("sapDocId", sapDocId)
            put("lat", lat)
            put("lng", lng)
            put("status", "stopped")
            put("name", name)
            put("stoppedAt", System.currentTimeMillis())
        }

        emitDriverStoppedOnSocket(mSocketGo, json)
        emitDriverStoppedOnSocket(mSocketApp, json)
        // Also emit update_status:stopped so sysu-app (which listens for update_status
        // for online/offline) gets a consistent machine-readable status field as well.
        emitStatusUpdateOnSocket(mSocketGo, "stopped", name, lat, lng)
        emitStatusUpdateOnSocket(mSocketApp, "stopped", name, lat, lng)
    }

    private fun emitDriverStoppedOnSocket(socket: Socket?, json: JSONObject) {
        if (socket?.connected() != true) {
            Log.w(TAG, "Socket not connected, cannot emit driver_stopped")
            return
        }
        try {
            socket.emit("driver_stopped", json)
            val name = json.optString("name", "?")
            Log.d(TAG, "Emitted driver_stopped: lat=${json.optDouble("lat")}, lng=${json.optDouble("lng")}, name=$name")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to emit driver_stopped: ${e.message}")
        }
    }

    fun emitOfflineStatus(driverId: String?, driverName: String) {
        if (driverId == null) return
        val uid = driverId
        val json = JSONObject().apply {
            put("driverId", uid)
            put("status", "offline")
            put("name", driverName)
        }
        listOf(mSocketGo, mSocketApp).forEach { socket ->
            if (socket?.connected() == true) {
                try {
                    socket.emit("update_status", json)
                    Log.d(TAG, "Emitted offline_status: $driverName")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to emit offline_status: ${e.message}")
                }
            }
        }
    }

    /**
     * Emits pod_updated to the sysu-go backend after a POD photo is successfully saved.
     * The backend should emit 'pod_updated' on the driver's socket (room) so the app's
     * pod_updated listener fires → bottom sheet refreshes automatically.
     *
     * Payload: { shipmentDocId: string }
     * Backend: on 'pod_updated' from driver → emit to driver room with updated lineItems
     */
    fun emitPodUpdated(shipmentDocId: String) {
        if (mSocketGo?.connected() != true) {
            Log.w(TAG, "emitPodUpdated: sysu-go socket not connected, skipping emit")
            return
        }
        try {
            val json = JSONObject().apply {
                put("shipmentDocId", shipmentDocId)
                put("source", "driver_app")
            }
            mSocketGo?.emit("pod_updated", json)
            Log.d(TAG, "Emitted pod_updated for shipment=$shipmentDocId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to emit pod_updated: ${e.message}")
        }
    }

    /**
     * Emits a generic event to the sysu-go backend socket (mSocketGo).
     * Used for request/response patterns where the server replies on a given event.
     * Example: emit("get_driver_assignments", { driverId }) → server emits "driver_assignments" response.
     */
    fun emitGo(event: String, json: JSONObject) {
        if (mSocketGo?.connected() != true) {
            Log.w(TAG, "emitGo($event): go-socket not connected, skipping emit")
            return
        }
        try {
            mSocketGo?.emit(event, json)
            Log.d(TAG, "Emitted $event: $json")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to emit $event: ${e.message}")
        }
    }

    fun disconnect() {
        // Remove this SocketManager as a listener from ConnectivityMonitor
        ConnectivityMonitor.removeListener(this)
        Log.d(TAG, "SocketManager unregistered from ConnectivityMonitor listener.")

        listOf(mSocketGo, mSocketApp).forEach { socket ->
            socket?.let {
                // Emit offline status before disconnecting for graceful shutdown (e.g., user logout)
                emitStatusUpdateOnSocket(it, "offline", getDriverName())
                it.disconnect()
            }
        }
        mSocketGo = null
        mSocketApp = null
        offlineEmitted = false
        listenersNotified = false
        registeredEvents.clear()
    }

    // Force disconnect sockets without emitting "offline" status when network is lost.
    private fun forceDisconnectSockets() {
        Log.w(TAG, "Forcing Socket.IO disconnect due to network loss.")
        listOf(mSocketGo, mSocketApp).forEach { socket ->
            socket?.let {
                if (it.connected()) {
                    it.disconnect()
                    Log.d(TAG, "Forced disconnect for socket: ${it.id()}")
                }
            }
        }
        mSocketGo = null
        mSocketApp = null
        offlineEmitted = false
        listenersNotified = false
        registeredEvents.clear()
    }

    override fun onConnectivityChanged(status: ConnectivityMonitor.Status) {
        when (status) {
            ConnectivityMonitor.Status.OFFLINE -> {
                Log.w(TAG, ">>> NETWORK OFFLINE event received")
                Log.w(TAG, ">>> Current socket state: mSocketGo=${mSocketGo?.connected()}, mSocketApp=${mSocketApp?.connected()}")
                Log.w(TAG, ">>> Will force-disconnect and wait for ONELINE event")
                forceDisconnectSockets()
            }
            else -> { // Any online status
                Log.i(TAG, ">>> NETWORK ONLINE (${status.name}) event received — initiating aggressive reconnect")
                Log.i(TAG, ">>> Current socket state before reconnect: mSocketGo=${mSocketGo?.connected()}, mSocketApp=${mSocketApp?.connected()}")
                Log.i(TAG, ">>> offlineEmitted flag: $offlineEmitted")
                Log.i(TAG, ">>> Calling connect()...")
                offlineEmitted = false
                connect()
                Log.i(TAG, ">>> connect() returned. mSocketGo=${mSocketGo?.connected()}, mSocketApp=${mSocketApp?.connected()}")
            }
        }
    }

    fun isConnected(): Boolean = mSocketGo?.connected() == true || mSocketApp?.connected() == true
}