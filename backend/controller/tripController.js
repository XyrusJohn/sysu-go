const { getDb } = require("../config/firebaseAdmin.js");
const { FieldValue } = require("firebase-admin/firestore");
const TYPES = new Set(["AUV", "4W", "6W", "10W", "20F", "40F"]);
const KEYS = new Set(["queueId", "driverId", "helperFN", "helperMI", "helperLN", "company", "truckType", "platePrefix", "plateSuffix", "driverPhone"]);
const text = (value, max, optional = false) => typeof value === "string" && value.length <= max && !/[\x00-\x1f]/.test(value) && (optional || value.trim().length > 0);
const fail = (status, message) => Object.assign(new Error(message), { status });
const clean = value => value.trim().replace(/[<>$&"|;'\\/]/g, "").replace(/\s+/g, " ");

const registerTrip = async (req, res) => {
  if (!req.user?.uid) return res.status(401).json({ success: false, message: "Unauthorized" });
  const body = req.body;
  if (!body || typeof body !== "object" || Array.isArray(body) || Object.keys(body).some(key => !KEYS.has(key)) ||
      !text(body.queueId, 128) || /[/\\]/.test(body.queueId) || [".", ".."].includes(body.queueId) ||
      !text(body.helperFN, 50) || !text(body.helperLN, 50) || !text(body.company, 100) ||
      !text(body.truckType, 10) || !text(body.platePrefix, 4) || !text(body.plateSuffix, 4) ||
      (body.helperMI != null && !text(body.helperMI, 5, true)) ||
      (body.driverPhone != null && !text(body.driverPhone, 20, true))) {
    return res.status(400).json({ success: false, message: "Invalid trip registration fields" });
  }
  if (body.driverId != null && body.driverId !== req.user.uid) return res.status(403).json({ success: false, message: "Driver identity mismatch" });
  const truckType = body.truckType.trim().toUpperCase();
  const platePrefix = body.platePrefix.trim().toUpperCase();
  const plateSuffix = body.plateSuffix.trim();
  const helperFN = clean(body.helperFN), helperLN = clean(body.helperLN);
  const helperMI = clean(body.helperMI || "").replace(/[^a-z]/gi, "").slice(0, 1).toUpperCase();
  const company = clean(body.company);
  if (!TYPES.has(truckType) || !/^[A-Z0-9-]{2,4}$/.test(platePrefix) || !/^\d{3,4}$/.test(plateSuffix) || !helperFN || !helperLN || !company) {
    return res.status(400).json({ success: false, message: "Invalid trip registration fields" });
  }
  try {
    const db = getDb();
    const queue = db.collection("queue").doc(body.queueId);
    const actor = db.collection("users").doc(req.user.uid);
    const helper = [helperFN, helperMI ? helperMI + "." : "", helperLN].filter(Boolean).join(" ");
    const plateNumber = platePrefix + "-" + plateSuffix;
    await db.runTransaction(async tx => {
      const profile = await tx.get(actor);
      const entry = await tx.get(queue);
      if (!profile.exists || profile.data().role !== "driver" || profile.data().status !== "active") throw fail(403, "Account not authorized");
      if (!entry.exists) throw fail(404, "Queue entry not found");
      const existing = entry.data();
      if (existing.driverId !== req.user.uid) throw fail(403, "Queue entry not owned by this driver");
      if (existing.status !== "waiting" || existing.registrationComplete === true) throw fail(409, "Queue entry cannot be registered");
      tx.update(queue, {
        helperFN, helperMI, helperLN, helper, company, truckCompany: company,
        truckType, platePrefix, plateSuffix, plateNumber, registrationComplete: true,
        ...(body.driverPhone != null ? { driverPhone: clean(body.driverPhone) } : {}),
        updatedAt: FieldValue.serverTimestamp(),
      });
    });
    // Owned notification only; Firestore listeners observe authoritative state.
    const io = req.app.get("io");
    if (io) {
      try { io.to("driver:" + req.user.uid).emit("queue_registration_complete", { queueId: body.queueId }); } catch (_) {}
    }
    return res.status(200).json({ success: true, message: "Trip registered successfully", queueId: body.queueId, tripId: body.queueId, registrationComplete: true, helper, plateNumber });
  } catch (error) {
    const status = [403, 404, 409].includes(error.status) ? error.status : 500;
    return res.status(status).json({ success: false, message: status === 500 ? "Unable to register trip" : error.message });
  }
};

module.exports = { registerTrip };
