# SYSU Go

**Stanley University Transportation — Driver Tracking System**

Cross-platform solution for real-time truck driver tracking, consisting of an Android mobile app, Node.js backend, and React admin dashboard.

---

## System Overview

The SYSU Go system is designed to provide real-time tracking of truck drivers. It comprises three main components: an Android mobile application for drivers, a Node.js backend server, and a React-based administration dashboard.

The **Android App** (Driver) communicates with the **Backend** via RESTful HTTP APIs for authentication and trip management, and uses Socket.IO for real-time GPS streaming. The **Backend** acts as a central hub, managing driver data, trip information, and GPS logs, persisting data in a **Firebase Firestore** database. It also facilitates real-time communication with the **Admin Dashboard** using Socket.IO, enabling live monitoring of driver locations and statuses. Authentication is handled by **Firebase Authentication**.

```
┌─────────────────────────────────────────────────────────────────┐
│                        SYSU Go Architecture                     │
└─────────────────────────────────────────────────────────────────┘

       DRIVER                         BACKEND                       ADMIN
  ┌──────────────┐               ┌──────────────┐               ┌──────────┐
  │ Android App  │──────────────►│  Express +   │◄──────────────│  Vanilla │
  │  (Kotlin)    │   REST/HTTP   │  Socket.IO   │  Socket.IO    │ Dashboard│
  │              │ Firebase Auth │   Server     │               │          │
  │ - Login      │◄──────────────│              │──────────────►│ - Live   │
  │ - Track Trip │               │ - Auth API   │               │   Map    │
  │ - GPS Stream │               │ - Trip API   │               │ - Status │
  └──────────────┘               │ - GPS API    │               └──────────┘
                                 │              │
                                 │  Firebase    │
                                 │  Firestore   │
                                 └──────────────┘
```

## System Design Principles

*   **Real-time Communication**: Utilizes Socket.IO for instant, bi-directional communication between the driver app, backend, and admin dashboard, enabling live location tracking and status updates.
*   **Modularity**: The system is composed of distinct, independently deployable components (Android App, Backend, Admin Dashboard), promoting easier development, testing, and scaling.
*   **Scalability**: The Node.js backend is designed to handle a growing number of concurrent connections and data throughput, supported by a robust Firebase Firestore database.
*   **Security**: Implements **Firebase Authentication** for secure access to API resources and protects sensitive data through encrypted storage and secure password hashing.
*   **Offline-first (Driver App)**: The Android application is designed to function reliably even with intermittent network connectivity, caching data locally and synchronizing when online.

## Repository Structure

```
sysu-go/
│
├── README.md               ← You are here (system overview)
│
├── backend/                ← Node.js / Express REST + Socket.IO server
│   └── README.md           ← Full backend documentation
│       ├── API Endpoints
│       ├── Authentication
│       ├── Socket.IO Events
│       └── Database Schema
│
├── app/                   ← Android mobile app (Kotlin)
│   └── README.md           ← Full Android app documentation
│       ├── Architecture
│       ├── User Flows
│       ├── Permissions
│       └── API Integration
│
├── free-claude-code/      ← Claude Code CLI configuration (internal)
│
└── docs/                  ← Architecture docs, learnings, flows
```

---

## Technology Stack

| Layer | Technology | Purpose |
|-------|------------|---------|
| **Mobile App** | Kotlin / Android | Driver interface |
| **Backend** | Node.js / Express | REST API + Socket.IO |
| **Real-time** | Socket.IO 4.x | Live location streaming |
| **Database** | Firebase Firestore | Driver data, trips, GPS logs |
| **Auth** | Firebase Authentication | User authentication and authorization |
| **Passwords** | bcrypt | Secure password hashing (for initial driver setup, Firebase handles user passwords) |
| **Dashboard** | React (future) | Admin map + status view |

---

## Features

