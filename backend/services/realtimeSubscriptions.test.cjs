const test = require("node:test");
const assert = require("node:assert/strict");
const {bindRealtimeSubscriptions, serialize} = require("./realtimeSubscriptions");

function fixture(queueOwnedBy = "driver-1", ownData = {driverId: queueOwnedBy}) {
  const handlers = new Map();
  const events = [];
  const stops = [];
  const filters = [];
  const socket = {
    data: {uid: "driver-1", role: "driver"},
    on: (event, callback) => handlers.set(event, callback),
    once: (event, callback) => handlers.set(`${event}:once`, callback),
    emit: (event, payload) => events.push({event, payload}),
  };
  const ref = {
    doc: () => ref,
    where: (...args) => { filters.push(args); return ref; },
    limit: value => { filters.push(["limit", value]); return ref; },
    get: async () => ({exists: Boolean(queueOwnedBy), data: () => ownData}),
    onSnapshot: next => {ref.next = next; return () => stops.push(true);},
  };
  bindRealtimeSubscriptions(socket, {collection: () => ref});
  return {handlers, events, stops, ref, filters};
}

test("driver assignment stream emits full state and cleans listener on disconnect", () => {
  const f = fixture();
  let ack;
  f.handlers.get("realtime:subscribe")({id: "a-1", topic: "assignments"}, value => {ack = value;});
  assert.equal(ack.success, true);
  f.ref.next({docs: [{id: "s-1", data: () => ({tripStatus: "in_transit"})}]});
  assert.deepEqual(f.events.at(-1), {event: "realtime:state", payload: {id: "a-1", topic: "assignments", data: [{id: "s-1", tripStatus: "in_transit"}]}});
  f.handlers.get("disconnect:once")();
  assert.deepEqual(f.stops, [true]);
});

test("queue subscription verifies ownership before attaching listener", async () => {
  const f = fixture("another-driver");
  let ack;
  await f.handlers.get("realtime:subscribe")({id: "q-1", topic: "queue", params: {queueId: "known-id"}}, value => {ack = value;});
  assert.equal(ack.success, false);
  assert.equal(f.ref.next, undefined);
});

test("queue feed is limited to the authenticated driver's sessions", async () => {
  const f = fixture();
  let ack;
  await f.handlers.get("realtime:subscribe")({id: "own-queues", topic: "queue"}, value => {ack = value;});
  assert.equal(ack.success, true);
  assert.deepEqual(f.filters, [["driverId", "==", "driver-1"]]);
  f.ref.next({docs: [{id: "q-1", data: () => ({driverId: "driver-1", status: "waiting"})}]});
  assert.deepEqual(f.events.at(-1).payload.data, [{id: "q-1", driverId: "driver-1", status: "waiting"}]);
});

test("queue position streams only the authenticated driver's ranked position", async () => {
  const own = {driverId: "driver-1", truckType: "AUV", status: "waiting", registrationComplete: true, isAssigned: false};
  const f = fixture("driver-1", own);
  let ack;
  await f.handlers.get("realtime:subscribe")({id: "position-1", topic: "queuePosition", params: {queueId: "q-own"}}, value => {ack = value;});
  assert.equal(ack.success, true);
  assert.deepEqual(f.filters, [["status", "in", ["waiting", "called"]], ["limit", 2001]]);
  const date = millis => ({toMillis: () => millis});
  f.ref.next({size: 2, docs: [
    {id: "q-first", data: () => ({truckType: "AUV", status: "waiting", registrationComplete: true, isAssigned: false, entryTime: date(1)})},
    {id: "q-own", data: () => ({...own, entryTime: date(2)})},
  ]});
  assert.deepEqual(f.events.at(-1), {event: "realtime:state", payload: {id: "position-1", topic: "queuePosition", data: {queueId: "q-own", position: 2}}});
});

test("subscriptions reject unknown topics and excessive listeners", () => {
  const f = fixture();
  let ack;
  f.handlers.get("realtime:subscribe")({id: "bad", topic: "users"}, value => {ack = value;});
  assert.equal(ack.success, false);
  for (let index = 0; index < 10; index++) f.handlers.get("realtime:subscribe")({id: `s-${index}`, topic: "driver"}, () => {});
  f.handlers.get("realtime:subscribe")({id: "s-10", topic: "driver"}, value => {ack = value;});
  assert.equal(ack.success, false);
});

test("nested Firestore timestamps are encoded as milliseconds", () => {
  assert.deepEqual(serialize({at: {toMillis: () => 42}, list: [1, {at: {toMillis: () => 9}}]}), {
    at: {$timestamp: 42}, list: [1, {at: {$timestamp: 9}}],
  });
});
