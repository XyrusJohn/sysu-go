/**
 * Presence watcher — authoritative server-side driver online/offline detector.
 *
 * Why this exists:
 *   Socket.IO's EVENT_DISCONNECT doesn't always fire promptly when a driver's
 *   network goes down (especially DNS-broken / captive-portal scenarios on
 *   Android). The driver's TCP socket can hang in the kernel without ever
 *   raising a FIN, so the server thinks the driver is still "online" until
 *   pingTimeout fires (default 30s in our config — sometimes much longer).
 *
 *   The fix: the client emits a `heartbeat` event every ~15s while online.
 *   We track `lastSeen` per driver. If lastSeen is older than HEARTBEAT_TIMEOUT_MS,
 *   we declare the driver OFFLINE — write "offline" to drivers/{uid}.status
 *   in Firestore and broadcast driver_status_change to room driver:{uid} and
 *   globally so all subscribers update in real-time.
 *
 * This is the same model Slack / Discord / WhatsApp use for online presence:
 *   "online" is a function of recent activity, not a state the client announces.
 *
 * Implementation:
 *   - Runs on a setInterval (every CHECK_INTERVAL_MS)
 *   - Iterates onlineDriver Map (defined in server.js)
 *   - For each stale driver: removes from map, persists to Firestore, broadcasts
 *
 * Tuning:
 *   - HEARTBEAT_INTERVAL_MS (client): how often the client pings (15s)
 *   - HEARTBEAT_TIMEOUT_MS (server): how long until we declare offline (45s = 3× interval)
 *   - CHECK_INTERVAL_MS (this file): how often we scan for stale drivers (15s)
 */

const { getDb } = require("../config/firebaseAdmin.js");

// 45s = 3× the 15s client heartbeat interval. Three missed pings → offline.
// Generous enough to ride out brief network blips without false positives.
const HEARTBEAT_TIMEOUT_MS = 45_000;
// Scan every 15s — same cadence as the client heartbeat.
const CHECK_INTERVAL_MS = 15_000;

let intervalId = null;

/**
 * Log helper with timestamp — matches server.js convention.
 */
const ts = () => new Date().toISOString().replace("T", " ").substring(0, 19);

/**
 * Scan onlineDriver for stale entries and mark them OFFLINE.
 *
 * @param {Map} onlineDriver — driverId → { name, lat, lng, socketIds, status, lastSeen }
 * @param {object} io — Socket.IO server instance (for broadcasts)
 */
