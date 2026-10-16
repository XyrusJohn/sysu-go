> Historical proposal dated 2026-08-25. It is not an approved/current architecture contract or release-readiness assessment.

# 🚚 SYSU-GO Mobile & Queue Architecture — Engineering Specification

> **Document:** Architecture Redesign, Queue Data Model, Drivers Cleanup, Real-time Map Sync & Release Audit  
> **Target Repositories:** `sysu-go` (Kotlin Mobile App) & `sysu-app` (Web Dashboard & Backend)  
> **Status:** 🚀 **OFFICIAL RELEASE-READY BLUEPRINT & AUDIT SPECIFICATION**  
> **Last Updated:** 2026-08-25  

---

## 1. 🧠 Architectural Analysis: Unbiased Evaluation ng Proposed Design Mo

### 🎯 Ang Naisip Mong Ideya (Proposed Design):
> *Ilagay ang mga assigned shipments (bilang Array / List) nang direkta sa loob ng document ng driver sa `queue` collection. Sa array na ito, makikita kung ilang shipments ang karga niya, at kung ano ang `tripStatus` ng bawat isa. Kapag ang isa sa mga `tripStatus` ay naging `"in_transit"`, automatic na hindi na pwedeng ma-assignan pa ng bagong shipment ang driver.*

---

### ⚖️ Unbiased Pros & Cons Evaluation

| Criteria | 🟢 Mga Magagandang Benepisyo (PROS) | 🔴 Mga Posibleng Peligro / Edge Cases (CONS) |
| :--- | :--- | :--- |
| **1. Single Source of Truth para sa Queue & Dispatch** | **Napakaganda.** Sa halip na mag-cross-query pa ang backend at frontend sa magkahiwalay na `sap_assignments` collection para alamin kung busy ang driver, isang tingin lang sa `queue` document ay alam na agad kung ilan ang karga niya at kung available pa siya. | Kapag nag-update ang status ng isang shipment (hal. na-deliver ang 1st OBD habang in-transit pa ang 2nd OBD), kailangang i-update pareho ang `sap_assignments` doc at ang array sa `queue` doc gamit ang atomic Firestore updates. |
| **2. Pag-alis sa `isAssigned: true` Bottleneck** | **Solved.** Sa halip na static boolean na `isAssigned: true` na sumisira sa subsequent trips, ang basehan ng availability ay: `activeShipmentsCount` at ang estado ng bawat shipment sa array. | Kung magkakaroon ng network disconnection habang nag-a-assign, kailangang siguruhing atomic ang pag-push sa array para maiwasan ang duplicate entries. |
| **3. Real-Time UI Performance (TV, Mobile, Web)** | **Sobrang Bilis.** Ang `MainActivity.kt` sa mobile at ang `dashboard.js` sa web ay mag-li-listen lamang sa iisang snapshot listener ng `queue` doc. Hindi na kailangan ng mabibigat na Firestore composite multi-collection joins. | Limitado sa 1MB ang maximum size ng isang Firestore document (pero dahil 1 hanggang 10 shipments lang naman kada biyahe ang karga ng truck, hindi aabot sa limitasyon). |

### 🏆 Hatol / Rekomendasyon:
**9.5 / 10 — Napakagandang Architectural Improvement.**  
Ito ang magwawakas sa lahat ng desync issues sa pagitan ng `sysu-app` at `sysu-go`.

---

## 2. 🗄️ Firestore Collections Separation of Concerns (`drivers` vs `queue`)

Upang maiwasan ang stale-state bugs sa mga susunod na check-in ng driver, nililinaw natin ang responsibilidad ng bawat collection:

```
┌─────────────────────────────────────────────────────────────┐
│                 1. `drivers/{uid}` (PROFILE LANG)           │
│  - Driver Name, Phone, Truck Type, Plate Number             │
│  - Walang dynamic trip / temporary queue data               │
└──────────────────────────────┬──────────────────────────────┘
                               │
                               ▼ Kapag nag-check in via QR:
┌─────────────────────────────────────────────────────────────┐
│                 2. `queue/{queueDocId}` (ACTIVE TRIP SESSION)│
│  - Lahat ng dynamic data ay DITO LALAGAY:                   │
│    • `isFull: boolean`                                      │
│    • `assignedShipments: [ { obd, tripStatus, ... } ]`      │
│    • `overallTripStatus: "loading" | "in_transit"`          │
│  - Kapag tapos na ang araw/shift (status: "complete"),      │
│    hindi maaapektuhan ang malinis na driver profile.        │
└─────────────────────────────────────────────────────────────┘
```

