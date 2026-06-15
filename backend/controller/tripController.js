const { getDb } = require("../config/firebaseAdmin.js");
const { FieldValue } = require("firebase-admin/firestore");

const ALLOWED_TRUCK_TYPES = ["AUV", "4W", "6W", "10W", "20F", "40F"];

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

const registerTrip = async (req, res) => {
  const db = getDb();

  try {
    const {
      queueId,
      driverId,
      helperFN, helperMI, helperLN,
      company,
      truckType,
      platePrefix,
      plateSuffix,
      driverPhone
    } = req.body;

    // 1. Auth: caller must match the driver on the queue entry
    if (!req.user || !req.user.uid) {
      return res.status(401).json({ success: false, message: "Unauthorized" });
    }
    if (driverId && driverId !== req.user.uid) {
      return res.status(403).json({ success: false, message: "Driver ID mismatch — not authorized to update this entry" });
    }

    // 1. Validate required fields
    if (!queueId || !helperFN || !helperLN || !company || !truckType || !platePrefix || !plateSuffix) {
      return res.status(400).json({ success: false, message: "Missing required fields" });
    }

    // 2. Normalize and validate truckType
    const normalizedTruckType = truckType.toUpperCase().trim();
    if (!ALLOWED_TRUCK_TYPES.includes(normalizedTruckType)) {
      return res.status(400).json({
        success: false,
        message: `Invalid truckType. Must be: ${ALLOWED_TRUCK_TYPES.join(", ")}`
      });
    }

    // 3. Sanitize inputs
    const s = {
      helperFN: SANITIZE(helperFN, 50),
      helperMI: SANITIZE_MI(helperMI),
      helperLN: SANITIZE(helperLN, 50),
      company: SANITIZE(company, 100),
      platePrefix: SANITIZE(platePrefix, 4).toUpperCase().replace(/[^A-Z0-9-]/g, ""),
      plateSuffix: SANITIZE(plateSuffix, 4).replace(/\D/g, "")
    };

    // 4. Validate plate fields
    if (s.platePrefix.length < 2 || s.platePrefix.length > 4) {
      return res.status(400).json({ success: false, message: "platePrefix must be 2-4 characters" });
    }
    if (s.plateSuffix.length < 3 || s.plateSuffix.length > 4) {
      return res.status(400).json({ success: false, message: "plateSuffix must be 3-4 digits" });
    }

    // 5. Fetch queue document
    const queueRef = db.collection("queue").doc(queueId);
    const queueDoc = await queueRef.get();

    if (!queueDoc.exists) {
      return res.status(404).json({ success: false, message: "Queue entry not found" });
    }

    const existingData = queueDoc.data();

    if (existingData.registrationComplete === true) {
      return res.status(409).json({
        success: false,
        message: "Trip already registered for this queue entry"
      });
    }

    // 6. Build helper full name and plate number
    const helper = `${s.helperFN} ${s.helperMI}. ${s.helperLN}`;
    const plateNumber = `${s.platePrefix}-${s.plateSuffix}`;

    // 7. Atomic update — add all trip registration fields
    const updateData = {
      helperFN: s.helperFN,
      helperMI: s.helperMI,
      helperLN: s.helperLN,
      helper,
      company: s.company,
      truckType: normalizedTruckType,
      platePrefix: s.platePrefix,
      plateSuffix: s.plateSuffix,
      plateNumber,
      status: "waiting",
      registrationComplete: true,
      updatedAt: FieldValue.serverTimestamp()
    };

    if (driverPhone) {
      updateData.driverPhone = SANITIZE(driverPhone, 20);
    }

    await queueRef.update(updateData);

    console.log(`[registerTrip] Queue entry ${queueId} trip registered successfully`);

    // 8. Emit qr_token_used — token already consumed but ensures TV refresh consistency
    const io = req.app.get("io");
    if (io) {
      if (existingData.token) io.emit("qr_token_used", { token: existingData.token });

      // Broadcast updated queue so Fleet Manager dashboard auto-refreshes
      const queueSnap = await db
        .collection("queue")
        .where("status", "in", ["waiting", "called"])
        .orderBy("joinedAt", "asc")
        .get();

      const allQueued = [];
      queueSnap.forEach((d) => allQueued.push({ id: d.id, ...d.data() }));
      io.emit("queue_update", { queue: allQueued });
    }

    return res.status(200).json({
      success: true,
      message: "Trip registered successfully",
      queueId,
      registrationComplete: true,
      helper,
      plateNumber
    });

  } catch (error) {
    console.error("[registerTrip] error:", error);
    return res.status(500).json({ success: false, message: error.message });
  }
};

module.exports = { registerTrip };