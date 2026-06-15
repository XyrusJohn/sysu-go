// DEPRECATED — This module is no longer used.
// Backend auth is handled by Firebase Auth directly.
// All clients (web, Android) use Firebase Auth SDK, not backend JWT routes.
module.exports = {
  verifyToken: async (req, res, next) => {
    // Safe pass-through — authRoutes are no longer mounted
    return res.status(401).json({ success: false, message: "Auth routes deprecated." });
  },
};