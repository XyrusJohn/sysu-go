# SYSU Go Android App

> Scope: source-checked Android implementation notes, reviewed 2026-10-15. This guide covers selected flows only. Check the current tracker for acceptance status; source and tests define behavior. No device or deployment behavior is asserted here.

## Overview

The Android module is a Kotlin application for driver account/profile, QR admission, queue and trip progress, location publishing, and proof-of-delivery (POD). It communicates with Firebase services and the SYSU-GO Express backend. The web dashboard is maintained in the sibling `sysu-app` project.

## Build configuration

`app/build.gradle.kts` declares namespace `com.sysu.go`, compile SDK 36, minimum SDK 24, target SDK 35, and Java/Kotlin target 11. It defines `debug`, `emulator`, `release`, and `liveTest` build types. The `emulator` type uses a `.debug` application ID suffix, local emulator endpoints, and Firebase emulator mode. `debug` and `release` declare hosted HTTPS endpoints; `liveTest` inherits release configuration and uses the debug signing key. Verify the selected variant and generated configuration before any install or test.

For isolated work, follow [LOCAL_EMULATOR_SETUP.md](LOCAL_EMULATOR_SETUP.md). It documents test prerequisites and the explicit emulator variant. Do not assume the Android Studio default variant targets an emulator.

## Authentication and API flows

The app uses Firebase Authentication. Backend protected routes verify Firebase ID tokens and require an active canonical user profile. Current endpoint authorization is defined in the route source:

| Operation | Method and path | Access |
|---|---|---|
| Generate a QR token | `POST /api/qr/generate` | Active `qr_generator`, `admin`, or `super_admin` |
| Verify a QR token | `GET /api/qr/verify` | Public; verification does not admit a driver |
| Scan/admit | `POST /api/qr/scan` | Active driver |
| Read queue | `GET /api/qr/queue` | Active `fleet_manager`, `admin`, or `super_admin` |
| Read own queue position | `GET /api/qr/own-position` | Active driver; own queue only |
| Update queue status | `PATCH /api/qr/queue-status` | Active staff role |
| Complete trip registration | `POST /api/trip/register` | Authenticated active account; ownership is checked by controller |
| Read/write driver status | `GET` or `POST /api/driver/status` | Driver only |
| Submit POD progress | `POST /api/pod/{shipmentId}/{obdNumber}` | Driver only; shipment ownership is checked |

See [backend API guide](backend-api-guide.md), [QR routes](../backend/routes/qrRoutes.js), [trip route](../backend/routes/tripRoute.js), and [POD route](../backend/routes/podRoutes.js). The `/api/sap/shipments` route is retired for staff with HTTP 410; SAP reads belong to the authenticated SYSU App service.

## Location and sockets

`LocationServices`, `GeneralTrackingActivity`, and `SocketManager` implement Android location and socket behavior. The backend authenticates socket handshakes, validates driver events, and sends tracking updates to authorized staff rooms. Event names, payload validation, and ownership rules are implemented in `backend/server.js` and `backend/middleware/socketAuth.js`; confirm those files when changing the contract. Automated coverage includes `backend/tests/integration/serverSocket.spec.cjs`, `backend/tests/integration/socketAuth.spec.cjs`, and `backend/tests/security/locationStream.test.cjs`.

These tests do not establish GPS freshness, battery usage, background reliability, or reconnect behavior on an actual phone. Those remain device acceptance items in [FIX_TRACKER.md](FIX_TRACKER.md).

## POD

The app captures delivery evidence and sends progress to the backend. The backend validates driver ownership and updates the assignment and POD image subcollection transactionally in `backend/routes/podRoutes.js`. Relevant tests include `backend/tests/integration/podProgress.spec.cjs`, `backend/tests/security/podProgress.test.cjs`, and `backend/tests/security/podAdapter.test.cjs`. Actual camera, storage, network, and sibling web preview behavior still require device/cross-app verification.

## Project guides

- [Backend API and socket contract](backend-api-guide.md)
- [Emulator and test isolation](LOCAL_EMULATOR_SETUP.md)
- [Current status and acceptance](project-status-summary.md)
- [Detailed finding tracker](FIX_TRACKER.md)
- [Release test plan](RELEASE_TEST_PLAN.md)