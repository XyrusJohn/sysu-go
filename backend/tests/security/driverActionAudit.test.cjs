const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {bindDriverActionAudit, validateEvent, RETENTION_MS, EVENT_TYPES} = require('../../services/driverActionAudit');

const validEvent = () => ({
  eventId: '6a1bb782-9c6f-447e-8a23-a6936b5dd414',
  sequence: 1,
  eventType: 'app_open',
  occurredAtMs: Date.now(),
  locationState: 'available',
  lat: 0,
  lng: 0,
  accuracy: 20,
  outcome: 'success',
  metadata: {},
});

test('driver action event schema accepts complete coordinates including equator or prime meridian', () => {
  assert.equal(validateEvent(validEvent()), null);
  assert.equal(EVENT_TYPES.has('shipment_assigned'), true);
});

test('driver action event schema rejects mismatched or unbounded payload values', () => {
  for (const event of [
    {...validEvent(), lat: null},
    {...validEvent(), locationState: 'unavailable'},
    {...validEvent(), lat: 91},
    {...validEvent(), accuracy: 5001},
    {...validEvent(), metadata: {secret: 'x'.repeat(1025)}},
    {...validEvent(), eventType: 'arbitrary'},
  ]) assert.ok(validateEvent(event));
  assert.equal(validateEvent({...validEvent(), occurredAtMs: Date.now() - RETENTION_MS - 1}), null);
});

test('driver action retention is 90 days and required Firestore query indexes are configured', () => {
  assert.equal(RETENTION_MS, 90 * 24 * 60 * 60 * 1000);
  const indexes = JSON.parse(fs.readFileSync(path.join(__dirname, '../../../firestore.indexes.json'), 'utf8')).indexes;
  assert.ok(indexes.some(index => index.collectionGroup === 'driver_action_sessions'
    && index.fields.map(field => `${field.fieldPath}:${field.order}`).join(',') === 'driverId:ASCENDING,startedAt:DESCENDING'));
  assert.ok(indexes.some(index => index.collectionGroup === 'driver_action_events'
    && index.fields.map(field => `${field.fieldPath}:${field.order}`).join(',') === 'driverId:ASCENDING,sessionId:ASCENDING,sequence:DESCENDING'));
});

test('sync persists idempotently under authenticated driver identity and closes after successful session completion', async () => {
  const docs = new Map();
  const ref = (collection, id) => ({collection, id, key: `${collection}/${id}`});
  const db = {
    collection(name) {
      return {doc(id) { return ref(name, id); }};
    },
    async runTransaction(work) {
      const writes = [];
      const tx = {
        async get(document) {
          const value = docs.get(document.key);
          return {exists: value !== undefined, data: () => value};
        },
        create(document, value) { writes.push(['create', document, value]); },
        set(document, value) { writes.push(['set', document, value]); },
        update(document, value) { writes.push(['update', document, value]); },
      };
      const result = await work(tx);
      for (const [operation, document, value] of writes) {
        const previous = docs.get(document.key) || {};
        if (operation === 'create' && docs.has(document.key)) throw new Error('already exists');
        docs.set(document.key, operation === 'update' ? {...previous, ...value} : {...previous, ...value});
      }
      return result;
    },
  };
  const handlers = new Map();
  const emits = [];
  const socket = {data: {uid: 'authenticated-driver-uid', role: 'driver'}, on: (name, callback) => handlers.set(name, callback)};
  bindDriverActionAudit(socket, {db, io: {to(room) { return {emit: (...args) => emits.push([room, ...args])}; }}});
  const sessionId = '0d3a8588-8c60-4e3f-97c9-c373148234c5';
  const event = {...validEvent(), sessionId, lat: 14.6, lng: 121.0};
  const call = payload => new Promise(resolve => handlers.get('driver_actions:sync')(payload, resolve));
  const first = await call({sessionId, events: [event]});
  assert.deepEqual(first, {success: true, acceptedEventIds: [event.eventId], terminalEventIds: []});
  const persisted = docs.get(`driver_action_events/${event.eventId}`);
  assert.equal(persisted.driverId, 'authenticated-driver-uid');
  assert.equal(persisted.sessionId, sessionId);
  assert.equal(docs.get(`driver_action_sessions/${sessionId}`).status, 'active');
  assert.equal(docs.has('drivers/authenticated-driver-uid'), false);
  assert.equal((await call({sessionId, events: [event]})).acceptedEventIds.length, 1);
  assert.equal(emits.length, 2);

  const arrival = {...validEvent(), eventId: 'fd863694-b981-4b4b-8dc9-cf01c213d9fa', sessionId,
    sequence: 2, eventType: 'arrived_at_warehouse', outcome: 'requested', occurredAtMs: Date.now(), locationState: 'unavailable', lat: undefined, lng: undefined};
  const arrivalRequested = await call({sessionId, events: [arrival]});
  assert.equal(arrivalRequested.success, true);
  assert.equal(docs.get(`driver_action_sessions/${sessionId}`).status, 'active');

  const completed = {...validEvent(), eventId: '843733c2-6fd6-4c49-b9db-3e5e9fbcb68d', sessionId,
    sequence: 3, eventType: 'session_completed', occurredAtMs: Date.now(), locationState: 'unavailable', lat: undefined, lng: undefined};
  const closed = await call({sessionId, events: [completed]});
  assert.equal(closed.success, true);
  assert.equal(docs.get(`driver_action_sessions/${sessionId}`).status, 'completed');
  assert.equal(docs.has('drivers/authenticated-driver-uid'), false);
});