### Driver App (Android)
- Username/password login with encrypted token storage
- Real-time GPS tracking with throttling (10s interval) and drift filter (30m)
- Background location service (survives app minimize)
- Live location streaming via Socket.IO to admin dashboard
- Trip management (start/stop tracking)
- Offline-aware with background sync (WorkManager)
- Network status monitoring with auto online/offline emission

### Backend (Node.js)
- Firebase Authentication integration for user management
- Driver registration and login
- Trip creation and management
- GPS batch upload
- Socket.IO server for real-time events

### Admin Dashboard (React — future)
- Live map with driver positions
- Driver online/offline status board
- Trip history and logs

---

## Database

### Backend (Firebase Firestore)
- **drivers** — registration, credentials, profile
- **trips** — trip records with start/stop
- **gps_logs** — batch-uploaded location points
- **qr_tokens** — (as seen in `firebaseAdmin.js` test)

*(See `backend/README.md` for more details on data models)*

### Android App
- **Room Database** — offline GPS log and trip caching
- **Encrypted SharedPreferences** — secure token storage via AndroidX Security Crypto

*(See `app/README.md` for full schema)*

---

## Quick Start

### Prerequisites
- Node.js 18+
- Android Studio Hedgehog+
- Firebase Project with Firestore and Authentication enabled
- Android device or emulator (API 24+)

### 1. Backend

```bash
cd backend
npm install
cp .env.example .env
# Configure FIREBASE_SERVICE_ACCOUNT_JSON with your Firebase service account key JSON string
npm run dev
```

### 2. Android App

```bash
cd app
# Open in Android Studio
# Sync Gradle
# Run on device/emulator
# Update RetrofitClient.Constants.BASE_URL for your network
```

### 3. Frontend (future)

```bash
cd frontend
npm install
npm run dev
```

---

## Environment Variables

| Variable | Backend | App | Description |
|----------|---------|-----|-------------|
| `FIREBASE_SERVICE_ACCOUNT_JSON` | ✅ | | Firebase service account key JSON string (for backend access to Firestore/Auth) |
| `PORT` | ✅ | | Server port (default: `8080`) |
| `BASE_URL` | | ✅ | Backend URL for Retrofit |
| `FRONTEND_URL` | ✅ | | URL of the frontend application for CORS (e.g., `https://sysu-web-5a25d.web.app`) |
| `ALLOWED_ORIGINS` | ✅ | | Comma-separated list of additional allowed CORS origins |

---

## Network Ports

The SYSU Go system utilizes the following network ports for communication:

*   **Backend Server (Node.js)**:
    *   **HTTP/REST API**: Default port `8080` (configurable via `PORT` environment variable). This port is used for all RESTful API interactions (e.g., authentication, trip management, GPS batch uploads).
    *   **Socket.IO**: Operates on the same port as the HTTP/REST API (`8080` by default) for real-time, bi-directional communication (e.g., live GPS streaming, status updates).

*   **Android App**: Connects to the backend server's HTTP/REST API and Socket.IO endpoint using the configured `BASE_URL`.

*   **Admin Dashboard**: Connects to the backend server's Socket.IO endpoint for real-time data display.

---

## API Base URLs

| Environment | URL |
|-------------|-----|
| Local Backend | `http://192.168.137.177:8080` |
| Production | Configure accordingly |

---

## Documentation

| Document | Description |
|----------|-------------|
| [`backend/README.md`](backend/README.md) | Full backend API docs, endpoints, Socket.IO |
| [`app/README.md`](app/README.md) | Full Android app architecture, flows, setup |
| [`backend/docs/`](backend/docs/) | Architecture notes and flow diagrams |

---

## Current Status

| Component | Status | Notes |
|-----------|--------|-------|
| Backend | ✅ Complete | Firebase Firestore and Authentication integrated |
| Android App | ✅ Complete | GPS + Socket.IO working |
| Admin Dashboard | 🚧 Planned | React frontend |
| Website (sysu-web) | 🚧 Planned | Public-facing site |

---

## License

ISC