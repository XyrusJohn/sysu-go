# Firestore indexes in this checkout

This page summarizes the composite indexes declared in the repository's root [`firestore.indexes.json`](../firestore.indexes.json). The JSON file is canonical for this checkout. This audit did not query Firebase, so it does not verify which indexes are deployed.

## Declared index groups

| Collection group | Fields present in the checked-in configuration |
|---|---|
| `queue` | `registrationComplete`, `status`, `entryTime`; `status`, `entryTime`; `registrationComplete`, `status`, `joinedAt`; `registrationComplete`, `status`, `truckType`, `joinedAt`; `truckType`, `status`, `registrationComplete`, `joinedAt`; `driverId`, `status`; and `status`, `joinedAt` |
| `qr_tokens` | `used` plus `createdAt`, ordered both ascending and descending |
| `role_requests` | `status` plus descending `createdAt` |
| `sap_assignments` | `driverId` plus descending `assignedAt`; `driverId` plus `tripStatus`; and descending `assignedAt` |
| `drivers` | `status` plus descending `updatedAt` |
| `incidents` | descending `createdAt` |

The checked-in file contains overlapping queue entries; retain them until each query and deployed configuration has been reconciled. Do not treat older names or field combinations in historical documents as instructions to create indexes.

## Query and deployment checks

Relevant query code is in `backend/controller/qrController.js`, `backend/controller/tripController.js`, and the Android queue code. Firebase may return a missing-index error when a query needs an index not deployed for its target project. Confirm a query against the current code, read the exact Firebase error and proposed index, then verify the intended project and deployed state with the release owner before applying changes.

Do not run live data changes as part of local tests. See [local emulator setup](LOCAL_EMULATOR_SETUP.md) and the [release test plan](RELEASE_TEST_PLAN.md).
