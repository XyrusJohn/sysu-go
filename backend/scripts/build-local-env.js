// Builds a complete, properly-formatted local-dev .env
// Run: node scripts/build-local-env.js
const fs = require('fs');
const path = require('path');
const dotenv = require('dotenv');

// Load FIREBASE_SERVICE_ACCOUNT from firebase-temp.env (already has clean formatting)
dotenv.config({ path: path.join(__dirname, '..', 'firebase-temp.env') });
const firebaseAccount = process.env.FIREBASE_SERVICE_ACCOUNT;

if (!firebaseAccount) {
  console.error('firebase-temp.env not found or FIREBASE_SERVICE_ACCOUNT missing.');
  console.error('Run: node scripts/write-env.js first');
  process.exit(1);
}

// Build the complete local .env
const localEnv = [
  '# ============================================================',
  '# LOCAL DEVELOPMENT CONFIG (active)',
  '# ============================================================',
  'PORT=3001',
  'NODE_ENV=development',
  '',
  'ALLOWED_ORIGINS=http://localhost:3001,http://10.0.2.2:3001',
  'FRONTEND_URL=http://localhost:3001',
  'API_BASE_URL=http://10.0.2.2:3001',
  '',
  'JWT_SECRET=your_jwt_secret_here',
  '',
  'FIREBASE_SERVICE_ACCOUNT=' + firebaseAccount,
  '',
  '# ============================================================',
  '# PRODUCTION CONFIG (comment out when developing locally)',
  '# ============================================================',
  '# PORT=8080',
  '# NODE_ENV=production',
  '# ALLOWED_ORIGINS=https://sysu-web-5a25d.web.app,https://sysu-app.firebaseapp.com,https://android-app.firebaseapp.com',
  '# FRONTEND_URL=https://sysu-web-5a25d.web.app',
  '# API_BASE_URL=https://sysu-web-production.up.railway.app',
  '# JWT_SECRET=your_jwt_secret_here',
  '# FIREBASE_SERVICE_ACCOUNT={...}',
  '',
].join('\n');

// Same content for .env.example as the canonical reference
const exampleEnv = localEnv; // same structure, .env.example is the template

const envPath = path.join(__dirname, '..', '.env');
const examplePath = path.join(__dirname, '..', '.env.example');

fs.writeFileSync(envPath, localEnv + '\n');
console.log('Written:', envPath);

fs.writeFileSync(examplePath, exampleEnv + '\n');
console.log('Written:', examplePath);

// Quick verify
const check = fs.readFileSync(envPath, 'utf8');
console.log('Total lines:', check.split('\n').length);
console.log('Active PORT:', check.match(/^PORT=(.*)$/m)?.[1]);
console.log('Active API_BASE_URL:', check.match(/^API_BASE_URL=(.*)$/m)?.[1]);