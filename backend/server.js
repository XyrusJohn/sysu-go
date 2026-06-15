require("dotenv").config();

const express = require("express");
const cors = require("cors");
const compression = require("compression");
const http = require("http");
const { Server } = require("socket.io");
const { getDb } = require("./config/firebaseAdmin.js");
const { startHeartbeat } = require("./utils/heartbeatMonitor");

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

// ─── Diagnostic endpoint — test Firestore connectivity directly ─────────────
app.get("/api/firestore-test", async (req, res) => {
  try {
    const db = getDb();
    const snap = await db.collection("qr_tokens").limit(1).get();
    return res.status(200).json({ success: true, firestore: "connected", docs: snap.size });
  } catch (err) {
    console.error("[firestore-test] Error:", err.message);
    return res.status(500).json({ success: false, firestore: "error", message: err.message });
  }
});

app.use((err, req, res, next) => {
  console.error("[SERVER ERROR]", err.stack);
  res.status(500).json({ success: false, message: "Internal Server Error" });
});

app.use("/api", (req, res) => {
  res.status(404).json({ success: false, message: `API Route ${req.method} ${req.originalUrl} not found` });
});

// ─── Socket.IO handshake logic ──────────────────────────────────────────────
// onlineDriver tracks ALL live sockets per driver using a Set.
// Driver is OFFLINE only when Set is EMPTY. This handles dual-socket connections
// (Railway + sysu-app) correctly — Ctrl+Shift+R / reconnect / WiFi-kill on one
// socket must NOT mark driver offline if another socket is still alive.
const onlineDriver = new Map();
const { getFirebaseAuth } = require("./config/firebaseAdmin.js");

// Helper: verify Firebase ID token from Socket.IO handshake and return decoded UID
async function verifySocketToken(socket) {
  const token = socket.handshake.auth?.token;
  if (!token) return null;
  try {
    const auth = getFirebaseAuth();
    const decoded = await auth.verifyIdToken(token);
    return decoded.uid;
  } catch (err) {
    console.warn(`[${ts()}] [Socket] Token verify failed for ${socket.id}:`, err.message);
    return null;
  }
}