### A. Bagong `queue` Document Schema Specification
Kapag nag-check in ang driver sa warehouse via QR code, ito ang magiging structure sa `queue/{queueDocId}`:

```json
{
  "id": "queue_doc_12345",
  "driverId": "firebase_auth_uid_abc",
  "driverName": "Juan Dela Cruz",
  "driverPhone": "+639171234567",
  "plateNumber": "ABC-1234",
  "truckType": "6-Wheeler",
  "truckCompany": "SYSU Logistics",
  "entryTime": "2026-08-25T06:00:00.000Z",
  "status": "waiting",

  "isFull": false,
  "activeShipmentsCount": 2,
  "assignedShipments": [
    {
      "dispatchKey": "DSP-20260825-001",
      "obdNumber": "80012345",
      "customerName": "Puregold San Juan",
      "tripStatus": "loaded",
      "assignedAt": "2026-08-25T07:15:00.000Z"
    },
    {
      "dispatchKey": "DSP-20260825-002",
      "obdNumber": "80012346",
      "customerName": "SM Megamall Supermarket",
      "tripStatus": "loading",
      "assignedAt": "2026-08-25T07:30:00.000Z"
    }
  ],

  "overallTripStatus": "loading",
  "guardVerified": false,
  "updatedAt": "2026-08-25T07:30:00.000Z"
}
```

### B. Linis na `drivers` Document Schema (Profile Only)
Ang `drivers/{uid}` collection ay gagamitin na lamang para sa permanenteng profile info:

```json
{
  "uid": "firebase_auth_uid_abc",
  "name": "Juan Dela Cruz",
  "email": "juan@example.com",
  "phone": "+639171234567",
  "licenseNumber": "N01-12-345678",
  "truckType": "6-Wheeler",
  "plateNumber": "ABC-1234",
  "truckCompany": "SYSU Logistics",
  "createdAt": "2026-01-01T00:00:00.000Z",
  "updatedAt": "2026-08-25T06:00:00.000Z"
}
```
*(Tatanggalin at ide-deprecate na ang dynamic client-side `DriverShipmentSync.kt` na nag-dudumi sa `drivers` document).*

---

## 3. 🚦 Consolidated Load & Multi-Drop Lifecycle Rules

### A. Sabay-sabay na Pagiging `in_transit`
Kapag ang driver ay may hawak na 2 o 3 shipments (consolidated load) at lumabas na sa warehouse gate:
* Sa oras na pinindot ang **"Start Trip"** sa mobile:
  * **Automatic na magiging `in_transit` ang LAHAT ng shipments** sa `assignedShipments` array.
  * Ang `overallTripStatus` ay magiging **`in_transit`**.
  * Sa dashboard ng dispatcher, markado ang lahat ng OBDs na nasa biyahe na.

```
[Start Trip / Gate Exit] ──► LAHAT ng assigned OBDs ay magiging "in_transit"
```

### B. Per-Stop Delivery (Multi-Drop Sequence)
1. **1st Drop (SM):** Pagkababa ng karga at pirma sa POD ➔ `OBD #1` = `"completed"`, habang ang `OBD #2` at `#3` ay nananatiling `"in_transit"`.
2. **2nd Drop (Robinsons):** Pagkababa ng karga ➔ `OBD #2` = `"completed"`, habang ang `OBD #3` ay `"in_transit"`.
3. **3rd Drop (Puregold):** Pagkababa ng karga ➔ `OBD #3` = `"completed"`.
4. **All Complete:** Kapag lahat ay `completed` na, mag-a-activate ang *"Return to Base"* trigger.

---

## 4. 🗺️ Strict Real-Time Live Map Marker Lifecycle (Zero-Reload Removal)

Upang matanggal ang **Ghost Marker Bug** nang hindi kailangang mag-refresh ng browser:

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                          1. Sa SYSU-GO (Mobile)                             │
│  - Kapag na-complete ang biyahe / return to base:                           │
│    1. Agad mag-emit sa Socket: `update_status` -> { status: "completed" }   │
│    2. I-stop ang LocationServices GPS updates.                              │
└──────────────────────────────────────┬──────────────────────────────────────┘
                                       │
                                       ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                          2. Sa SYSU-APP (Web Dashboard)                     │
│  - DUAL REMOVAL TRIGGER:                                                    │
│    A. [Socket Channel]: `driverSocket.on("update_status")`                  │
│       -> Kapag `status === "completed"`, tawagin ang `removeMarker(driverId)`│
│    B. [Firestore Snapshot Channel]: `onSnapshot(sap_assignments)`           │
│       -> Kapag ang tripStatus ng driver ay naging "completed",              │
│          automatic ding tawagin ang `removeMarker(driverId)`!                │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 5. 📋 Release-Ready Implementation Checklist (With Status Tracking)

