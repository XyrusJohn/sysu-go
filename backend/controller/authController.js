// DEPRECATED — This module is no longer used.
// All sign-in flows use Firebase Auth directly (web & Android).
// Backend auth routes have been removed; QR flow uses Firebase Firestore only.
module.exports = {
  signup: async (req, res) => res.status(410).json({ success: false, message: "Deprecated" }),
  login: async (req, res) => res.status(410).json({ success: false, message: "Deprecated" }),
  logout: async (req, res) => res.status(410).json({ success: false, message: "Deprecated" }),
};