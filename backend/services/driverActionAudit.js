const {Timestamp} = require("firebase-admin/firestore");

const MAX_BATCH = 50;
const MAX_PAGE = 500;
const RETENTION_MS = 90 * 24 * 60 * 60 * 1000;
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const EVENT_TYPES = new Set([
  "app_open", "qr_scan", "trip_registration", "queue_joined", "shipment_assigned",
  "loading_started", "fully_loaded", "guard_verified", "transit_started", "route_point",
  "unloading_started", "pod_captured", "returning_to_base", "arrived_at_warehouse",
]);
const LOCATION_STATES = new Set(["available", "unavailable", "permission_denied"]);
const STAFF_ROLES = new Set(["fleet_manager", "admin", "super_admin"]);

function validateEvent(event, now = Date.now()) {
  if (!event || typeof event !== "object" || Array.isArray(event)) return "Invalid activity event";
  if (typeof event.eventId !== "string" || !UUID.test(event.eventId)) return "Invalid event ID";
  if (!Number.isSafeInteger(event.sequence) || event.sequence < 1) return "Invalid event sequence";
  if (typeof event.eventType !== "string" || !EVENT_TYPES.has(event.eventType)) return "Unsupported event type";
  if (!Number.isSafeInteger(event.occurredAtMs) || event.occurredAtMs > now + 5 * 60 * 1000) return "Invalid event time";
  if (!LOCATION_STATES.has(event.locationState)) return "Invalid location state";
  if ((event.lat == null) !== (event.lng == null)) return "Incomplete coordinates";
  if (event.locationState === "available" && (event.lat == null || event.lng == null)) return "Missing coordinates";
  if (event.locationState !== "available" && event.lat != null) return "Coordinates conflict with location state";
  if (event.lat != null && (!Number.isFinite(event.lat) || event.lat < -90 || event.lat > 90
    || !Number.isFinite(event.lng) || event.lng < -180 || event.lng > 180)) return "Invalid coordinates";
  if (event.accuracy != null && (!Number.isFinite(event.accuracy) || event.accuracy < 0 || event.accuracy > 5000)) return "Invalid accuracy";
  if (event.durationMs != null && (!Number.isSafeInteger(event.durationMs) || event.durationMs < 0 || event.durationMs > RETENTION_MS)) return "Invalid duration";
  if (event.subjectId != null && (typeof event.subjectId !== "string" || event.subjectId.length > 128 || /[/\\]/.test(event.subjectId))) return "Invalid activity reference";
  if (typeof event.outcome !== "string" || event.outcome.length > 40) return "Invalid outcome";
  if (event.metadata != null) {
    if (!event.metadata || typeof event.metadata !== "object" || Array.isArray(event.metadata)
      || Buffer.byteLength(JSON.stringify(event.metadata)) > 1024) return "Invalid activity metadata";
    for (const [key, value] of Object.entries(event.metadata)) {
      if (!/^[A-Za-z][A-Za-z0-9_]{0,39}$/.test(key)
        || !["string", "number", "boolean"].includes(typeof value)
        || (typeof value === "string" && value.length > 256)
        || (typeof value === "number" && !Number.isFinite(value))) return "Invalid activity metadata";
    }
  }
  return null;
}