### 📦 Backend (`sysu-app/backend`)
- [ ] **B.1 Update `/api/sap/assign` (`sapAssignmentController.js`):**
  - I-append ang bagong assignment record sa `queue/{queueDocId}.assignedShipments` array gamit ang atomic Firestore transaction.
  - Tanggalin ang pagba-block sa driver na nasa `"loading"` / `"assigned"` status kung `isFull === false`.
- [ ] **B.2 Multi-Shipment Status Cascade (`sapAssignmentController.js`):**
  - Kapag nag-start trip ang driver, tiyakin na lahat ng in-flight assignment docs para sa driver na iyon ay sabay-sabay na nagiging `tripStatus: "in_transit"`.
- [ ] **B.3 Standardize Socket Events (`server.js`):**
  - Magdagdag ng `socket.on("driver_is_full")` handler para suportahan ang real-time capacity updates sa `queue` collection.

### 🖥️ Frontend Web Dashboard (`sysu-app/frontend`)
- [ ] **F.1 Refactor Modal Filter (`dashboard.js` - `getAvailableDriversForModal`):**
  - I-decode ang `truckType` gamit ang `decodeURIComponent`.
  - Tingnan ang `assignedShipments` array sa halip na static boolean:
    - Kung may `in_transit` sa array o `isFull === true` ➔ **I-tago o i-disable sa modal**.
    - Kung `assigned`/`loading`/`loaded` at `isFull === false` ➔ **Payagan mag-assign ng additional OBD**.
- [ ] **F.2 Live Map Marker Cleanup (`MapManager.js` & `dashboard.js`):**
  - Tawagin ang `removeMarker(driverId)` sa oras na maging `completed` ang biyahe sa **Socket listener AT Firestore snapshot listener** (Dual-Channel Removal).
- [ ] **F.3 QR Generator Security (`qr-generator.html`):**
  - Alisin ang hardcoded `STATIC_TOKEN` sa boot flow at magpakita ng spinner bago ang 1st dynamic token generation.

### 📱 Mobile App (`sysu-go/app`)
- [x] **M.1 Clean up `drivers` collection & Deprecate `DriverShipmentSync.kt`:**
  - Tanggalin ang dynamic mirroring sa `drivers/{uid}` doc at panatilihin itong static profile reference lamang. (Done sa `GoApplication.kt`)
- [x] **M.2 Single Firestore Queue Listener (`MainActivity.kt`):**
  - I-bind ang active shipment cards sa `queue/{queueDocId}.assignedShipments` na may automatic sap_assignments sync fallback. (Done)
- [x] **M.3 Standardize Status Strings (`MyTripActivity.kt` & `Assignment.kt`):**
  - Palitan ang `"fully_loaded"` ng standard `"loaded"`. (Done)
- [x] **M.4 Start Trip Multi-OBD Batch Update (`TransitActivity.kt`):**
  - Kapag pinindot ang "Start Trip", sabay-sabay na i-update ang lahat ng assigned shipments sa `"in_transit"`. (Done)
- [x] **M.5 Single Socket Gateway (`SocketManager.kt`):**
  - Tanggalin ang dual connection (`SOCKET_URL_GO` at `SOCKET_URL_APP`) at gumamit lamang ng iisang verified server URL. (Done)
- [x] **M.6 Multi-Drop POD Flow (`MyTripActivity.kt`):**
  - I-update lamang ang specific OBD doc kapag na-capture ang signature/photo, at mag-trigger ng *"Return to Base"* kapag 100% complete na ang lahat ng OBDs. (Done)
- [x] **M.7 Explicit `completed` Emit on Trip End (`ReturnToBaseBottomSheet.kt`):**
  - Magpadala ng `update_status: "completed"` bago i-stop ang GPS service para agad mawala ang marker sa web map. (Done)

---

## 6. 🛠️ Execution Roadmap

```
┌────────────────────────────────────────────────────────────────────────┐
│ Phase 1: Backend API & Transaction Update (sapAssignmentController.js) │
└──────────────────────────────────┬─────────────────────────────────────┘
                                   │
                                   ▼
┌────────────────────────────────────────────────────────────────────────┐
│ Phase 2: Frontend Web Dashboard Modal & Map Marker Sync (dashboard.js) │
└──────────────────────────────────┬─────────────────────────────────────┘
                                   │
                                   ▼
┌────────────────────────────────────────────────────────────────────────┐
│ Phase 3: Mobile sysu-go Cleanup (Deprecate DriverShipmentSync & Sync)  │
└────────────────────────────────────────────────────────────────────────┘
```
