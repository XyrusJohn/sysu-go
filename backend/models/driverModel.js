const { getDb } = require("../config/firebaseAdmin.js");
const db = getDb();

// findDriverById — used by Socket.IO to get driver name from Firestore
const findDriverById = async (driverId) => {
  if (!driverId) return null;
  try {
    const doc = await db.collection("drivers").doc(driverId).get();
    if (!doc.exists) return null;
    const data = doc.data();
    if (!data) return null;
    // Store as first_name/last_name for Socket.IO compatibility
    const nameParts = (data.name || "").split(" ");
    return {
      first_name: nameParts[0] || "",
      last_name: nameParts.slice(1).join(" ") || "",
    };
  } catch (err) {
    console.error("Firestore findDriverById error:", err);
    return null;
  }
};

module.exports = { findDriverById };