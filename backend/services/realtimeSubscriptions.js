const ID_PATTERN = /^[A-Za-z0-9_-]{1,80}$/;
const MASTER_DATA_FIELDS = {
  securityGuards: ["guardId", "firstName", "middleInitial", "lastName", "fullName", "active"],
  truckCompanies: ["companyCode", "forwarderCode", "name", "active"],
};

function serialize(value) {
  if (value && typeof value.toMillis === "function") return {$timestamp: value.toMillis()};
  if (Array.isArray(value)) return value.map(serialize);
  if (value && typeof value === "object") return Object.fromEntries(Object.entries(value).map(([key, item]) => [key, serialize(item)]));
  return value;
}

function bindRealtimeSubscriptions(socket, db) {
  const subscriptions = new Map();
  const stopAll = () => {
    for (const stop of subscriptions.values()) stop();
    subscriptions.clear();
  };
  socket.on("realtime:subscribe", async (request, acknowledge) => {
    const ack = typeof acknowledge === "function" ? acknowledge : () => {};
    if (!request || typeof request !== "object" || Array.isArray(request)
      || !ID_PATTERN.test(request.id || "") || subscriptions.has(request.id) || subscriptions.size >= 10) {
      ack({success: false, message: "Invalid subscription"});
      return;
    }
    const uid = socket.data.uid;
    let collection;
    let document = false;
    if (request.topic === "profile") {
      collection = db.collection("users").doc(uid);
      document = true;
    } else if (request.topic === "driver") {
      collection = db.collection("drivers").doc(uid);
      document = true;
    } else if (request.topic === "queue") {
      const queueId = request.params?.queueId;
      if (queueId == null) {
        collection = db.collection("queue").where("driverId", "==", uid);
      } else {
        if (typeof queueId !== "string" || !ID_PATTERN.test(queueId)) {
          ack({success: false, message: "Invalid queue session"});
          return;
        }
        collection = db.collection("queue").doc(queueId);
        const queueRecord = await collection.get();
        if (!queueRecord.exists || queueRecord.data()?.driverId !== uid) {
          ack({success: false, message: "Subscription not authorized"});
          return;
        }
        if (socket.connected === false) return;
        document = true;
      }
    } else if (request.topic === "queuePosition") {
      const queueId = request.params?.queueId;
      if (typeof queueId !== "string" || !ID_PATTERN.test(queueId)) {
        ack({success: false, message: "Invalid queue session"});
        return;
      }
      const own = await db.collection("queue").doc(queueId).get();
      if (!own.exists || own.data()?.driverId !== uid) {
        ack({success: false, message: "Subscription not authorized"});
        return;
      }
      if (socket.connected === false) return;
      const ownData = own.data();
      const validTypes = new Set(["AUV", "4W", "6W", "10W", "20F", "40F"]);
      const stop = db.collection("queue").where("status", "in", ["waiting", "called"]).limit(2001)
        .onSnapshot(snapshot => {
          if (snapshot.size > 2000) {
            socket.emit("realtime:state", {id: request.id, topic: request.topic, data: {queueId, position: null}});
            return;
          }
          const currentOwn = snapshot.docs.find(doc => doc.id === queueId)?.data() || ownData;
          const currentType = String(currentOwn.truckType || "").toUpperCase();
          let position = null;
          if (currentOwn.registrationComplete === true && currentOwn.isAssigned !== true
            && ["waiting", "called"].includes(currentOwn.status) && validTypes.has(currentType)) {
            const ranked = snapshot.docs.map(doc => ({id: doc.id, ...doc.data()})).filter(entry =>
              entry.registrationComplete === true && entry.isAssigned !== true
              && ["waiting", "called"].includes(entry.status)
              && String(entry.truckType || "").toUpperCase() === currentType
              && Number.isFinite(entry.entryTime?.toMillis?.()))
              .sort((left, right) => left.entryTime.toMillis() - right.entryTime.toMillis()
                || left.id.localeCompare(right.id));
            const index = ranked.findIndex(entry => entry.id === queueId);
            if (index >= 0) position = index + 1;
          }
          socket.emit("realtime:state", {id: request.id, topic: request.topic, data: {queueId, position}});
        }, () => socket.emit("realtime:error", {id: request.id, topic: request.topic, message: "Queue position unavailable"}));
      subscriptions.set(request.id, stop);
      ack({success: true, id: request.id, topic: request.topic});
      return;
    } else if (request.topic === "assignments") {
      collection = db.collection("sap_assignments").where("driverId", "==", uid);
    } else if (request.topic === "location") {
      collection = db.collection("driver_locations").doc(uid);
      document = true;
    } else if (Object.hasOwn(MASTER_DATA_FIELDS, request.topic)) {
      collection = db.collection(request.topic === "securityGuards" ? "security_guard" : "truck_company")
        .where("active", "==", true);
    } else {
      ack({success: false, message: "Subscription not authorized"});
      return;
    }
    const stop = collection.onSnapshot(snapshot => {
      let data = document
        ? (snapshot.exists ? {id: snapshot.id, ...serialize(snapshot.data())} : null)
        : snapshot.docs.map(doc => ({id: doc.id, ...serialize(doc.data())}));
      if (MASTER_DATA_FIELDS[request.topic]) {
        const fields = MASTER_DATA_FIELDS[request.topic];
        data = snapshot.docs.map(doc => {
          const source = doc.data();
          return Object.fromEntries([["id", doc.id], ...fields
            .filter(field => source[field] !== undefined)
            .map(field => [field, serialize(source[field])])]);
        });
      }
      socket.emit("realtime:state", {id: request.id, topic: request.topic, data});
    }, () => socket.emit("realtime:error", {id: request.id, topic: request.topic, message: "Realtime data unavailable"}));
    subscriptions.set(request.id, stop);
    ack({success: true, id: request.id, topic: request.topic});
  });
  socket.on("realtime:unsubscribe", (request, acknowledge) => {
    const ack = typeof acknowledge === "function" ? acknowledge : () => {};
    const stop = ID_PATTERN.test(request?.id || "") ? subscriptions.get(request.id) : null;
    if (stop) {
      stop();
      subscriptions.delete(request.id);
    }
    ack({success: true});
  });
  socket.once("disconnect", stopAll);
}

module.exports = {bindRealtimeSubscriptions, serialize};
