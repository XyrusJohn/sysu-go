const { getDb } = require("../config/firebaseAdmin.js");
const { FieldValue } = require("firebase-admin/firestore");

const SANITIZE = (str, max = 50) => {
  if (!str) return "";
  return str
    .replace(/[<>$&""|;'\\\/]/g, "")
    .replace(/\s+/g, " ")
    .trim()
    .substring(0, max);
};

const SANITIZE_MI = (str) => {
  if (!str) return "";
  return str.charAt(0).toUpperCase().replace(/[^A-Z]/g, "");
};

const SANITIZE_PLATE_PREFIX = (str) => {
  if (!str) return "";
  return str.toUpperCase().replace(/[^A-Z0-9-]/g, "").replace(/-+/g, "-").trim("-").substring(0, 4);
};

const SANITIZE_PLATE_SUFFIX = (str) => {
  if (!str) return "";
  return str.replace(/\D/g, "").substring(0, 4);
};

let db;
const getDatabase = () => {
  if (!db) db = getDb();
  return db;
};

// Generate a short alphanumeric token (e.g., SYS-A1B2C3)
const generateToken = () => {
  const chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
  let token = "SYS-";
  for (let i = 0; i < 6; i++) {
    token += chars.charAt(Math.floor(Math.random() * chars.length));
  }
  return token;
};

// POST /api/qr/generate — TV screen calls this to get a fresh QR token
const generateQR = async (req, res) => {
  try {
    const token = generateToken();
    const expiresAt = new Date(Date.now() + 5 * 60 * 1000);
    const tokenRef = getDatabase().collection("qr_tokens").doc(token);
    await tokenRef.set({
      token,
      expiresAt: expiresAt.toISOString(),
      isUsed: false,
      createdAt: new Date().toISOString(),
    });

    return res.status(200).json({
      success: true,
      token,
      expiresAt: expiresAt.toISOString(),
      scanUrl: `${process.env.FRONTEND_URL}/scan?token=${token}`,
    });
  } catch (error) {
    console.error("QR Generate Error:", error);
    return res.status(500).json({ success: false, message: error.message });
  }
};

// GET /api/qr/verify — App checks if token is valid before scanning.
// Does NOT mark token as used; that is the sole responsibility of scanQR.
const verifyQR = async (req, res) => {
  try {
    const { token } = req.query;
    if (!token) {
      return res.status(400).json({ success: false, message: "Token is required." });
    }

    const tokenDoc = await getDatabase().collection("qr_tokens").doc(token).get();

    if (!tokenDoc.exists) {
      return res.status(404).json({ success: false, message: "Invalid or expired token." });
    }

    if (tokenDoc.data().isUsed) {
      return res.status(410).json({ success: false, message: "Token already used." });
    }

    const expiresAt = new Date(tokenDoc.data().expiresAt);
    if (new Date() > expiresAt) {
      return res.status(410).json({ success: false, message: "Token has expired." });
    }

    return res.status(200).json({ success: true, message: "Token verified", token });
  } catch (error) {
    console.error("QR Verify Error:", error);
    return res.status(500).json({ success: false, message: "Internal server error" });
  }
};

// POST /api/qr/scan — App submits driver info after scanning.
// Marks token as used and creates a queue entry.
// Auth is handled by the route-level verifyToken middleware in auth.js.
// NOTE: The Firebase ID token is verified at the route layer (qrRoutes.js).
// req.user.uid is available for audit logging.
const scanQR = async (req, res) => {
  try {
    const { token, driverName, helperName, truckCompany, truckType, plateNumber, driverId,
        driverFN, driverMI, driverLN, driverPhone } = req.body;

    if (!token || !driverId) {
      return res.status(400).json({ success: false, message: "Missing token or driverId" });
    }

    if (!driverName || !truckType || !plateNumber) {
      return res.status(400).json({ success: false, message: "Missing driverName, truckType, or plateNumber" });
    }

    const database = getDatabase();

    // Validate token exists and is not expired or already used
    const tokenDoc = await database.collection("qr_tokens").doc(token).get();
    if (!tokenDoc.exists) {
      return res.status(404).json({ success: false, message: "Invalid or expired token." });
    }
    if (tokenDoc.data().isUsed) {
      return res.status(410).json({ success: false, message: "Token already used." });
    }

    const expiresAt = new Date(tokenDoc.data().expiresAt);
    if (new Date() > expiresAt) {
      return res.status(410).json({ success: false, message: "Token has expired." });
    }

    // Mark token as USED — this is the single authoritative mark.
    // verifyQR only reads; scanQR is the sole writer of isUsed.
    await database.collection("qr_tokens").doc(token).update({ isUsed: true });

    // Sanitize all string inputs
    const sDriverFN   = SANITIZE(driverFN, 50);
    const sDriverMI   = SANITIZE_MI(driverMI);
    const sDriverLN   = SANITIZE(driverLN, 50);
    const sDriverName = SANITIZE(driverName, 100);
    const sHelperName = SANITIZE(helperName, 100);
    const sTruckComp  = SANITIZE(truckCompany, 100);
    const sTruckType  = truckType ? truckType.toUpperCase().trim() : "";
    const sPlateNum   = SANITIZE(plateNumber, 20);
    const sDriverPhone = SANITIZE(driverPhone, 20);

    // Add driver to queue.
    // Status "waiting" is the initial state; "called" is assigned by dispatch.
    const queueRef = database.collection("queue").doc();
    const queueEntry = {
      id: queueRef.id,
      driverId,
      driverName: sDriverName,
      driverFN: sDriverFN || null,
      driverMI: sDriverMI || null,
      driverLN: sDriverLN || null,
      driverPhone: sDriverPhone || null,
      token,  // stored so registerTrip can emit qr_token_used for TV refresh
      helper: sHelperName || null,
      truckCompany: sTruckComp || null,
      truckType: sTruckType,
      plateNumber: sPlateNum,
      entryTime: FieldValue.serverTimestamp(),
      joinedAt: FieldValue.serverTimestamp(),
      registrationComplete: false,
      isAssigned: false,
      status: "waiting",
      notes: "",
    };
    await queueRef.set(queueEntry);

    console.log(`[scanQR] Driver ${driverId} joined queue (entry ${queueRef.id})`);

    // Emit qr_token_used so TV display auto-refreshes its QR
    const io = req.app.get("io");
    if (io) io.emit("qr_token_used", { token });

    // Broadcast updated queue via Socket.IO
    if (io) {
      try {
        const queueSnap = await database
          .collection("queue")
          .where("status", "in", ["waiting", "called"])
          .orderBy("joinedAt", "asc")
          .get();

        const allQueued = [];
        queueSnap.forEach((doc) => allQueued.push({ id: doc.id, ...doc.data() }));
        io.emit("queue_update", { queue: allQueued });
      } catch (e) {
        console.warn("[scanQR] Socket broadcast query failed (likely missing index):", e.message);
      }
    }

    // Compute queue position within truck type group
    let position = 0;
    try {
      const posSnap = await database
        .collection("queue")
        .where("truckType", "==", truckType)
        .where("status", "==", "waiting")
        .orderBy("joinedAt", "asc")
        .get();

      const group = [];
      posSnap.forEach((doc) => group.push({ id: doc.id, ...doc.data() }));

      group.forEach((doc, idx) => {
        if (doc.id === queueRef.id) position = idx + 1;
      });
    } catch (e) {
      console.warn("[scanQR] Position query failed (likely missing index):", e.message);
    }

    return res.status(200).json({
      success: true,
      message: "Successfully joined the queue",
      queueId: queueRef.id,
      queuePosition: position || null,
    });
  } catch (error) {
    console.error("QR Scan Error:", error);
    return res.status(500).json({ success: false, message: "Internal server error" });
  }
};

// GET /api/qr/queue — Returns current queue grouped by truck type.
// Only shows entries where registrationComplete: true (the Fleet Manager gate).
// Use ?all=1 to include incomplete entries (for dispatch/debugging only).
const getQueue = async (req, res) => {
  try {
    const database = getDatabase();
    const showAll = req.query.all === "1";

    // Default: only return entries where the driver completed Phase 2 registration.
    // Incomplete entries (registrationComplete: false) are hidden from Fleet Manager.
    let queueSnap;
    if (showAll) {
      queueSnap = await database
        .collection("queue")
        .where("status", "in", ["waiting", "called"])
        .orderBy("joinedAt", "asc")
        .get();
    } else {
      queueSnap = await database
        .collection("queue")
        .where("status", "in", ["waiting", "called"])
        .where("registrationComplete", "==", true)
        .orderBy("joinedAt", "asc")
        .get();
    }

    const truckTypes = ["AUV", "4W", "6W", "10W", "20F", "40F"];
    const grouped = {};
    truckTypes.forEach((t) => (grouped[t] = []));

    queueSnap.forEach((doc) => {
      const entry = { id: doc.id, ...doc.data() };
      if (grouped[entry.truckType]) {
        grouped[entry.truckType].push(entry);
      }
    });

    return res.status(200).json({ success: true, queue: grouped, incompleteCount: showAll ? 0 : undefined });
  } catch (error) {
    console.error("Queue Fetch Error:", error);
    return res.status(500).json({ success: false, message: "Internal server error" });
  }
};

// PATCH /api/qr/queue-status — Admin updates queue entry status
const updateQueueStatus = async (req, res) => {
  try {
    const { queueId, status } = req.body;
    if (!queueId || !status) {
      return res.status(400).json({ success: false, message: "queueId and status are required." });
    }

    // Support all status values used across mobile, web, and admin flows
    const validStatuses = ["waiting", "called", "loading", "in_transit", "complete", "departed", "cancelled"];
    if (!validStatuses.includes(status)) {
      return res.status(400).json({
        success: false,
        message: `Invalid status. Must be one of: ${validStatuses.join(", ")}`,
      });
    }

    const database = getDatabase();
    const queueRef = database.collection("queue").doc(queueId);
    const doc = await queueRef.get();
    if (!doc.exists) {
      return res.status(404).json({ success: false, message: "Queue entry not found." });
    }

    await queueRef.update({
      status,
      updatedAt: FieldValue.serverTimestamp(),
    });

    console.log(`[updateQueueStatus] ${queueId} → ${status}`);

    // Broadcast updated queue via Socket.IO
    const io = req.app.get("io");
    if (io) {
      const queueSnap = await database
        .collection("queue")
        .where("status", "in", ["waiting", "called"])
        .orderBy("joinedAt", "asc")
        .get();

      const allQueued = [];
      queueSnap.forEach((d) => allQueued.push({ id: d.id, ...d.data() }));
      io.emit("queue_update", { queue: allQueued });
    }

    return res.status(200).json({ success: true, message: "Status updated.", status });
  } catch (error) {
    console.error("Queue Status Update Error:", error);
    return res.status(500).json({ success: false, message: "Internal server error" });
  }
};

module.exports = { generateQR, verifyQR, scanQR, getQueue, updateQueueStatus };