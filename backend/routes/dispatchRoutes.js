const express = require("express");
const router = express.Router();
const { verifyToken } = require("../middleware/auth.js");
const { getFirebaseAuth, getDb } = require("../config/firebaseAdmin.js");

const VALID_DISPATCH_STATUSES = ["assigned", "loading", "in_transit", "delivered", "cancelled"];

// POST /api/dispatch/status — update the status of an SAP dispatch assignment
// See: backend/docs/integration/SYSU_GO_BACKEND_REQUIREMENTS.md Item 1
router.post("/status", verifyToken, async (req, res) => {
  try {
    const { dispatchKey, status, driverId, lat, lng, notes } = req.body;

    // Basic validation
    if (!dispatchKey || !status) {
      return res.status(400).json({
        success: false,
        message: "dispatchKey and status are required",
      });
    }
    if (!VALID_DISPATCH_STATUSES.includes(status)) {
      return res.status(400).json({
        success: false,
        message: `Invalid status. Must be one of: ${VALID_DISPATCH_STATUSES.join(", ")}`,
      });
    }

    const db = getDb();
    const now = new Date();

    // Build the update payload — write to sap_assignments/{dispatchKey}
    const updateData = {
      status,
      updatedAt: now,
      updatedBy: req.user.uid,
    };
    if (driverId) updateData.driverId = driverId;
    if (lat != null) updateData.lat = lat;
    if (lng != null) updateData.lng = lng;
    if (notes) updateData.notes = notes;

    // Append to statusHistory array using a transaction
    const assignmentRef = db.collection("sap_assignments").doc(dispatchKey);
    await db.runTransaction(async (tx) => {
      const snap = await tx.get(assignmentRef);
      const existing = snap.exists ? snap.data() : {};
      const history = Array.isArray(existing.statusHistory) ? [...existing.statusHistory] : [];
      history.push({
        status,
        at: now,
        by: req.user.uid,
        ...(lat != null && lng != null ? { lat, lng } : {}),
        ...(notes ? { notes } : {}),
      });
      tx.set(assignmentRef, { ...updateData, statusHistory: history }, { merge: true });
    });

    // If driverId provided, also update the corresponding queue entry status
    if (driverId) {
      try {
        const queueRef = db.collection("queue").doc(driverId);
        await queueRef.update({
          status: status === "in_transit" ? "in_transit" : status,
          updatedAt: now,
        });
      } catch (qErr) {
        console.warn(`[DispatchRoutes] Could not update queue entry ${driverId}:`, qErr.message);
      }
    }

    // Emit Socket.IO event to all connected clients
    const io = req.app.get("io");
    if (io) {
      io.emit("dispatch_status_update", {
        type: "dispatch_status_update",
        dispatchKey,
        status,
        driverId: driverId || null,
        lat: lat ?? null,
        lng: lng ?? null,
        notes: notes || null,
        updatedAt: now.toISOString(),
      });
    }

    console.log(`[DispatchRoutes] Status updated: ${dispatchKey} → ${status} by ${req.user.uid}`);
    return res.status(200).json({
      success: true,
      dispatchKey,
      status,
      updatedAt: now.toISOString(),
    });
  } catch (error) {
    console.error("[DispatchRoutes] dispatch/status error:", error);
    return res.status(500).json({ success: false, message: error.message });
  }
});

module.exports = router;