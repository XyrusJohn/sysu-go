const { initializeApp, getApp, cert } = require("firebase-admin/app");
const { getFirestore } = require("firebase-admin/firestore");
const fs = require("fs");
const path = require("path");

let db;

const serviceAccountRaw = process.env.FIREBASE_SERVICE_ACCOUNT_JSON;

if (!serviceAccountRaw) {
  console.error("Firebase init error: FIREBASE_SERVICE_ACCOUNT_JSON env var is not set.");
  process.exit(1);
}

try {
  let app;
  try {
    app = getApp();
  } catch (e) {
    // Create a temporary file for Google Application Default Credentials
    // This fixes the "Could not load default credentials" error for all Google libraries
    const credsPath = path.join(__dirname, "service-account-temp.json");
    fs.writeFileSync(credsPath, serviceAccountRaw);
    process.env.GOOGLE_APPLICATION_CREDENTIALS = credsPath;

    const serviceAccount = JSON.parse(serviceAccountRaw);
    app = initializeApp({
      credential: cert(serviceAccount),
    });
  }

  db = getFirestore(app);
  console.log("Connected to Firebase Firestore");
} catch (err) {
  console.error("Firebase init error:", err.message);
  process.exit(1);
}

module.exports = db;