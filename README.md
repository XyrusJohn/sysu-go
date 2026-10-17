# SYSU Go

**Sysu International - Driver Tracking System**

SYSU Go is the Android driver application and supporting Node.js/Firebase backend. The web dashboard, kiosk, SAP assignment view, and maps are maintained in the sibling `sysu-app` project.

---

## System Overview

SYSU Go covers Android driver workflows and the supporting backend. The Android app uses Firebase Authentication, HTTPS APIs, and Socket.IO. The backend uses Firebase Admin/Firestore and Socket.IO. The web dashboard and kiosk are in the sibling `sysu-app` repository; this README does not describe that repository's implementation.

See [the Android guide](docs/android-app-guide.md), [backend API guide](docs/backend-api-guide.md), and [documentation index](docs/README.md).

## System Design Principles

- **Identity:** Protected backend operations verify Firebase ID tokens and active user profiles.
- **Isolation:** Use the explicit `emulator` Android variant and guarded demo Firebase configuration for local integration work.
- **Cross-project boundary:** SYSU Go owns driver-facing Android flows and its mobile backend; SYSU App owns the web dashboard and SAP service.
- **Acceptance:** Local tests do not establish deployed rules, production behavior, or actual-device reliability. See the current [project status](docs/project-status-summary.md).

## Repository Structure

```text
sysu-go/
|-- app/                 Kotlin Android application
|-- backend/             Express, Firebase Admin, and Socket.IO service
|-- docs/                Current guides, status, test evidence, and archived plans
|-- gradle/              Android build and dependency configuration
`-- firebase.json        Firebase emulator/deployment configuration
```

The former `app/README.md` and `backend/README.md` content has been consolidated into the linked guides under `docs/`.

## Technology Stack

| Layer | Technology | Evidence |
|---|---|---|
| Android | Kotlin, Android Gradle Plugin | `app/build.gradle.kts` |
| Backend | Node.js 24, Express, Firebase Admin | `backend/package.json` and `backend/server.js` |
| Identity/data | Firebase Authentication and Firestore | Android Firebase clients and `backend/config/firebaseAdmin.js` |
| Realtime | Socket.IO | Android `SocketManager` and backend `server.js` |
| QR camera | CameraX and ML Kit | `app/build.gradle.kts` |

The sibling web dashboard is documented by the `sysu-app` repository.

## Features

### Android driver application

- Driver account/profile, QR queue admission, trip registration and progress.
- Location/status updates through Firebase and authenticated Socket.IO flows.
- POD capture and progress submission through the backend.

### Mobile backend

- Firebase ID-token and active-profile checks on protected operations.
- Role-scoped QR generation, queue access, and status changes.
- Owned trip registration and POD progress routes.
- Authenticated, validated driver socket events and staff-scoped tracking broadcasts.

See the [route guide](docs/backend-api-guide.md) for the reviewed HTTP contract and its source references. Physical-device acceptance remains tracked separately.

## Database

The backend and Android app use Firebase Authentication and Firestore. The inspected backend paths include `users`, `drivers`, `queue`, `qr_tokens`, `qr_admissions`, and `sap_assignments`; POD image records are stored beneath assignments. The checked-in index configuration is [firestore.indexes.json](firestore.indexes.json). Neither that file nor local tests prove deployed rules or indexes.

Android uses Firebase SDKs and app-managed session state. This repository does not declare Room or WorkManager dependencies for the app; do not rely on the former offline database/sync claims.

## Quick Start

### Prerequisites

- Node.js 24 for the backend.
- Android Studio and the JDK version supported by the checked-in Gradle setup.
- Firebase emulators for isolated backend integration tests.

### Backend (isolated local work)

Use the demo project and loopback emulator values in `backend/.env.example`, then follow [LOCAL_EMULATOR_SETUP.md](docs/LOCAL_EMULATOR_SETUP.md). From `backend/`, install from the lockfile and run `npm run dev` only after the emulator configuration is confirmed.

### Android (isolated local work)

Open the repository in Android Studio, select the `emulator` build variant, and confirm its demo project and local endpoints. Follow [LOCAL_EMULATOR_SETUP.md](docs/LOCAL_EMULATOR_SETUP.md) for the current commands. The default `debug` variant is configured with hosted endpoints; never treat the default variant as an isolated test target.

## Configuration

| Variable | Use |
|---|---|
| `SYSU_ENV` | Explicit backend environment (`emulator`, `staging`, or `production`) |
| `FIREBASE_PROJECT_ID` | Project asserted by guarded backend initialization |
| `FIRESTORE_EMULATOR_HOST` | Firestore emulator host for isolated tests |
| `FIREBASE_AUTH_EMULATOR_HOST` | Auth emulator host for isolated tests |
| `FIREBASE_SERVICE_ACCOUNT_JSON` | Approved non-emulator backend credential input, when required |
| `PORT` | Backend HTTP/Socket.IO listener port |

Use the demo project and loopback hosts for local emulator work; do not provide service-account credentials in emulator mode. Non-emulator configuration must match its Firebase project and environment. The backend serves HTTP and Socket.IO on one listener. Android API and socket URLs come from the selected Gradle build variant. See [local emulator setup](docs/LOCAL_EMULATOR_SETUP.md) for the exact values and checks.

## Documentation

| Document | Purpose |
|---|---|
| [Documentation index](docs/README.md) | Maintained guides and historical material |
| [Android app guide](docs/android-app-guide.md) | Source-checked Android behavior and limits |
| [Backend API guide](docs/backend-api-guide.md) | Current route and socket contract |
| [Local emulator setup](docs/LOCAL_EMULATOR_SETUP.md) | Isolated configuration and test setup |
| [Project status](docs/project-status-summary.md) | Current acceptance summary |
| [Fix tracker](docs/FIX_TRACKER.md) | Detailed release findings and gates |
| [Release test plan](docs/RELEASE_TEST_PLAN.md) | Verification strategy and release criteria |

For current acceptance state and release limitations, start with [project status](docs/project-status-summary.md) and [fix tracker](docs/FIX_TRACKER.md). Local test results and a controlled beta do not establish public release readiness; deployment, device, signing, security, and rollback gates require their own evidence.
