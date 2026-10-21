require("dotenv").config();

const express = require("express");
const cors = require("cors");
const compression = require("compression");
const http = require("http");
const { Server } = require("socket.io");
const { getDb } = require("./config/firebaseAdmin.js");
const { startHeartbeat } = require("./utils/heartbeatMonitor");
const { startPresenceWatcher } = require("./utils/presenceWatcher");

// ─── Log helper with timestamp ────────────────────────────────────────────────
const ts = () => new Date().toISOString().replace("T", " ").substring(0, 19);

// ─── CORS origins ────────────────────────────────────────────────────────────
const FRONTEND_ORIGIN = process.env.FRONTEND_URL || "https://sysu-web-5a25d.web.app";
const rawOrigins = process.env.ALLOWED_ORIGINS || FRONTEND_ORIGIN;
const allowedOrigins = rawOrigins.split(",").map((s) => s.trim()).filter(Boolean);
const isAllowedOrigin = (origin, cb) => {
  if (!origin) return cb(null, true);           // mobile apps / Socket.IO have no origin
  if (allowedOrigins.includes(origin)) return cb(null, true);
  if (process.env.NODE_ENV !== "production") return cb(null, true); // dev: allow all
  cb(new Error(`CORS: origin ${origin} not allowed`));
};

const app = express();
const server = http.createServer(app);

// ─── CORS ─────────────────────────────────────────────────────────────────
app.use(cors({
  origin: isAllowedOrigin,
  methods: ["GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"],
  credentials: true,
}));

app.use(compression());

app.use(express.json({ limit: "10mb" }));

// ─── Routes ─────────────────────────────────────────────────────────────────
const qrRoutes = require("./routes/qrRoutes.js");
const sapRoutes = require("./routes/sapRoutes.js");
const Driver = require("./models/driverModel.js");

// ─── Socket.IO ─────────────────────────────────────────────────────────────
const io = new Server(server, {
  cors: {
    origin: isAllowedOrigin,
    methods: ["GET", "POST"],
    credentials: true,
  },
  pingTimeout: 30000,
  pingInterval: 10000,
});
app.set("io", io);

// ─── Health routes ──────────────────────────────────────────────────────────
app.get("/", (req, res) => {
  res.status(200).json({ success: true, message: "SYSU Go API is running." });
});

app.get("/api/health", (req, res) => res.status(200).json({ status: "ok" }));

// ─── API routes ─────────────────────────────────────────────────────────────
app.use("/api/qr", qrRoutes);
app.use("/api/sap", sapRoutes);
app.use("/api/users", require("./routes/userRoutes.js"));
app.use("/api/dispatch", require("./routes/dispatchRoutes.js"));
app.use("/api/trip", require("./routes/tripRoute.js"));
app.use("/api/driver", require("./routes/driverStatusRoutes.js"));
app.use("/api/pod", require("./routes/podRoutes.js"));

// ─── Diagnostic endpoint — test Firestore connectivity directly ─────────────
// Production diagnostic route intentionally disabled.

app.use((err, req, res, next) => {
  const status = err.type === "entity.parse.failed" ? 400 : err.type === "entity.too.large" ? 413 : 500;
  console.error("[SERVER ERROR]", status);
  res.status(status).json({ success: false, message: status === 400 ? "Invalid request body" : status === 413 ? "Request too large" : "Internal Server Error" });
});

app.use("/api", (req, res) => {
  res.status(404).json({ success: false, message: `API Route ${req.method} ${req.originalUrl} not found` });
});

// ─── API routes ─────────────────────────────────────────────────────────────


// ─── Socket.IO handshake logic ──────────────────────────────────────────────
// onlineDriver tracks ALL live sockets per driver using a Set.
// Driver is OFFLINE only when Set is EMPTY. This handles dual-socket connections
// (Railway + sysu-app) correctly — Ctrl+Shift+R / reconnect / WiFi-kill on one
// socket must NOT mark driver offline if another socket is still alive.
//
// `lastSeen` is the timestamp of the most recent client→server emit (heartbeat,
// driver_moving, update_status, driver_status_change). The presence watcher
// (utils/presenceWatcher.js) uses this to detect drivers whose network has died
// silently — Socket.IO doesn't always fire EVENT_DISCONNECT fast enough on
// DNS-broken / captive-portal scenarios, so a heartbeat timeout is the
// authoritative "this driver is gone" signal.
const onlineDriver = new Map();
const locationSamples = new Map();
const closedTracking = new Map();
const activeLocationStatuses = new Set(['assigned', 'loading', 'loaded', 'fully_loaded', 'in_transit', 'returning_to_base']);
function locationPayload(uid, entry) {
  return {driverId: uid, lat: entry.lat, lng: entry.lng, name: entry.name,
    sapDocId: entry.sapDocId || null, status: entry.isOffline ? 'offline' : entry.status,
    timestamp: entry.locationTimestamp || 0};
}

