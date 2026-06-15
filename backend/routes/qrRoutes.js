const express = require("express");
const router = express.Router();
const { verifyToken } = require("../middleware/auth.js");
const { generateQR, verifyQR, scanQR, getQueue, updateQueueStatus } = require("../controller/qrController.js");

// Whether to enforce Firebase token auth on /scan.
// Set QR_SCAN_REQUIRE_AUTH=true in Railway env vars to lock down.
// Mobile app needs to be updated to send Authorization: Bearer <token> before enabling.
const QR_SCAN_REQUIRE_AUTH = process.env.QR_SCAN_REQUIRE_AUTH === "true";

const scanMiddleware = QR_SCAN_REQUIRE_AUTH ? verifyToken : (req, res, next) => next();

router.post("/generate", generateQR);
router.get("/verify", verifyQR);
router.post("/scan", scanMiddleware, scanQR);
router.get("/queue", getQueue);
router.patch("/queue-status", verifyToken, updateQueueStatus);

// NOTE: When QR_SCAN_REQUIRE_AUTH is enabled, the mobile app must send:
//   Authorization: Bearer <Firebase ID token>
// in the /scan request header. The token is available via
// FirebaseAuth.getIdToken() on Android. Update QRApiService.kt to add the header.

module.exports = router;