test('legacy successful warehouse-arrival event still closes its session', async () => {
  const docs = new Map();
  const ref = (collection, id) => ({key: `${collection}/${id}`});
  const db = {
    collection(name) { return {doc(id) { return ref(name, id); }}; },
    async runTransaction(work) {
      const writes = [];
      const tx = {
        async get(document) { const value = docs.get(document.key); return {exists: value !== undefined, data: () => value}; },
        create(document, value) { writes.push([document, value]); },
        update(document, value) { writes.push([document, {...(docs.get(document.key) || {}), ...value}]); },
      };
      const result = await work(tx);
      for (const [document, value] of writes) docs.set(document.key, value);
      return result;
    },
  };
  const handlers = new Map();
  const socket = {data: {uid: 'authenticated-driver-uid', role: 'driver'}, on: (name, callback) => handlers.set(name, callback)};
  bindDriverActionAudit(socket, {db, io: {to() { return {emit() {}}; }}});
  const sessionId = 'd2ba3a01-6749-4e19-9a4d-f9eb06df7153';
  const arrival = {...validEvent(), eventId: '6bd2ed91-c8ee-453e-a393-5d791082b739', sessionId,
    eventType: 'arrived_at_warehouse', outcome: 'success', locationState: 'unavailable', lat: undefined, lng: undefined};
  const response = await new Promise(resolve => handlers.get('driver_actions:sync')({sessionId, events: [arrival]}, resolve));
  assert.equal(response.success, true);
  assert.equal(docs.get(`driver_action_sessions/${sessionId}`).status, 'completed');
});

test('expired offline events are terminally acknowledged so valid later events can sync', async () => {
  const docs = new Map();
  const ref = (collection, id) => ({key: `${collection}/${id}`});
  const db = {
    collection(name) { return {doc(id) { return ref(name, id); }}; },
    async runTransaction(work) {
      const writes = [];
      const tx = {
        async get(document) { const value = docs.get(document.key); return {exists: value !== undefined, data: () => value}; },
        create(document, value) { writes.push([document, value]); },
        update(document, value) { writes.push([document, {...(docs.get(document.key) || {}), ...value}]); },
      };
      const result = await work(tx);
      for (const [document, value] of writes) docs.set(document.key, value);
      return result;
    },
  };
  const handlers = new Map();
  const socket = {data: {uid: 'authenticated-driver-uid', role: 'driver'}, on: (name, callback) => handlers.set(name, callback)};
  bindDriverActionAudit(socket, {db, io: {to() { return {emit() {}}; }}});
  const sessionId = 'a344832e-9a56-43d0-85a4-d968c5c51b71';
  const expired = {...validEvent(), eventId: 'fd863694-b981-4b4b-8dc9-cf01c213d9fa', sessionId,
    occurredAtMs: Date.now() - RETENTION_MS - 1};
  const current = {...validEvent(), eventId: '1b743342-1654-42bd-91cb-dd15293296a7', sessionId,
    sequence: 2, occurredAtMs: Date.now()};
  const call = payload => new Promise(resolve => handlers.get('driver_actions:sync')(payload, resolve));
  const response = await call({sessionId, events: [expired, current]});
  assert.deepEqual(response, {success: true, acceptedEventIds: [current.eventId], terminalEventIds: [expired.eventId]});
  assert.equal(docs.has(`driver_action_events/${expired.eventId}`), false);
  assert.equal(docs.has(`driver_action_events/${current.eventId}`), true);
});