async function tick(onlineDriver, io) {
  if (!onlineDriver || onlineDriver.size === 0) return;

  const now = Date.now();
  const staleDrivers = [];

  for (const [uid, entry] of onlineDriver.entries()) {
    if (!entry.lastSeen) {
      // Defensive — entry without lastSeen (shouldn't happen with new code, but
      // covers pre-existing map entries from before this watcher landed).
      entry.lastSeen = now;
      continue;
    }
    const ageMs = now - entry.lastSeen;
    if (ageMs > HEARTBEAT_TIMEOUT_MS) {
      staleDrivers.push({ uid, entry, ageMs });
    }
  }

  if (staleDrivers.length === 0) return;

  console.log(`[${ts()}] [PRESENCE] Found ${staleDrivers.length} stale driver(s) (lastSeen > ${HEARTBEAT_TIMEOUT_MS / 1000}s ago)`);

  for (const { uid, entry, ageMs } of staleDrivers) {
    const ageSec = Math.round(ageMs / 1000);
    console.log(`[${ts()}] [PRESENCE] ⚠️  Driver ${entry.name} (${uid}) stale: lastSeen ${ageSec}s ago — marking OFFLINE`);

    // Centralised offline-protection check via Firestore
    let active = false;
    try {
      const db = getDb();
      const snap = await db
        .collection("sap_assignments")
        .where("driverId", "==", uid)
        .where("tripStatus", "not-in", ["completed"])
        .limit(1)
        .get();
      active = !snap.empty;
    } catch (err) {
      console.error(`[${ts()}] [PRESENCE] Firestore error checking active shipment for ${uid}:`, err.message);
      active = true; // Safe fallback
    }

    if (active && entry.lat != null && entry.lng != null) {
      // Mark offline in-place — keep lat/lng for web frontend
      entry.isOffline = true;
      entry.offlineSince = Date.now();
      entry.socketIds.clear();

      // Persist OFFLINE to Firestore (still important for audit trail)
      try {
        const db = getDb();
        await db.collection("drivers").doc(uid).set(
          { status: "offline_in_transit", lastSeenOfflineAt: new Date(), lastKnownLat: entry.lat, lastKnownLng: entry.lng },
          { merge: true }
        );
        console.log(`[${ts()}] [PRESENCE] ✓ Firestore: drivers/${uid}.status = "offline_in_transit" (last pos retained)`);
      } catch (err) {
        console.error(`[${ts()}] [PRESENCE] ✗ Firestore write failed for ${uid}:`, err.message);
      }

      // Broadcast offline_in_transit so sysu-web shows grey marker at last known position
      if (io) {
        const payload = {
          driverId: uid,
          status: "offline_in_transit",
          name: entry.name,
          lat: entry.lat,
          lng: entry.lng,
          offlineSince: entry.offlineSince,
          timestamp: Date.now(),
          source: "presence_watcher",
          staleAgeMs: ageMs,
        };
        io.to(`driver:${uid}`).emit("driver_status_change", { ...payload, status: "offline" });
        io.emit("driver_status_change", { ...payload, status: "offline" });
        io.emit("vendor_status_update", payload);
        console.log(`[${ts()}] [PRESENCE] ✓ Broadcast offline_in_transit for ${entry.name} — last pos [${entry.lat}, ${entry.lng}] retained`);
      }
    } else {
      // Not in_transit — full removal from map (same as before)
      onlineDriver.delete(uid);

      // Persist OFFLINE to Firestore
      try {
        const db = getDb();
        await db.collection("drivers").doc(uid).set(
          { status: "offline", lastSeenOfflineAt: new Date() },
          { merge: true }
        );
        console.log(`[${ts()}] [PRESENCE] ✓ Firestore: drivers/${uid}.status = "offline"`);
      } catch (err) {
        console.error(`[${ts()}] [PRESENCE] ✗ Firestore write failed for ${uid}:`, err.message);
      }

      if (io) {
        const payload = {
          driverId: uid,
          status: "offline",
          name: entry.name,
          timestamp: Date.now(),
          source: "presence_watcher",
          staleAgeMs: ageMs,
        };
        io.to(`driver:${uid}`).emit("driver_status_change", payload);
        io.emit("driver_status_change", payload);
        io.emit("vendor_status_update", {
          driverId: uid,
          name: entry.name,
          status: "offline",
          lat: entry.lat,
          lng: entry.lng,
          source: "presence_watcher",
        });
        console.log(`[${ts()}] [PRESENCE] ✓ Broadcast driver_status_change:offline to room driver:${uid} + global`);
      }
    }
  }
}

/**
 * Start the presence watcher.
 *
 * @param {Map} onlineDriver — shared with server.js's io.on("connection")
 * @param {object} io — Socket.IO server instance
 */
function startPresenceWatcher(onlineDriver, io) {
  if (intervalId !== null) {
    console.warn(`[${ts()}] [PRESENCE] Already running — ignoring start`);
    return;
  }
  intervalId = setInterval(() => tick(onlineDriver, io), CHECK_INTERVAL_MS);
  console.log(`[${ts()}] [PRESENCE] ✓ Started — scanning every ${CHECK_INTERVAL_MS / 1000}s, timeout=${HEARTBEAT_TIMEOUT_MS / 1000}s`);
}

function stopPresenceWatcher() {
  if (intervalId !== null) {
    clearInterval(intervalId);
    intervalId = null;
    console.log(`[${ts()}] [PRESENCE] Stopped`);
  }
}

module.exports = {
  startPresenceWatcher,
  stopPresenceWatcher,
  HEARTBEAT_TIMEOUT_MS,
  CHECK_INTERVAL_MS,
};