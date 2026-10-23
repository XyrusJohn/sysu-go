const VALID_ROLES = new Set(["driver", "fleet_manager", "qr_generator", "admin", "super_admin"]);
const DRIVER_EVENTS = new Set(["update_status", "driver_moving", "driver_stopped", "driver_status_change", "request_driver_status", "heartbeat", "driver_is_full", "pod_updated"]);
const REALTIME_EVENTS = new Set(["realtime:subscribe", "realtime:unsubscribe"]);
const DRIVER_ACTION_SYNC = "driver_actions:sync";
const DRIVER_ACTION_READS = new Set(["driver_actions:get_sessions", "driver_actions:get_events"]);
const STAFF_ROLES = new Set(["fleet_manager", "admin", "super_admin"]);
const {DRIVER_ACTION_EVENT_TYPES: ACTION_TYPES} = require("../constants/driverActionEvents");
const ACTION_UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const UPDATE_STATUSES = new Set(["online", "offline", "stopped", "completed"]);
const LIFECYCLE_STATUSES = new Set(["online", "offline", "in_transit", "returning_to_base"]);
const fail = message => Object.assign(new Error(message), { data: { code: "SOCKET_UNAUTHORIZED" } });
const validCoordinates = (lat, lng) => Number.isFinite(lat) && Number.isFinite(lng) && lat >= -90 && lat <= 90 && lng >= -180 && lng <= 180 && lat !== 0 && lng !== 0;

function createSocketAuth({ getFirebaseAuth, getDb }) {
  async function identity(token) {
    if (typeof token !== "string" || token.length < 20 || token.length > 8192) throw fail("Authentication required");
    const decoded = await getFirebaseAuth().verifyIdToken(token, true);
    if (typeof decoded.uid !== "string" || !decoded.uid || !Number.isFinite(decoded.exp)) throw fail("Authentication required");
    const snapshot = await getDb().collection("users").doc(decoded.uid).get();
    const profile = snapshot.exists ? snapshot.data() : null;
    if (!profile || profile.status !== "active" || !VALID_ROLES.has(profile.role)) throw fail("Account not authorized");
    return { uid: decoded.uid, role: profile.role, exp: decoded.exp };
  }

  return async function authenticate(socket, next) {
    try {
      const principal = await identity(socket.handshake?.auth?.token);
      socket.data.uid = principal.uid;
      socket.data.role = principal.role;
      socket.data.token = socket.handshake.auth.token;
      socket.data.tokenExpiresAt = principal.exp * 1000;
      socket.use(async (packet, done) => {
        try {
          const current = await identity(socket.data.token);
          if (current.uid !== socket.data.uid || current.role !== socket.data.role) throw fail("Account authorization changed");
          const [event, payload] = packet;
          if (REALTIME_EVENTS.has(event)) {
            if (current.role !== "driver") throw fail("Socket subscription not authorized");
            if (!payload || typeof payload !== "object" || Array.isArray(payload)
              || Buffer.byteLength(JSON.stringify(payload)) > 1024) throw fail("Invalid socket subscription");
            if (event === "realtime:subscribe" && (typeof payload.id !== "string"
              || !/^[A-Za-z0-9_-]{1,80}$/.test(payload.id)
              || !["profile", "driver", "queue", "queuePosition", "assignments", "location", "securityGuards", "truckCompanies"].includes(payload.topic))) {
              throw fail("Invalid socket subscription");
            }
            if (event === "realtime:unsubscribe" && (typeof payload.id !== "string"
              || !/^[A-Za-z0-9_-]{1,80}$/.test(payload.id))) throw fail("Invalid socket subscription");
            done();
            return;
          }
          if (event === DRIVER_ACTION_SYNC) {
            if (current.role !== "driver" || !validDriverActionBatch(payload)) throw fail("Activity sync not authorized");
            done();
            return;
          }
          if (DRIVER_ACTION_READS.has(event)) {
            if (!STAFF_ROLES.has(current.role) || !validDriverActionRead(payload, event === "driver_actions:get_events")) {
              throw fail("Activity history not authorized");
            }
            done();
            return;
          }
          if (!DRIVER_EVENTS.has(event) || current.role !== "driver") throw fail("Socket event not authorized");
          if (payload != null && (typeof payload !== "object" || Array.isArray(payload) || Buffer.byteLength(JSON.stringify(payload)) > 4096)) throw fail("Invalid socket payload");
          if (payload?.driverId != null && payload.driverId !== current.uid) throw fail("Cannot act for another driver");
          if (["driver_moving", "driver_stopped"].includes(event) && (!validCoordinates(payload?.lat, payload?.lng) || (event === "driver_moving" && payload.driverId !== current.uid))) throw fail("Invalid driver coordinates");
          if (event === "update_status" && ((payload.lat != null || payload.lng != null) && !validCoordinates(payload.lat, payload.lng))) throw fail("Invalid driver coordinates");
          if (["driver_moving", "driver_stopped", "update_status", "driver_status_change"].includes(event) && payload.name != null && (typeof payload.name !== "string" || payload.name.length > 100)) throw fail("Invalid driver name");
          if (["driver_moving", "driver_stopped"].includes(event) && payload.sapDocId != null && (typeof payload.sapDocId !== "string" || payload.sapDocId.length > 128 || payload.sapDocId.includes("/"))) throw fail("Invalid assignment identifier");
          if (event === "update_status" && !UPDATE_STATUSES.has(payload?.status)) throw fail("Invalid driver status");
          if (event === "driver_status_change" && !LIFECYCLE_STATUSES.has(payload?.status)) throw fail("Invalid driver status");
          if (event === "request_driver_status" && payload?.driverId != null && payload.driverId !== current.uid) throw fail("Cannot read another driver status");
          if (event === "driver_is_full" && typeof payload?.isFull !== "boolean") throw fail("Invalid capacity state");
          if (event === "pod_updated" && (typeof payload?.shipmentDocId !== "string" || payload.shipmentDocId.length < 1 || payload.shipmentDocId.length > 128 || payload.shipmentDocId.includes("/") || payload.source !== "driver_app")) throw fail("Invalid POD event");
          done();
        } catch (error) {
          socket.disconnect(true);
          done(fail("Socket authorization required"));
        }
      });
      if (typeof socket.once === "function") {
        const monitor = setInterval(async () => {
          try {
            const current = await identity(socket.data.token);
            if (current.uid !== socket.data.uid || current.role !== socket.data.role) socket.disconnect(true);
          } catch (_) { socket.disconnect(true); }
        }, 30000);
        monitor.unref?.();
        socket.once("disconnect", () => clearInterval(monitor));
      }
      next();
    } catch (_) {
      next(fail("Socket authentication required"));
    }
  };
}

