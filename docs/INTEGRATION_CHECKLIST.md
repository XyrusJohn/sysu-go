# SYSU Go Mobile App — Integration Checklist

> **Generated:** 2026-08-01  
> **Branch:** `main`  
> **Purpose:** Modular checklist for cross-team integration (Mobile ↔ Sysu Web App)  

---

## 1. 🇵🇭 Tagalog Localization (Notifications)

All user-facing notifications, toast messages, dialog texts, and navbar overlays translated to Filipino.

| # | Item | Status | Notes |
|---|------|--------|-------|
| 1.1 | `strings.xml` — all resource strings in Tagalog | ✅ DONE | Includes CTA, QR scan, login, registration, trip, POD, permissions, assignment, cancel queue, return-to-base |
| 1.2 | QR tutorial overlay text | ✅ DONE | `activity_main.xml` — "Paano Mag-Check In" / "Nakuha Ko Na!" |
| 1.3 | Nav tutorial overlay text | ✅ DONE | `activity_main.xml` — "Iyong Navbar" / each button described in Tagalog |
| 1.4 | LocationServices foreground notification | ✅ DONE | "Aktibo ang SYSU Tracking" / "Ipinapadala ang iyong lokasyon sa dispatch" |
| 1.5 | Hardcoded Toast messages in `MainActivity` | ✅ DONE | Welcome, queue, start-trip, OBD toasts |
| 1.6 | Hardcoded Toast messages in `TransitActivity` | ✅ DONE | Start/end trip, return-to-base, POD blocking |
| 1.7 | `tvScanHint` banner text | ✅ DONE | "Ipakita ang QR code sa booth para mag-check in" |

---

## 2. 🧹 Sanitizer — Middle Initial Fix

Prevents double-dot middle initial patterns like `"Carlo B.. Locar"`.

| # | Item | Status | Notes |
|---|------|--------|-------|
| 2.1 | `Sanitizer.sanitizeDisplayName()` | ✅ DONE | Collapses `\.{2,}` → `.`, squashes extra whitespace, trims trailing dot |
| 2.2 | Applied in `SessionManager` save/read paths | ✅ DONE | `saveDriverSession()`, `saveDriverSessionWithSplit()`, `getDriverName()` |
| 2.3 | `ProfileActivity` sync applies clean name | ✅ DONE | Rebuilds from FN/MI/LN parts on Firestore sync |

---

## 3. 🚛 Trucking Company Display Fix

Display company name without forwarder number / truck company number. ALL CAPS + bold.

| # | Item | Status | Notes |
|---|------|--------|-------|
| 3.1 | `Sanitizer.sanitizeCompanyName()` | ✅ DONE | Strips `001-` / `045— ` / raw number prefixes → uppercase result |
| 3.2 | Pattern: `"001-LAZADA SHIPPING"` → `"LAZADA SHIPPING"` | ✅ DONE | Regex: `"""^\d+\s*[-—–]+\s*""".replace("")` then `.uppercase()` |
| 3.3 | Fallback: if all digits stripped leaves empty, returns uppercased original | ✅ DONE | `.ifBlank { input.trim().uppercase() }` |
| 3.4 | `ProfileActivity.tvCompany` layout — ALL CAPS + bold | ✅ DONE | `textAllCaps="true"` + `textStyle="bold"` + `fontFamily="atkinson_hyperlegible_bold"` |
| 3.5 | `ProfileActivity` reads apply `sanitizeCompanyName` | ✅ DONE | Both session-init path + Firestore callback path |
| 3.6 | `MainActivity` stores apply `sanitizeCompanyName` | ✅ DONE | `syncProfileWithFirestore()` + `enrichAssignmentsWithDriverData()` |

---

## 4. 📍 Strict GPS Geo-Tracking (Start → Arrival Only)

GPS tracking activates only when driver clicks Start Trip, and stops immediately when reaching warehouse (returning_to_base / completed). No stale emits leak.

| # | Item | Status | Notes |
|---|------|--------|-------|
| 4.1 | Tracking: ON = `TransitActivity.startTrip()` → `sessionManager.setTrackingActive(true)` | ✅ DONE | Line 379, immediately after `saveCurrentSapDocId()` |
| 4.2 | Tracking: OFF = `TransitActivity.performEndTrip()` → `sessionManager.setTrackingActive(false)` + `stopService(LocationServices)` | ✅ DONE | Lines 473-486, BEFORE stopService so the auto-restart does NOT re-enable GPS |
| 4.3 | Tracking: OFF = `TransitActivity.transitionToReturningBase()` → same stop pattern | ✅ DONE | Previously missing — now added. Fires on warehouse arrival. |
| 4.4 | LocationServices guard: re-checks `isTrackingActive()` on EVERY callback | ✅ DONE | `handleLocation()` line 1 guard: if `!isTrackingActive()`, return silently |
| 4.5 | LocationServices guard: `onStartCommand` only starts GPS if `tracking_active=TRUE` | ✅ DONE | Pre-existing, hardened by 4.4 |
| 4.6 | Offline emit on trip end / return-to-base | ✅ DONE | `SocketManager.emitOfflineStatus()` → sysu-app map scar marker immediately |

---

## 5. ✅ Overall Integration Status

| Feature | Status | Blocks Web Integration? |
|---------|--------|------------------------|
| Tagalog notifications | ✅ COMPLETE | No |
| Middle Initial fix | ✅ COMPLETE | No |
| Trucking company display | ✅ COMPLETE | No |
| Strict geo-tracking | ✅ COMPLETE | Yes — sysu-app Railway map depends on this |
| Modular checklist | ✅ COMPLETE | This file |

---

## 6. file/docs

| File | Change Type |
|------|-------------|
| `res/values/strings.xml` | Full rewrite — Tagalog translations |
| `res/layout/activity_main.xml` | Scan hint, QR tutorial, Nav tutorial → Tagalog |
| `res/layout/activity_profile.xml` | `tvCompany` → ALL CAPS + bold |
| `utils/Sanitizer.kt` | New: `sanitizeCompanyName()` and `sanitizeDisplayName()` (from previous commit) |
| `utils/SessionManager.kt` | Previously updated with `sanitizeDisplayName` |
| `services/LocationServices.kt` | Strict guard in `handleLocation()`; Tagalog notification text |
| `activities/TransitActivity.kt` | `transitionToReturningToBase()` stops GPS; Tagalog toasts |
| `activities/MainActivity.kt` | Tagalog toasts; company sanitization apply |
| `activities/ProfileActivity.kt` | Added `Sanitizer` import; `sanitizeCompanyName` on both read paths |