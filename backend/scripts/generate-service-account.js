const fs = require('fs');
const path = require('path');
const dotenv = require('dotenv');

// Load firebase-temp.env so we can read FIREBASE_SERVICE_ACCOUNT from it
dotenv.config({ path: path.join(__dirname, '..', 'firebase-temp.env') });

const raw = process.env.FIREBASE_SERVICE_ACCOUNT;
if (!raw) {
  console.error('FIREBASE_SERVICE_ACCOUNT not found in firebase-temp.env');
  process.exit(1);
}

let serviceAccount;
try {
  serviceAccount = JSON.parse(raw);
} catch (e) {
  console.error('Failed to JSON.parse FIREBASE_SERVICE_ACCOUNT:', e.message);
  process.exit(1);
}

// After JSON.parse, the private_key newlines are real \n chars—exactly what we need
const outPath = path.join(__dirname, '..', 'config', 'serviceAccountKey.json');
fs.writeFileSync(outPath, JSON.stringify(serviceAccount, null, 2));
console.log('Written:', outPath);

// Quick verify
const check = require(outPath);
console.log('project_id:', check.project_id);
console.log('private_key PEM valid:', check.private_key.includes('-----BEGIN PRIVATE KEY-----'));
console.log('Total PEM lines:', check.private_key.split('\n').length, '(should be ~27)');