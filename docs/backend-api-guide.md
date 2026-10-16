# SYSU Go Backend API

> Scope: selected route and socket contracts verified from the current source tree on 2026-10-15. This is not a deployment report. The old MySQL/Supabase/JWT examples are preserved in [the archived guide](archive/legacy-backend-api-guide.md) and must not be used as current setup instructions.

## Runtime and identity

The backend starts from `backend/server.js`, uses Express and Socket.IO, and initializes Firebase Admin. Protected routes verify Firebase ID tokens and check the canonical active `users/{uid}` profile in `backend/middleware/auth.js`. The backend package declares Node 24 and `npm test`; the project root package is only a wrapper.

## HTTP route map

| Method and path | Access and behavior |
|---|---|
| `GET /api/health` | Liveness response |
| `POST /api/qr/generate` | Active QR generator/admin/super-admin; issues a token |
| `GET /api/qr/verify` | Public, read-only token verification |
| `POST /api/qr/scan` | Active driver; admission is identity-bound and transactional |
| `GET /api/qr/queue` | Active fleet manager/admin/super-admin |
| `GET /api/qr/own-position` | Active driver; reads only the caller's queue position |
| `PATCH /api/qr/queue-status` | Active fleet manager/admin/super-admin |
| `POST /api/trip/register` | Authenticated active user; trip controller enforces queue ownership and registration rules |
| `GET`, `POST /api/driver/status` | Active driver only |
| `POST /api/pod/{shipmentId}/{obdNumber}` | Driver only; validates ownership and applies POD progress transactionally |
| `POST /api/pod/{shipmentId}/group` | Driver only; atomically unloads or completes every pending OBD with matching SAP destination identity; stores one shared image with OBD-keyed references |
| `GET /api/sap/shipments` | Retired mobile proxy: anonymous 401, unauthorized role 403, authorized staff 410 |

Routes are mounted in `backend/server.js`; route access is declared in `backend/routes/`. Treat those files and their tests as the contract when it changes. This table does not describe deployed versions or Firebase rules.

## Socket behavior

Socket authentication and event validation are implemented in `backend/middleware/socketAuth.js` and configured in `backend/server.js`. Active drivers can send only self-scoped validated status/location events; tracking is delivered to the authorized `staff:tracking` room. Presence lifecycle, reconnect handling, and POD notifications have regression tests under `backend/tests/integration/` and `backend/tests/security/`. A passing local test does not prove production room policy or device connectivity.

## Data and configuration

The backend reads and writes Firestore collections including `users`, `drivers`, `queue`, `qr_tokens`, `qr_admissions`, and `sap_assignments`; individual POD images are stored below `pod_images/{obdNumber}`, while grouped captures store one image in `pod_groups/{groupPodId}` and leave a small group reference in each OBD image document. Firestore rules allow reads to assigned drivers and staff but deny client writes. Current deployed rules and indexes must be verified independently.

Use [LOCAL_EMULATOR_SETUP.md](LOCAL_EMULATOR_SETUP.md) for isolated emulator configuration. Do not copy production credentials into a local test process. See [Firestore index notes](firestore-composite-indexes.md) and [current release status](FIX_TRACKER.md).

## Verification sources

- [QR routes and tests](../backend/routes/qrRoutes.js) and `backend/tests/integration/qrAdmission.spec.cjs`
- [Trip registration route](../backend/routes/tripRoute.js) and `backend/tests/integration/tripRegistration.spec.cjs`
- [POD route](../backend/routes/podRoutes.js) and `backend/tests/integration/podProgress.spec.cjs`
- [Socket middleware](../backend/middleware/socketAuth.js) and `backend/tests/integration/socketAuth.spec.cjs`