const { getFirebaseAuth } = require("./config/firebaseAdmin.js");
const { createSocketAuth } = require("./middleware/socketAuth.js");
const TRACKING_ROLES = new Set(["fleet_manager", "admin", "super_admin"]);
io.use(createSocketAuth({ getFirebaseAuth, getDb }));

/**
 * Touch the driver's lastSeen timestamp to "now". Called from every client
 * event handler so an idle driver (no GPS movement) still keeps their
 * online flag alive as long as they're sending heartbeats.
 */
function touchLastSeen(uid) {
  const entry = onlineDriver.get(uid);
  if (entry) entry.lastSeen = Date.now();
}

/**
 * Centralised offline-protection gate.
 *
 * Returns true if the driver has at least one sap_assignment that is NOT
 * yet completed — i.e. they still have active work (assigned / loading /
 * loaded / fully_loaded / in_transit).
 *
 * This is the AUTHORITATIVE source of truth for "should we retain this
 * driver's last-known position on the admin map when they go offline?"
 * Using Firestore (not socket-derived state) guarantees correctness across:
 *   - Server restarts (in-memory Map is wiped)
 *   - Driver force-kills (socket state was never fully hydrated)
 *   - Race conditions between disconnect and status events
 *
 * Cost: 1 Firestore read per offline event, limit(1) → minimal quota impact.
 *
 * @param {string} uid  Firebase UID of the driver
 * @returns {Promise<boolean>}
 */
async function hasActiveShipment(uid) {
  try {
    const db = getDb();
    const snap = await db
      .collection("sap_assignments")
      .where("driverId", "==", uid)
      .where("tripStatus", "not-in", ["completed"])
      .limit(1)
      .get();
    return !snap.empty;
  } catch (err) {
    console.error(`[${ts()}] [hasActiveShipment] Firestore error for ${uid}:`, err.message);
    // Fail-safe: if Firestore is unreachable, assume active — better to show
    // a stale marker than to silently drop a driver who might be in danger.
    return true;
  }
}

/**
 * Valid driver lifecycle statuses broadcast via driver_status_change.
 * Anything outside this set is rejected by the server.
 */
const DRIVER_LIFECYCLE_STATUSES = new Set([
  "online",
  "offline",
  "in_transit",
  "returning_to_base",
]);