function validDriverActionBatch(payload) {
  if (!payload || typeof payload !== "object" || Array.isArray(payload)
    || Buffer.byteLength(JSON.stringify(payload)) > 64 * 1024
    || !ACTION_UUID.test(payload.sessionId || "")
    || Object.hasOwn(payload, "driverId")
    || !Array.isArray(payload.events) || payload.events.length < 1 || payload.events.length > 50) return false;
  let priorSequence = 0;
  for (const item of payload.events) {
    if (!item || typeof item !== "object" || Array.isArray(item)
      || !ACTION_UUID.test(item.eventId || "")
      || (item.sessionId != null && item.sessionId !== payload.sessionId)
      || !Number.isSafeInteger(item.sequence) || item.sequence <= priorSequence
      || !ACTION_TYPES.has(item.eventType)
      || !Number.isSafeInteger(item.occurredAtMs)
      || !["available", "unavailable", "permission_denied"].includes(item.locationState)
      || ((item.lat == null) !== (item.lng == null))
      || (item.locationState === "available" && (typeof item.lat !== "number" || typeof item.lng !== "number"))
      || (item.locationState !== "available" && item.lat != null)
      || (item.lat != null && (!Number.isFinite(item.lat) || item.lat < -90 || item.lat > 90
        || !Number.isFinite(item.lng) || item.lng < -180 || item.lng > 180))
      || (item.accuracy != null && (!Number.isFinite(item.accuracy) || item.accuracy < 0 || item.accuracy > 5000))
      || (item.subjectId != null && (typeof item.subjectId !== "string" || item.subjectId.length > 128 || /[/\\]/.test(item.subjectId)))
      || typeof item.outcome !== "string" || item.outcome.length > 40
      || (item.durationMs != null && (!Number.isSafeInteger(item.durationMs) || item.durationMs < 0))
      || (item.metadata != null && (!item.metadata || typeof item.metadata !== "object" || Array.isArray(item.metadata)
        || Buffer.byteLength(JSON.stringify(item.metadata)) > 1024))) return false;
    priorSequence = item.sequence;
  }
  return true;
}

function validDriverActionRead(payload, needsSession) {
  return payload && typeof payload === "object" && !Array.isArray(payload)
    && typeof payload.driverId === "string" && /^[A-Za-z0-9_-]{1,128}$/.test(payload.driverId)
    && (!needsSession || ACTION_UUID.test(payload.sessionId || ""))
    && (payload.limit == null || Number.isSafeInteger(payload.limit) && payload.limit >= 1 && payload.limit <= 500)
    && (payload.cursor == null || Number.isSafeInteger(payload.cursor) && payload.cursor >= 0)
    && Buffer.byteLength(JSON.stringify(payload)) <= 2048;
}

module.exports = { createSocketAuth, VALID_ROLES, DRIVER_EVENTS };
