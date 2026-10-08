const { getFirebaseAuth, getDb } = require("../config/firebaseAdmin.js");
const APPROVED_ROLES = new Set(["driver", "fleet_manager", "admin", "super_admin", "qr_generator"]);

// All protected routes require current Auth validity and a canonical active profile.
const verifyToken = async (req, res, next) => {
  const header = req.headers.authorization;
  if (typeof header !== "string" || !/^Bearer [^\s]+$/.test(header)) {
    return res.status(401).json({ success: false, message: "Unauthorized" });
  }
  try {
    const decoded = await getFirebaseAuth().verifyIdToken(header.slice(7), true);
    const profile = await getDb().collection("users").doc(decoded.uid).get();
    const data = profile.exists ? profile.data() : null;
    if (!data || data.status !== "active" || !APPROVED_ROLES.has(data.role)) {
      return res.status(403).json({ success: false, message: "Account not authorized" });
    }
    req.user = decoded;
    req.userProfile = { role: data.role, status: data.status };
    return next();
  } catch (_) {
    return res.status(401).json({ success: false, message: "Unauthorized" });
  }
};

module.exports = { verifyToken };