function bindDriverActionAudit(socket, {db, io}) {
  const uid = socket.data.uid;
  const role = socket.data.role;
  const ack = (fn, data) => { if (typeof fn === "function") fn(data); };

  socket.on("driver_actions:sync", async (payload, callback) => {
    if (role !== "driver" || typeof uid !== "string" || !UUID.test(payload?.sessionId || "")
      || !Array.isArray(payload?.events) || payload.events.length < 1 || payload.events.length > MAX_BATCH
      || Buffer.byteLength(JSON.stringify(payload)) > 64 * 1024) {
      ack(callback, {success: false, acceptedEventIds: [], message: "Invalid activity batch"});
      return;
    }
    const now = Date.now();
    const events = payload.events;
    const terminalEventIds = [];
    let previousSequence = 0;
    for (const event of events) {
      const error = validateEvent(event, now);
      if (error || (event.sessionId != null && event.sessionId !== payload.sessionId)
        || event.sequence <= previousSequence || Object.hasOwn(event, "driverId")) {
        ack(callback, {success: false, acceptedEventIds: [], message: error || "Invalid activity batch"});
        return;
      }
      previousSequence = event.sequence;
      if (event.occurredAtMs < now - RETENTION_MS) terminalEventIds.push(event.eventId);
    }

    const sessionRef = db.collection("driver_action_sessions").doc(payload.sessionId);
    const eventRefs = events.map(event => db.collection("driver_action_events").doc(event.eventId));
    try {
      const acceptedEventIds = await db.runTransaction(async transaction => {
        const sessionSnapshot = await transaction.get(sessionRef);
        const existingEvents = [];
        for (const ref of eventRefs) existingEvents.push(await transaction.get(ref));
        if (sessionSnapshot.exists && sessionSnapshot.data()?.driverId !== uid) {
          throw new Error("Activity session ownership conflict");
        }
        const session = sessionSnapshot.exists ? sessionSnapshot.data() : {};
        let lastSequence = Number.isSafeInteger(session.lastSequence) ? session.lastSequence : 0;
        const accepted = [];
        const pendingWrites = [];
        for (let index = 0; index < events.length; index++) {
          const event = events[index];
          const existing = existingEvents[index];
          if (existing.exists) {
            const saved = existing.data();
            if (saved.driverId !== uid || saved.sessionId !== payload.sessionId) throw new Error("Activity event ownership conflict");
            accepted.push(event.eventId);
            continue;
          }
          if (session.status === "completed" && !terminalEventIds.includes(event.eventId)) throw new Error("Activity sequence conflict");
          if (terminalEventIds.includes(event.eventId)) {
            lastSequence = Math.max(lastSequence, event.sequence);
            continue;
          }
          lastSequence = Math.max(lastSequence, event.sequence);
          const receivedAt = Timestamp.fromMillis(now);
          const eventData = {
            driverId: uid, sessionId: payload.sessionId, eventId: event.eventId,
            sequence: event.sequence, eventType: event.eventType,
            occurredAtMs: event.occurredAtMs, occurredAt: Timestamp.fromMillis(event.occurredAtMs),
            receivedAt, expiresAt: Timestamp.fromMillis(now + RETENTION_MS),
            lat: event.lat ?? null, lng: event.lng ?? null, accuracy: event.accuracy ?? null,
            locationState: event.locationState, subjectId: event.subjectId ?? null,
            outcome: event.outcome, durationMs: event.durationMs ?? null,
            metadata: event.metadata || {},
          };
          pendingWrites.push([eventRefs[index], eventData]);
          accepted.push(event.eventId);
        }
        for (const [ref, data] of pendingWrites) transaction.create(ref, data);
        const hasArrival = events.some(event => event.eventType === "arrived_at_warehouse"
          && (accepted.includes(event.eventId) || terminalEventIds.includes(event.eventId)));
        if (!sessionSnapshot.exists && pendingWrites.length) {
          transaction.create(sessionRef, {
            driverId: uid, sessionId: payload.sessionId,
            status: hasArrival ? "completed" : "active", startedAt: Timestamp.fromMillis(events.find(event => !terminalEventIds.includes(event.eventId)).occurredAtMs),
            updatedAt: Timestamp.fromMillis(now), lastSequence,
            ...(hasArrival ? {completedAt: Timestamp.fromMillis(now)} : {}),
            expiresAt: Timestamp.fromMillis(now + RETENTION_MS),
          });
        } else if (sessionSnapshot.exists && (pendingWrites.length || terminalEventIds.length)) {
          transaction.update(sessionRef, {
            updatedAt: Timestamp.fromMillis(now), lastSequence,
            ...(hasArrival ? {status: "completed", completedAt: Timestamp.fromMillis(now)} : {}),
            expiresAt: Timestamp.fromMillis(now + RETENTION_MS),
          });
        }
        return accepted;
      });
      ack(callback, {
        success: true,
        acceptedEventIds,
        terminalEventIds: terminalEventIds.filter(eventId => !acceptedEventIds.includes(eventId)),
      });
      if (acceptedEventIds.length) io.to("staff:tracking").emit("driver_actions:updated", {
        driverId: uid, sessionId: payload.sessionId, receivedAtMs: now,
      });
    } catch (_) {
      ack(callback, {success: false, acceptedEventIds: [], message: "Activity history could not be synced"});
    }
  });

  socket.on("driver_actions:get_sessions", async (payload, callback) => {
    if (!STAFF_ROLES.has(role) || !validReadRequest(payload, false)) {
      ack(callback, {success: false, message: "Activity history is not available"});
      return;
    }
    try {
      let query = db.collection("driver_action_sessions").where("driverId", "==", payload.driverId)
        .orderBy("startedAt", "desc").limit(readLimit(payload.limit) + 1);
      if (Number.isSafeInteger(payload.cursor) && payload.cursor > 0) {
        query = query.startAfter(Timestamp.fromMillis(payload.cursor));
      }
      const snapshot = await query.get();
      const docs = snapshot.docs.slice(0, readLimit(payload.limit));
      ack(callback, {
        success: true, sessions: docs.map(doc => ({id: doc.id, ...doc.data()})),
        nextCursor: snapshot.docs.length > docs.length ? docs[docs.length - 1]?.data()?.startedAt?.toMillis?.() ?? null : null,
      });
    } catch (_) { ack(callback, {success: false, message: "Activity history is temporarily unavailable"}); }
  });

  socket.on("driver_actions:get_events", async (payload, callback) => {
    if (!STAFF_ROLES.has(role) || !validReadRequest(payload, true)) {
      ack(callback, {success: false, message: "Activity history is not available"});
      return;
    }
    try {
      const session = await db.collection("driver_action_sessions").doc(payload.sessionId).get();
      if (!session.exists || session.data()?.driverId !== payload.driverId) {
        ack(callback, {success: false, message: "Activity history is not available"});
        return;
      }
      let query = db.collection("driver_action_events")
        .where("driverId", "==", payload.driverId)
        .where("sessionId", "==", payload.sessionId)
        .orderBy("sequence", "desc")
        .limit(readLimit(payload.limit) + 1);
      if (Number.isSafeInteger(payload.cursor) && payload.cursor > 0) query = query.startAfter(payload.cursor);
      const snapshot = await query.get();
      const docs = snapshot.docs.slice(0, readLimit(payload.limit));
      ack(callback, {
        success: true, events: docs.map(doc => ({id: doc.id, ...doc.data()})),
        nextCursor: snapshot.docs.length > docs.length ? docs[docs.length - 1]?.data()?.sequence ?? null : null,
      });
    } catch (_) { ack(callback, {success: false, message: "Activity history is temporarily unavailable"}); }
  });
}

