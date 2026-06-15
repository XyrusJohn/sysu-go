// backend/scripts/seed-guards.js
// Usage: node seed-guards.js
// Collection: security_guard
// Schema: guardId, firstName, middleInitial, lastName, fullName, active, createdAt

require('dotenv').config({ path: require('path').resolve(__dirname, '../.env') });
const { getDb } = require('../config/firebaseAdmin');

const db = getDb();

const guards = [
  { guardId: 'SG-001', firstName: 'James',   middleInitial: 'B.', lastName: 'Rivera'  },
  { guardId: 'SG-002', firstName: 'Ramon',    middleInitial: 'A.', lastName: 'Cruz'    },
  { guardId: 'SG-003', firstName: 'Daniel',   middleInitial: 'P.', lastName: 'Torres'  },
  { guardId: 'SG-004', firstName: 'Kevin',     middleInitial: 'L.', lastName: 'Morales'  },
  { guardId: 'SG-005', firstName: 'Brian',    middleInitial: 'C.', lastName: 'Navarro'  },
  { guardId: 'SG-006', firstName: 'Marcus',   middleInitial: 'D.', lastName: 'Santos'   },
  { guardId: 'SG-007', firstName: 'Eduardo',  middleInitial: 'M.', lastName: 'Reyes'   },
  { guardId: 'SG-008', firstName: 'Andres',   middleInitial: 'V.', lastName: 'Flores'   },
  { guardId: 'SG-009', firstName: 'Roberto',  middleInitial: 'J.', lastName: 'Garcia'  },
  { guardId: 'SG-010', firstName: 'Carlos',   middleInitial: 'E.', lastName: 'Mendoza'  },
];

async function seed() {
  const collection = db.collection('security_guard');
  for (const g of guards) {
    const fullName = `${g.firstName} ${g.middleInitial} ${g.lastName}`;
    await collection.doc(g.guardId).set({
      guardId:       g.guardId,
      firstName:     g.firstName,
      middleInitial: g.middleInitial,
      lastName:      g.lastName,
      fullName:      fullName,
      active:        true,
      createdAt:     new Date().toISOString(),
    });
    console.log(`[${g.guardId}] ${fullName}`);
  }
  console.log(`\n✓ Seeded ${guards.length} guards into security_guard.`);
  process.exit(0);
}

seed().catch(err => { console.error(err); process.exit(1); });