io.on("connection", async (socket) => {
  require("./services/realtimeSubscriptions").bindRealtimeSubscriptions(socket, getDb());
  const verifiedUid = socket.data.uid;
  const role = socket.data.role;
  console.log("[" + ts() + "] Authenticated socket connected: " + socket.id + " (" + role + ")");

  if (role === "driver") {
    socket.join("driver:" + verifiedUid);
    const entry = onlineDriver.get(verifiedUid);
    if (entry) { entry.socketIds.add(socket.id); entry.lastSeen = Date.now(); }
  } else if (TRACKING_ROLES.has(role)) {
    socket.join("staff:tracking");
    for (const [otherId, entry] of onlineDriver.entries()) {
      const hasLiveSocket = Array.from(entry.socketIds).some(sid => io.sockets.sockets.get(sid));
      if (!hasLiveSocket || entry.status === "completed" || entry.lat == null || entry.lng == null || entry.lat === 0 || entry.lng === 0) continue;
      if (!(await hasActiveShipment(otherId))) continue;
      const payload = locationPayload(otherId, entry);
      socket.emit("vendor_status_update", payload);
      socket.emit("update_admin_map", payload);
    }
  }

  socket.on("update_status", async (data) => {
    try {
      if (!data) data = {};
      const targetDriverId = data.driverId || verifiedUid;
      if (!verifiedUid || (data.driverId && data.driverId !== verifiedUid)) {
        console.warn(`[${ts()}] [Socket] update_status rejected: unauthorized (socket ${socket.id})`);
        return;
      }

      const { status, lat, lng } = data;

      let fullName = "Unknown Driver";
      try {
        const driverInfo = await Driver.findDriverById(verifiedUid);
        if (driverInfo) fullName = `${driverInfo.first_name} ${driverInfo.last_name}`.trim();
      } catch (err) {
        console.error(`[${ts()}] DB Error in Socket:`, err);
      }

      if (status === "online") {
        const hasActive = await hasActiveShipment(verifiedUid);
        const validCoords = (typeof lat === "number" && typeof lng === "number" && lat !== 0 && lng !== 0);
        const entry = onlineDriver.get(verifiedUid);
        if (entry) {
          entry.socketIds.add(socket.id);
          entry.name = fullName;
          if (!['stopped', 'returning_to_base'].includes(entry.status)) entry.status = "online";
          entry.isOffline = false;
          entry.offlineSince = null;
          entry.lastSeen = Date.now();
          if (validCoords && entry.locationTimestamp == null) {
            entry.lat = lat;
            entry.lng = lng;
            entry.locationTimestamp = 0;
          }
          if (hasActive && validCoords) {
            io.to("staff:tracking").emit("vendor_status_update", {...locationPayload(verifiedUid, entry), timestamp: Date.now(), locationTimestamp: entry.locationTimestamp});
          }
          console.log(`[${ts()}] Driver ${fullName} back ONLINE (${entry.socketIds.size} active sockets, hasActive=${hasActive})`);
        } else {
          onlineDriver.set(verifiedUid, {
            name: fullName,
            lat: validCoords ? lat : null,
            lng: validCoords ? lng : null,
            socketIds: new Set([socket.id]),
            status: "online",
            isOffline: false,
            tripStatus: null,
            locationTimestamp: 0,
            lastSeen: Date.now(),
          });
          console.log(`[${ts()}] Driver ${fullName} is now ONLINE (hasActive=${hasActive})`);
          if (hasActive && validCoords) {
            io.to("staff:tracking").emit("vendor_status_update", {driverId: verifiedUid, status: 'online', name: fullName,
              lat, lng, timestamp: Date.now(), locationTimestamp: 0});
          }
        }
      } else if (status === "completed") {
        closedTracking.set(verifiedUid, Date.now());
        locationSamples.delete(verifiedUid);
        if (closedTracking.size > 1000) closedTracking.delete(closedTracking.keys().next().value);
        onlineDriver.delete(verifiedUid);
        io.to("staff:tracking").emit("update_status", { driverId: verifiedUid, status: "completed", name: fullName, timestamp: Date.now() });
        io.to("staff:tracking").emit("vendor_status_update", { driverId: verifiedUid, status: "completed", name: fullName, lat: null, lng: null, timestamp: Date.now() });
        console.log(`[${ts()}] Driver ${fullName} COMPLETED trip [marker removed from map]`);
      } else if (status === "stopped") {
        const entry = onlineDriver.get(verifiedUid);
        if (!entry) return;
        entry.status = "stopped";
        entry.lastSeen = Date.now();
        io.to("staff:tracking").emit("vendor_status_update", {...locationPayload(verifiedUid, entry), timestamp: Date.now(), locationTimestamp: entry.locationTimestamp});
      } else {
        const entry = onlineDriver.get(verifiedUid);
        const offlineLat = entry ? entry.lat : null;
        const offlineLng = entry ? entry.lng : null;

        const active = await hasActiveShipment(verifiedUid);
        if (active && offlineLat != null && offlineLng != null) {
          if (entry) {
            entry.isOffline = true;
            entry.offlineSince = Date.now();
            entry.socketIds.clear();
          }
          io.to("staff:tracking").emit("update_status", { driverId: verifiedUid, status: "offline_in_transit", name: fullName });
          io.to("staff:tracking").emit("vendor_status_update", {
            driverId: verifiedUid,
            status: "offline_in_transit",
            name: fullName,
            lat: offlineLat,
            lng: offlineLng,
            offlineSince: entry ? entry.offlineSince : Date.now(),
          });
          console.log(`[${ts()}] Driver ${fullName} OFFLINE — has active shipment, retaining last position [${offlineLat}, ${offlineLng}]`);
        } else {
          onlineDriver.delete(verifiedUid);
          io.to("staff:tracking").emit("update_status", { driverId: verifiedUid, status: "offline", name: fullName });
          io.to("staff:tracking").emit("vendor_status_update", { driverId: verifiedUid, status: "offline", name: fullName, lat: offlineLat, lng: offlineLng });
          console.log(`[${ts()}] Driver ${fullName} is now OFFLINE — no active shipment, marker removed`);
        }
      }
    } catch (err) {
      console.error(`[${ts()}] Error in update_status:`, err);
    }
  });

  /**
   * Real-time GPS movement events from mobile app.
   * Auth required: driverId must match verifiedUid from Firebase token.
   */
  socket.on("driver_moving", async (data) => {
    try {
      if (!data || data.driverId !== verifiedUid) return;
      const {lat, lng, sapDocId} = data;
      const receivedAt = Date.now();
      const sampledAt = data.timestamp == null ? receivedAt : data.timestamp;
      if (!Number.isFinite(sampledAt) || sampledAt > receivedAt + 5000 || receivedAt - sampledAt > 15000) return;
      if (data.accuracyMeters != null && (!Number.isFinite(data.accuracyMeters) || data.accuracyMeters < 0 || data.accuracyMeters > 50)) return;
      const prior = locationSamples.get(verifiedUid);
      if (prior && sampledAt < prior.sampledAt) return;
      const sample = {sampledAt};
      locationSamples.set(verifiedUid, sample);
      if (locationSamples.size > 1000) locationSamples.delete(locationSamples.keys().next().value);
      let ownedSapDocId = null;
      let assignmentStatus = null;
      if (typeof sapDocId === 'string' && sapDocId) {
        try {
          const assignment = await getDb().collection('sap_assignments').doc(sapDocId).get();
          const record = assignment.data();
          if (assignment.exists && record.driverId === verifiedUid && activeLocationStatuses.has(record.tripStatus)) {
            ownedSapDocId = sapDocId;
            assignmentStatus = record.tripStatus;
          }
        } catch (_) { /* Unverified shipment links are excluded from tracking. */ }
      }
      if (locationSamples.get(verifiedUid) !== sample || !socket.connected) return;
      if (closedTracking.has(verifiedUid) && (!ownedSapDocId || sampledAt <= closedTracking.get(verifiedUid))) return;
      if (ownedSapDocId) closedTracking.delete(verifiedUid);
      let entry = onlineDriver.get(verifiedUid);
      if (!entry) {
        const driver = await Driver.findDriverById(verifiedUid);
        if (locationSamples.get(verifiedUid) !== sample || !socket.connected) return;
        entry = {name: driver ? `${driver.first_name} ${driver.last_name}`.trim() : 'Driver', socketIds: new Set()};
        onlineDriver.set(verifiedUid, entry);
      }
      const status = assignmentStatus === 'returning_to_base' || (!assignmentStatus && entry.status === 'returning_to_base')
        ? 'returning_to_base' : data.status === 'stopped' ? 'stopped' : 'online';
      Object.assign(entry, {lat, lng, status, sapDocId: ownedSapDocId, tripStatus: assignmentStatus || entry.tripStatus,
        locationTimestamp: sampledAt, lastSeen: receivedAt, isOffline: false, offlineSince: null});
      entry.socketIds.add(socket.id);
      const payload = locationPayload(verifiedUid, entry);
      io.to('staff:tracking').emit('update_admin_map', payload);
      io.to('staff:tracking').emit('vendor_status_update', payload);
    } catch (_) {
      console.error('[Socket] Location update unavailable');
    }
  });

  /**
   * Stationary stopped event from mobile app
   */
  socket.on("driver_stopped", async (data) => {
    if (!data) return;
    const entry = onlineDriver.get(verifiedUid);
    if (!entry) return;
    if (Number.isFinite(data.timestamp) && data.timestamp < (entry.locationTimestamp || 0)) return;
    entry.status = entry.status === 'returning_to_base' ? entry.status : 'stopped';
    touchLastSeen(verifiedUid);
    io.to('staff:tracking').emit('vendor_status_update', {...locationPayload(verifiedUid, entry), timestamp: Date.now(), locationTimestamp: entry.locationTimestamp});
  });

  /**
   * Lifecycle status changes for the driver
   */
  socket.on("driver_status_change", async (data) => {
    try {
      if (!data) data = {};
      const targetDriverId = data.driverId || verifiedUid;
      if (!verifiedUid || (data.driverId && data.driverId !== verifiedUid)) {
        console.warn(`[${ts()}] [Socket] driver_status_change rejected: unauthorized (socket ${socket.id})`);
        return;
      }

      const { status, name, timestamp } = data;
      const VALID_STATUSES = new Set(["online", "offline", "in_transit", "returning_to_base"]);
      if (!status || !VALID_STATUSES.has(status)) {
        console.warn(`[${ts()}] [Socket] driver_status_change rejected: invalid status '${status}'`);
        return;
      }

      const driverRef = getDb().collection("drivers").doc(verifiedUid);
      const canonicalDriver = await driverRef.get();
      if (!canonicalDriver.exists) return;
      const fullName = canonicalDriver.data().name || "Driver";
      const driverName = fullName;

      try {
        const db = getDb();
        await db.collection("drivers").doc(verifiedUid)
          .set({ status }, { merge: true });
      } catch (err) {
        console.error("[Socket] driver_status_change persistence failed");
        return;
      }

      const entry = onlineDriver.get(verifiedUid);
      if (entry) {
        entry.status = status;
        entry.name = fullName;
        if (status === "in_transit") entry.tripStatus = "in_transit";
        else if (status === "online") { entry.tripStatus = null; entry.isOffline = false; }
        else if (status === "returning_to_base") entry.tripStatus = null;
        touchLastSeen(verifiedUid);
      } else if (status !== "offline") {
        onlineDriver.set(verifiedUid, {
          name: fullName,
          lat: null,
          lng: null,
          socketIds: new Set([socket.id]),
          status,
          tripStatus: status === "in_transit" ? "in_transit" : null,
          isOffline: false,
          lastSeen: Date.now(),
        });
      }

      const payload = {
        driverId: verifiedUid,
        status,
        name: driverName,
        timestamp: Date.now(),
      };
      io.to(`driver:${verifiedUid}`).emit("driver_status_change", payload);
      io.to("staff:tracking").emit("driver_status_change", payload);
      console.log(`[${ts()}] [Socket] driver_status_change → ${status} for ${driverName} (${verifiedUid})`);
    } catch (err) {
      console.error(`[${ts()}] Error in driver_status_change:`, err);
    }
  });

  socket.on("request_driver_status", async (data) => {
    try {
      if (!data) data = {};
      if (!verifiedUid || (data.driverId && data.driverId !== verifiedUid)) {
        console.warn(`[${ts()}] [Socket] request_driver_status rejected: unauthorized (socket ${socket.id})`);
        return;
      }
      let currentStatus = "online";
      let fullName = "Driver";
      try {
        const db = getDb();
        const snap = await db.collection("drivers").doc(verifiedUid).get();
        if (snap.exists) {
          const docData = snap.data();
          if (docData && docData.status) currentStatus = docData.status;
          if (docData && docData.name) fullName = docData.name;
        }
      } catch (err) {
        console.error(`[${ts()}] [Socket] request_driver_status Firestore read failed:`, err.message);
      }
      const payload = {
        driverId: verifiedUid,
        status: currentStatus,
        name: fullName,
        timestamp: Date.now(),
      };
      socket.emit("driver_status_change", payload);
      console.log(`[${ts()}] [Socket] request_driver_status → ${currentStatus} for ${fullName}`);
    } catch (err) {
      console.error(`[${ts()}] Error in request_driver_status:`, err);
    }
  });

  socket.on("pod_updated", async (data) => {
    try {
      if (role !== "driver" || typeof data?.shipmentDocId !== "string") return;
      const assignment = await getDb().collection("sap_assignments").doc(data.shipmentDocId).get();
      if (!assignment.exists || assignment.data().driverId !== verifiedUid) return;
      io.to("staff:tracking").emit("pod_updated", { shipmentDocId: data.shipmentDocId, driverId: verifiedUid, source: "driver_app" });
    } catch (_) { /* Do not expose assignment or Firestore details to socket clients. */ }
  });

  socket.on("disconnect", async (reason) => {
    console.log(`[${ts()}] System Disconnected: ${socket.id} (Reason: ${reason})`);

    for (const [id, entry] of onlineDriver.entries()) {
      if (!entry.socketIds.has(socket.id)) continue;
      entry.socketIds.delete(socket.id);

      if (entry.socketIds.size === 0) {
        // No more live sockets for this driver — they are unreachable.
        // Capture values now before any async operation modifies the entry.
        const driverName = entry.name;
        const lastLat = entry.lat;
        const lastLng = entry.lng;

        // AUTHORITATIVE OFFLINE PROTECTION (same gate as explicit offline handler):
        // Query Firestore — does this driver have any unfinished sap_assignment?
        // If yes → retain last known position as grey "last seen" marker on admin map.
        // If no  → remove marker entirely (driver has no active work).
        // Firestore-backed so it's correct across server restarts and force-kills.
        const active = await hasActiveShipment(id);
        if (onlineDriver.get(id) !== entry || entry.socketIds.size > 0) continue;
        if (active && lastLat != null && lastLng != null) {
          entry.isOffline = true;
          entry.offlineSince = Date.now();
          io.to("staff:tracking").emit("vendor_status_update", {
            driverId: id,
            name: driverName,
            status: "offline_in_transit",
            lat: lastLat,
            lng: lastLng,
            offlineSince: entry.offlineSince,
            timestamp: Date.now(),
            locationTimestamp: entry.locationTimestamp || 0,
          });
          console.log(`[${ts()}] Driver ${driverName} OFFLINE — has active shipment, retaining last position [${lastLat}, ${lastLng}]`);
        } else {
          onlineDriver.delete(id);
          io.to("staff:tracking").emit("vendor_status_update", { driverId: id, name: driverName, status: "offline", lat: lastLat, lng: lastLng, timestamp: Date.now(), locationTimestamp: entry.locationTimestamp || 0 });
          console.log(`[${ts()}] Driver ${driverName} is now OFFLINE — no active shipment, marker removed`);
        }
      } else {
        // Other socket(s) still alive — driver stays online, just lost one socket
        console.log(`[${ts()}] Driver ${entry.name} still online via ${entry.socketIds.size} other socket(s)`);
      }
      break;
    }
  });

  /**
   * Heartbeat ping from the client. The client emits this every ~15s while
   * online so an idle driver (no GPS movement, no lifecycle changes) still
   * refreshes their lastSeen timestamp. The presence watcher
   * (utils/presenceWatcher.js) uses lastSeen to detect drivers whose
   * network has died silently — no EVENT_DISCONNECT fired, just silent loss.
   *
   * Auth required: same as other driver events.
   */
  socket.on("heartbeat", () => {
    if (!verifiedUid) return;
    const entry = onlineDriver.get(verifiedUid);
    if (entry) {
      entry.lastSeen = Date.now();
      // No need to broadcast or persist — heartbeats are silent keep-alives.
      // Just log at debug level so we can verify the cadence in prod.
      console.log(`[${ts()}] [Heartbeat] ${entry.name} (${verifiedUid})`);
    }
  });
});

// ─── Start server ───────────────────────────────────────────────────────────
const PORT = parseInt(process.env.PORT || "8080", 10);

// Destroy sockets idle for > 30s (avoids holding connections indefinitely)
server.on("timeout", (socket) => {
  socket.destroy();
  console.warn(`[${ts()}] [SERVER] Idle socket destroyed: ${socket.remoteAddress}`);
});

server.listen(PORT, "0.0.0.0", () => {
  console.log(`[${ts()}] [ENV] PORT=${process.env.PORT}`);
  console.log(`[${ts()}] 🚀 Server + Socket.IO running on port ${PORT}`);
  // Start internet connectivity heartbeat monitor
  startHeartbeat();
  // Start presence watcher — scans onlineDriver Map every 15s and marks
  // drivers OFFLINE if their lastSeen is older than HEARTBEAT_TIMEOUT_MS.
  // This is the authoritative "this driver is offline" detector — fires
  // even when Socket.IO EVENT_DISCONNECT doesn't (DNS-broken / captive
  // portal scenarios where the TCP socket hangs without a FIN).
  startPresenceWatcher(onlineDriver, io);
});
