const express = require("express");
const router = express.Router();
const { verifyToken } = require("../middleware/auth.js");
const { getDb } = require("../config/firebaseAdmin.js");

/**
 * Valid driver lifecycle statuses. Mirrors the 4-state model in
 * app/src/main/java/com/sysu/go/utils/DriverStatus.kt.
 *   "online" | "offline" | "in_transit" | "returning_to_base"
 */
const VALID_STATUSES = new Set([
  "online",
  "offline",
  "in_transit",
  "returning_to_base",
]);

/**
 * POST /api/driver/status — proxy write to Firestore when the app cannot
 * reach Firestore directly (DNS failure, captive portal, etc.).
 *
 * The app calls this endpoint when:
 *   - Its own Firestore write fails (UnknownHostException etc.)
 *   - The Socket.IO emit fails
 *
 * The backend uses Firebase Admin SDK which connects via Google's internal
 * DNS, bypassing the device's broken DNS. The backend then forwards the
 * update to the driver's Socket.IO room (`driver:{uid}`) so other clients
 * (sysu-web, second device) receive the real-time push.
 *
 * Request body: { status: string, name?: string }
 * Auth: Bearer <firebase_id_token>
 *
 * Returns: 200 { success: true, status, source: "backend_proxy" }
 *          400 invalid status / missing auth
 *          500 backend Firestore error
 */
router.post("/status", verifyToken, async (req, res) => {
  const uid = req.user.uid;
  const { status, name } = req.body;

  if (!status || !VALID_STATUSES.has(status)) {
    return res.status(400).json({
      success: false,
      message: `Invalid status. Must be one of: ${[...VALID_STATUSES].join(", ")}`,
    });
  }

  try {
    const db = getDb();
    await db.collection("drivers").doc(uid).set(
      { status, ...(name ? { name } : {}) },
      { merge: true }
    );
    console.log(`[DriverStatusRoutes] proxy write ${uid} → ${status} ${name ? "(" + name + ")" : ""}`);

    // Also emit driver_status_change to the driver's room via Socket.IO so
    // connected sysu-web and other device instances get the real-time push.
    try {
      const io = req.app.get("io");
      if (io) {
        const payload = {
          driverId: uid,
          status,
          name: name || "Driver",
          timestamp: Date.now(),
          source: "backend_proxy",
        };
        io.to(`driver:${uid}`).emit("driver_status_change", payload);
        io.emit("driver_status_change", payload);
        console.log(`[DriverStatusRoutes] fanned out to room driver:${uid} + global`);
      }
    } catch (ioErr) {
      console.warn(`[DriverStatusRoutes] Socket.IO fanout failed: ${ioErr.message}`);
    }

    return res.status(200).json({ success: true, status, source: "backend_proxy" });
  } catch (err) {
    console.error(`[DriverStatusRoutes] proxy write failed for ${uid}:`, err.message);
    return res.status(500).json({ success: false, message: err.message });
  }
});

/**
 * GET /api/driver/status — read the driver's last known status.
 * Used by the app on socket reconnect to fetch the latest value from a
 * authoritative source rather than waiting for a push event.
 */
router.get("/status", verifyToken, async (req, res) => {
  const uid = req.user.uid;
  try {
    const db = getDb();
    const snap = await db.collection("drivers").doc(uid).get();
    const status = snap.exists && snap.data().status ? snap.data().status : "online";
    const name = snap.exists && snap.data().name ? snap.data().name : "Driver";
    return res.status(200).json({ success: true, status, name });
  } catch (err) {
    console.error(`[DriverStatusRoutes] read failed for ${uid}:`, err.message);
    return res.status(500).json({ success: false, message: err.message });
  }
});

module.exports = router;