test('late lower-sequence events can repair an already-synced session without changing its high-water mark', async () => {
  const docs = new Map();
  const ref = (collection, id) => ({key: `${collection}/${id}`});
  const db = {
    collection(name) { return {doc(id) { return ref(name, id); }}; },
    async runTransaction(work) {
      const writes = [];
      const tx = {
        async get(document) { const value = docs.get(document.key); return {exists: value !== undefined, data: () => value}; },
        create(document, value) { writes.push([document, value]); },
        update(document, value) { writes.push([document, {...(docs.get(document.key) || {}), ...value}]); },
      };
      const result = await work(tx);
      for (const [document, value] of writes) docs.set(document.key, value);
      return result;
    },
  };
  const handlers = new Map();
  const socket = {data: {uid: 'authenticated-driver-uid', role: 'driver'}, on: (name, callback) => handlers.set(name, callback)};
  bindDriverActionAudit(socket, {db, io: {to() { return {emit() {}}; }}});
  const sessionId = '94461c53-3e36-4059-8791-8c5dd0fdb3a9';
  const higher = {...validEvent(), eventId: 'a7a92a12-b67b-46cd-b9a1-2f4a2fbb6e44', sessionId, sequence: 3};
  const lower = {...validEvent(), eventId: '34189a26-61ad-42e6-a9c9-7c25678e8a34', sessionId, sequence: 2};
  const call = payload => new Promise(resolve => handlers.get('driver_actions:sync')(payload, resolve));
  assert.deepEqual((await call({sessionId, events: [higher]})).acceptedEventIds, [higher.eventId]);
  assert.deepEqual((await call({sessionId, events: [lower]})).acceptedEventIds, [lower.eventId]);
  assert.equal(docs.get(`driver_action_sessions/${sessionId}`).lastSequence, 3);
  assert.equal(docs.has(`driver_action_events/${lower.eventId}`), true);
});

test('session read returns a stable error code and logs safe Firestore diagnostics', async () => {
  const driverId = '8IBYwCODqLNc1fNzdgheaUeQPO72';
  const handlers = new Map();
  const db = {
    collection() {
      return {
        where() { return this; },
        orderBy() { return this; },
        limit() { return this; },
        async get() { throw Object.assign(new Error('private Firestore details'), {code: 9}); },
      };
    },
  };
  const socket = {data: {uid: 'staff-uid', role: 'admin'}, on: (name, callback) => handlers.set(name, callback)};
  bindDriverActionAudit(socket, {db, io: {to() { return {emit() {}}; }}});
  const originalError = console.error;
  const logs = [];
  console.error = (...args) => logs.push(args);
  try {
    const response = await new Promise(resolve => handlers.get('driver_actions:get_sessions')({driverId}, resolve));
    assert.deepEqual(response, {
      success: false,
      errorCode: 'DRIVER_ACTION_SESSIONS_READ_FAILED',
      message: 'Activity history is temporarily unavailable',
    });
  } finally {
    console.error = originalError;
  }
  assert.equal(logs.length, 1);
  assert.equal(logs[0][0], '[driver-action] Firestore read failed');
  assert.deepEqual(logs[0][1], {
    operation: 'get_sessions', errorCode: 9, errorName: 'Error',
    reason: 'Firestore failed precondition; check query index requirements',
  });
  assert.equal(JSON.stringify(logs).includes(driverId), false);
  assert.equal(JSON.stringify(logs).includes('private Firestore details'), false);
});
