// backend/scripts/nuke-truck-companies.js
// Usage: node nuke-truck-companies.js
// WARNING: DELETES all documents in truck_company collection!

const { getDb } = require('../config/firebaseAdmin');

const db = getDb();

async function nuke() {
  const collection = db.collection('truck_company');
  const snapshot = await collection.get();

  if (snapshot.empty) {
    console.log('truck_company is already empty.');
    process.exit(0);
  }

  console.log(`Deleting ${snapshot.size} documents...`);

  // Delete in batches of 500 (Firestore limit)
  const batchSize = 500;
  for (let i = 0; i < snapshot.docs.length; i += batchSize) {
    const batch = db.batch();
    const batchDocs = snapshot.docs.slice(i, i + batchSize);
    batchDocs.forEach(doc => batch.delete(doc.ref));
    await batch.commit();
    console.log(`  Deleted batch ${Math.floor(i / batchSize) + 1} (${batchDocs.length} docs)`);
  }

  console.log('✓ All deleted. Now run: node seed-truck-companies.js');
  process.exit(0);
}

nuke().catch(err => {
  console.error(err);
  process.exit(1);
});