async function cleanupExpiredDriverActions(db, now = Date.now()) {
  const cutoff = Timestamp.fromMillis(now);
  let removed = 0;
  for (const collectionName of ["driver_action_events", "driver_action_sessions"]) {
    while (true) {
      const snapshot = await db.collection(collectionName).where("expiresAt", "<=", cutoff).limit(400).get();
      if (snapshot.empty) break;
      const batch = db.batch();
      snapshot.docs.forEach(doc => batch.delete(doc.ref));
      await batch.commit();
      removed += snapshot.size;
      if (snapshot.size < 400) break;
    }
  }
  return removed;
}

function readLimit(value) {
  return Number.isSafeInteger(value) ? Math.max(1, Math.min(MAX_PAGE, value)) : 50;
}
function validReadRequest(payload, needsSession) {
  return payload && typeof payload === "object" && !Array.isArray(payload)
    && typeof payload.driverId === "string" && /^[A-Za-z0-9_-]{1,128}$/.test(payload.driverId)
    && (!needsSession || typeof payload.sessionId === "string" && UUID.test(payload.sessionId))
    && (payload.cursor == null || Number.isSafeInteger(payload.cursor) && payload.cursor >= 0)
    && Buffer.byteLength(JSON.stringify(payload)) <= 2048;
}

module.exports = {bindDriverActionAudit, validateEvent, cleanupExpiredDriverActions, EVENT_TYPES, MAX_BATCH, RETENTION_MS};
