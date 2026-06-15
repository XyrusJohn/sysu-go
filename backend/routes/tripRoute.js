const express = require("express");
const router = express.Router();
const { verifyToken } = require("../middleware/auth.js");
const { registerTrip } = require("../controller/tripController.js");

router.post("/register", verifyToken, registerTrip);

module.exports = router;