const { getFirebaseAuth } = require("../config/firebaseAdmin.js");

/**
 * Express middleware: verifies Firebase ID token from the Authorization Bearer header.
 * Attaches the decoded token payload to req.user on success.
 */
const verifyToken = async (req, res, next) => {
  const authHeader = req.headers["authorization"];
  if (!authHeader || !authHeader.startsWith("Bearer ")) {
    return res.status(401).json({ success: false, message: "Missing or invalid Authorization header" });
  }
  const token = authHeader.slice(7);
  try {
    const auth = getFirebaseAuth();
    const decoded = await auth.verifyIdToken(token);
    req.user = decoded;
    next();
  } catch (err) {
    console.error("[authMiddleware] Token verify failed:", err.message);
    return res.status(401).json({ success: false, message: "Invalid or expired token" });
  }
};

module.exports = { verifyToken };