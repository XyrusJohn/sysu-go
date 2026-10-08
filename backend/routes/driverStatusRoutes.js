const express = require("express");
const { verifyToken } = require("../middleware/auth.js");
const { getDb } = require("../config/firebaseAdmin.js");
const router = express.Router();
const VALID_STATUSES = new Set(["online", "offline", "in_transit", "returning_to_base"]);
const driverOnly = (req, res, next) => req.userProfile?.role === "driver"
  ? next() : res.status(403).json({ success: false, message: "Account not authorized" });

router.post("/status", verifyToken, driverOnly, async (req, res) => {
  const uid = req.user.uid, body = req.body;
  if (!body || typeof body !== "object" || Array.isArray(body) || !VALID_STATUSES.has(body.status)
      || (body.name != null && (typeof body.name !== "string" || body.name.length > 100))
      || Object.keys(body).some(key => !["status", "name", "driverId"].includes(key))) {
    return res.status(400).json({ success: false, message: "Invalid driver status request" });
  }
  if (body.driverId != null && body.driverId !== uid) {
    return res.status(403).json({ success: false, message: "Cannot act for another driver" });
  }
  try {
    const ref = getDb().collection("drivers").doc(uid);
    const driver = await ref.get();
    if (!driver.exists) return res.status(404).json({ success: false, message: "Driver profile unavailable" });
    const status = body.status, name = driver.data().name || "Driver";
    await ref.set({ status, isReturning: status === "returning_to_base" }, { merge: true });
    const payload = { driverId: uid, status, name, timestamp: Date.now(), source: "backend_proxy" };
    const io = req.app.get("io");
    if (io) {
      io.to(`driver:${uid}`).emit("driver_status_change", payload);
      io.to("staff:tracking").emit("driver_status_change", payload);
    }
    return res.status(200).json({ success: true, status, source: "backend_proxy" });
  } catch (_) {
    console.error("[DriverStatusRoutes] status persistence unavailable");
    return res.status(503).json({ success: false, message: "Driver status temporarily unavailable" });
  }
});

router.get("/status", verifyToken, driverOnly, async (req, res) => {
  try {
    const snapshot = await getDb().collection("drivers").doc(req.user.uid).get();
    if (!snapshot.exists) return res.status(404).json({ success: false, message: "Driver profile unavailable" });
    const {status, name} = snapshot.data();
    if (!VALID_STATUSES.has(status)) return res.status(503).json({ success: false, message: "Driver status unavailable" });
    return res.status(200).json({ success: true, status, name: name || "Driver" });
  } catch (_) {
    console.error("[DriverStatusRoutes] status read unavailable");
    return res.status(503).json({ success: false, message: "Driver status temporarily unavailable" });
  }
});
module.exports = router;
