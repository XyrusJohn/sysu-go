// backend/scripts/seed-truck-companies.js
// Usage: node seed-truck-companies.js
// Requires: dotenv, firebase-admin (both in package.json)

require('dotenv').config({ path: require('path').resolve(__dirname, '../.env') });

const { getDb } = require('../config/firebaseAdmin');

const db = getDb();

// Dedupe by forwarderCode — use forwarderCode as document ID so it's queryable by code
const companies = [
  ['40000279', 'LALAMOVE PHILIPPINES INC'],
  ['40001681', 'SCHENKER DISTRIBUTION SOLUTIONS'],
  ['40001916', 'ZBZ GLOBAL LOGISTICS (PHILS) INC.'],
  ['50000001', '2GO LOGISTICS INC'],
  ['50000006', 'BCJ DELIVERY SERVICES'],
  ['50000013', 'DANIKO TRUCKING SERVICES'],
  ['50000016', 'EDMONTON TRUCKING SERVICES'],
  ['50000018', 'F. DRILON TRUCKING SERVICES'],
  ['50000020', 'F2 LOGISTICS PHILIPPINES INC.'],
  ['50000023', 'FAST LOGISTICS CORPORATION'],
  ['50000025', 'FASTCARGO LOGISTICS CORPORATION'],
  ['50000037', 'JONTHER CORPORATION'],
  ['50000039', 'KE ENTERPRISES'],
  ['50000040', 'KENJITSU TRANSPORTER CORPORATION'],
  ['50000043', 'LUBAY DELIVERY SERVICE'],
  ['50000047', 'METRO COMBINED LOGISTICS SOLUTIONS INC'],
  ['50000048', 'MISHABELA LOGISTICS SERVICES'],
  ['50000054', 'PHOEMIROLD TRANSPORT'],
  ['50000055', 'R.D. CASTILLO TRUCKING'],
  ['50000059', 'RL TRUCKING SERVICES'],
  ['50000066', 'TRITEK REVERSE LOGISTICS CORP.'],
  ['50000081', 'SAFEXPRESS LOGISTICS INC.'],
  ['50000090', 'RICARDO AND ANNIE TRUCKING'],
  ['50000091', 'SHIPA TRANSPORT'],
  ['50000093', 'MC3 CARMONA TRUCKING SERVICES'],
  ['50000095', 'DAPH TRUCKING SERVICES'],
  ['50000096', "CK'5 TRUCKING SERVICES"],
  ['50000101', 'GRAMORE TRUCKING SERVICES'],
  ['50000106', 'EXCELLENT FREIGHT INC.'],
  ['50000109', 'JUANCAT DELIVERY SERVICES'],
  ['50000111', 'VDS DELIVERY SERVICES'],
  ['50000119', 'ALOYSIANZ TRUCKING SERVICES'],
  ['50000120', "LITE EXPRESS INT'L INC"],
  ['50000122', 'RL1 TRUCKING SERVICES'],
  ['50000123', 'ST. CHRISTOPHER FORWARDERS OPC'],
  ['50000124', 'FASTLOGIC CORP.'],
  ['50000125', 'ROAR DELIVERY SERVICES'],
  ['50000126', 'MHETHZ TRUCKING SERVICES'],
  ['50000127', 'SHIASTINE LOGISTICS SERVICES'],
  ['50000128', 'J.H. CAROLINO DELIVERY SERVICES'],
  ['50000129', 'L3V LOGISTICS SERVICES'],
  ['50000130', 'DUSAU TRUCKING SERVICES'],
  ['50000131', 'QUINN&QUIAA TRUCKING SERVICES'],
  ['50000132', 'EZEKIEL DELIVERY SERVICES'],
  ['50000137', 'MSAU TRANSPORT SERVICES'],
  ['70000005', "WE'VELINK SERVICES INCORPORATED"],
];

async function seed() {
  const collection = db.collection('truck_company');
  let count = 0;

  for (const [code, name] of companies) {
    await collection.doc(code).set({
      forwarderCode: code,
      name: name.trim(),
      active: true,
      createdAt: new Date().toISOString(),
    });
    console.log(`[${code}] ${name.trim()}`);
    count++;
  }

  console.log(`\n✓ Seeded ${count} truck companies into truck_company collection.`);
  process.exit(0);
}

seed().catch(err => {
  console.error(err);
  process.exit(1);
});