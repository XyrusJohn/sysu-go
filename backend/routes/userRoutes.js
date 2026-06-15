const express = require("express");
const router = express.Router();
const { verifyToken } = require("../middleware/auth.js");
const { getFirebaseAuth, getDb } = require("../config/firebaseAdmin.js");

// POST /api/users/update-role — admin assigns a role to a user
router.post("/update-role", verifyToken, async (req, res) => {
  try {
    const { uid, role } = req.body;
    if (!uid || !role) {
      return res.status(400).json({ success: false, message: "uid and role are required" });
    }

    const validRoles = ["admin", "super_admin", "fleet_manager", "qr_generator", "pending", "driver"];
    if (!validRoles.includes(role)) {
      return res.status(400).json({ success: false, message: `Invalid role. Must be one of: ${validRoles.join(", ")}` });
    }

    const db = getDb();
    await db.collection("users").doc(uid).update({ role });

    console.log(`[UserRoutes] Role updated: ${uid} → ${role} by ${req.user.uid}`);
    return res.status(200).json({ success: true, message: "Role updated", role });
  } catch (error) {
    console.error("[UserRoutes] update-role error:", error);
    return res.status(500).json({ success: false, message: error.message });
  }
});

// POST /api/users/delete — super-admin permanently deletes a user
router.post("/delete", verifyToken, async (req, res) => {
  try {
    const { uid } = req.body;
    if (!uid) {
      return res.status(400).json({ success: false, message: "uid is required" });
    }

    const db = getDb();
    const auth = getFirebaseAuth();

    await db.collection("users").doc(uid).delete();
    try {
      await auth.updateUser(uid, { disabled: true });
    } catch (authErr) {
      console.warn(`[UserRoutes] Could not disable Auth user ${uid}:`, authErr.message);
    }

    console.log(`[UserRoutes] User deleted: ${uid} by ${req.user.uid}`);
    return res.status(200).json({ success: true, message: "User deleted" });
  } catch (error) {
    console.error("[UserRoutes] delete error:", error);
    return res.status(500).json({ success: false, message: error.message });
  }
});

module.exports = router;