io.on("connection", async (socket) => {
  console.log(`[${ts()}] System Connected: ${socket.id}`);

  // Verify Firebase token and associate socket with verified UID
  const verifiedUid = await verifySocketToken(socket);
  if (verifiedUid) {
    socket.data.uid = verifiedUid;
    console.log(`[${ts()}] [Socket] Authenticated: socket ${socket.id} → uid ${verifiedUid}`);

    // On connect: if this driver was already in the map (page reload / reconnect),
    // emit their current state to this socket so the marker reappears WITHOUT needing
    // them to move first. This fixes the regression introduced by the multi-socket fix.
    const existingEntry = onlineDriver.get(verifiedUid);
    if (existingEntry) {
      // Ensure this new socket is added to the driver's set
      existingEntry.socketIds.add(socket.id);
      socket.emit("vendor_status_update", {
        driverId: verifiedUid,
        status: "online",
        name: existingEntry.name,
        lat: existingEntry.lat,
        lng: existingEntry.lng,
      });
      // Also emit update_admin_map with latest known position
      socket.emit("update_admin_map", {
        driverId: verifiedUid,
        lat: existingEntry.lat,
        lng: existingEntry.lng,
        name: existingEntry.name,
        sapDocId: null,
      });
      console.log(`[${ts()}] [Socket] Emitted current driver state to reconnecting socket ${socket.id}`);
    }

    // Also emit full state of ALL OTHER active drivers to this new socket,
    // so sysu-app rebuilds the full map on page reload (not just this driver).
    for (const [otherId, entry] of onlineDriver.entries()) {
      if (otherId === verifiedUid) continue; // skip self (handled above)
      // Only emit if this driver's socket set actually contains a lived socket
      const hasLiveSocket = Array.from(entry.socketIds).some(sid => io.sockets.sockets.get(sid));
      if (hasLiveSocket) {
        socket.emit("vendor_status_update", {
          driverId: otherId,
          status: "online",
          name: entry.name,
          lat: entry.lat,
          lng: entry.lng,
        });
        if (entry.lat != null && entry.lng != null) {
          socket.emit("update_admin_map", {
            driverId: otherId,
            lat: entry.lat,
            lng: entry.lng,
            name: entry.name,
            sapDocId: null,
          });
        }
      }
    }
  } else {
    console.warn(`[${ts()}] [Socket] Unauthenticated connection from ${socket.id} — events will be ignored`);
  }

  socket.on("update_status", async (data) => {
    // Reject if not authenticated or if driverId doesn't match verified UID
    if (!verifiedUid || data.driverId !== verifiedUid) {
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
      // Add this socket to the driver's set of live sockets. Use Set so both
      // Railway and sysu-app sockets are tracked independently. Driver stays ONLINE
      // as long as ANY socket remains. When the LAST socket disconnects, OFFLINE fires.
      const entry = onlineDriver.get(verifiedUid);
      if (entry) {
        entry.socketIds.add(socket.id);
        entry.name = fullName;
        if (lat != null) entry.lat = lat;
        if (lng != null) entry.lng = lng;
        console.log(`[${ts()}] Driver ${fullName} socket added (${entry.socketIds.size} active sockets)`);
      } else {
        onlineDriver.set(verifiedUid, { name: fullName, lat: lat ?? null, lng: lng ?? null, socketIds: new Set([socket.id]) });
        console.log(`[${ts()}] Driver ${fullName} is now ONLINE`);
        // First online → emit so sysu-app can place GREEN marker immediately
        io.emit("vendor_status_update", { driverId: verifiedUid, status: "online", name: fullName, lat: lat ?? null, lng: lng ?? null });
      }
    } else {
      // Driver went offline (emitOfflineStatus path — e.g. "End Trip" button).
      // Remove ALL sockets for this driver — explicit session end, not a reconnect blip.
      const entry = onlineDriver.get(verifiedUid);
      const lat = entry ? entry.lat : null;
      const lng = entry ? entry.lng : null;
      onlineDriver.delete(verifiedUid);
      io.emit("vendor_status_update", { driverId: verifiedUid, status: "offline", name: fullName, lat, lng });
      console.log(`[${ts()}] Driver ${fullName} is now OFFLINE [explicit]`);
    }
  });

  socket.on("disconnect", (reason) => {
    console.log(`[${ts()}] System Disconnected: ${socket.id} (Reason: ${reason})`);

    for (const [id, entry] of onlineDriver.entries()) {
      if (!entry.socketIds.has(socket.id)) continue;
      entry.socketIds.delete(socket.id);
      if (entry.socketIds.size === 0) {
        // No more sockets for this driver — officially OFFLINE
        onlineDriver.delete(id);
        io.emit("vendor_status_update", { driverId: id, name: entry.name, status: "offline", lat: entry.lat, lng: entry.lng });
        console.log(`[${ts()}] Driver ${entry.name} is now OFFLINE`);
      } else {
        // Other socket(s) still alive — driver stays online, just lost one socket
        console.log(`[${ts()}] Driver ${entry.name} still online via ${entry.socketIds.size} other socket(s)`);
      }
      break;
    }
  });

  socket.on("driver_moving", async (data) => {
    const { driverId, lat, lng, sapDocId } = data;

    // Reject if not authenticated or if driverId doesn't match verified UID
    if (!verifiedUid || driverId !== verifiedUid) {
      console.warn(`[${ts()}] [Socket] driver_moving rejected: unauthorized (socket ${socket.id})`);
      return;
    }
    if (!driverId) return;

    let fullName = "Unknown Driver";
    const entry = onlineDriver.get(verifiedUid);
    if (entry) {
      // Ensure this socket is tracked (may have reconnected before disconnect fired)
      entry.socketIds.add(socket.id);
      entry.lat = lat;
      entry.lng = lng;
      fullName = entry.name;
    } else {
      try {
        const dbInfo = await Driver.findDriverById(verifiedUid);
        if (dbInfo) fullName = `${dbInfo.first_name} ${dbInfo.last_name}`.trim();
        // Create a new entry for first driver_moving before any update_status online
        onlineDriver.set(verifiedUid, { name: fullName, lat, lng, socketIds: new Set([socket.id]) });
      } catch (e) {
        console.log(`[${ts()}] Database Error in Socket:`, e.message);
      }
    }

    console.log(`[${ts()}] Live Driver ${fullName} moved to [${lat}, ${lng}]`);
    io.emit("update_admin_map", { driverId: verifiedUid, lat, lng, sapDocId, name: fullName });
  });
});

// ─── Start server ───────────────────────────────────────────────────────────
const RAILWAY_PORT = parseInt(process.env.PORT || "8080", 10);

// Destroy sockets idle for > 30s (avoids holding connections indefinitely)
server.on("timeout", (socket) => {
  socket.destroy();
  console.warn(`[${ts()}] [SERVER] Idle socket destroyed: ${socket.remoteAddress}`);
});

server.listen(RAILWAY_PORT, "0.0.0.0", () => {
  console.log(`[${ts()}] [ENV] PORT=${process.env.PORT}`);
  console.log(`[${ts()}] 🚀 Server + Socket.IO running on port ${RAILWAY_PORT}`);
  // Start internet connectivity heartbeat monitor
  startHeartbeat();
});