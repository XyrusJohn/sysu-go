const { initializeApp, getApp, cert } = require("firebase-admin/app");
const { getFirestore } = require("firebase-admin/firestore");
const { getAuth } = require("firebase-admin/auth");

let _db = null;
let _auth = null;

function getAdminApp() {
  try {
    return getApp();
  } catch (_) {}

  // Try primary env var first (FIREBASE_SERVICE_ACCOUNT_JSON), then fallback to
  // FIREBASE_SERVICE_ACCOUNT — both can have the same JSON format.
  const serviceAccountRaw =
    process.env.FIREBASE_SERVICE_ACCOUNT_JSON || process.env.FIREBASE_SERVICE_ACCOUNT;

  if (!serviceAccountRaw) {
    console.error("[firebaseAdmin] Neither FIREBASE_SERVICE_ACCOUNT_JSON nor FIREBASE_SERVICE_ACCOUNT env var is set.");
    console.error("  Add your Firebase service account JSON to .env as FIREBASE_SERVICE_ACCOUNT=");
    process.exit(1);
  }

  let serviceAccount;
  try {
    serviceAccount = JSON.parse(serviceAccountRaw);
  } catch (err) {
    console.error("[firebaseAdmin] Failed to parse service account JSON:", err.message);
    process.exit(1);
  }

  // Normalize private_key: ensure real newlines for the PEM format.
  // Handles \n escape sequences from JSON-oneliner env vars.
  if (serviceAccount.private_key) {
    serviceAccount.private_key = serviceAccount.private_key
      .replace(/\\n/g, "\n")
      .replace(/\\\\n/g, "\n");
  }

  initializeApp({ credential: cert(serviceAccount) });
  console.log("[firebaseAdmin] Firebase Admin initialized.");

  return getApp();
}

function getDb() {
  getAdminApp();
  if (!_db) _db = getFirestore(getAdminApp());
  return _db;
}

function getFirebaseAuth() {
  getAdminApp();
  if (!_auth) _auth = getAuth(getAdminApp());
  return _auth;
}

module.exports = { getDb, getFirebaseAuth };