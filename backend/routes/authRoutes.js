// DEPRECATED — Auth routes are no longer mounted.
// Kept to prevent module-not-found errors if anything references this file.
// Login/signup = Firebase Auth directly on client.
// Logout = Firebase Auth signOut() on client.
// No Supabase code in backend anymore.
const express = require("express");
const router = express.Router();

module.exports = router;