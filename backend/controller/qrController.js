const { getDb } = require("../config/firebaseAdmin.js");
const { FieldValue, Timestamp } = require("firebase-admin/firestore");
const crypto = require("node:crypto");
const TYPES = ["AUV", "4W", "6W", "10W", "20F", "40F"];
const ACTIVE = ["waiting", "called", "assigned", "loading", "loaded", "fully_loaded", "in_transit", "unloading", "returning_to_base"];
const KEYS = new Set(["token", "driverId", "driverName", "driverFN", "driverMI", "driverLN", "driverPhone", "truckType", "truckCompany", "plateNumber", "helperName"]);
const safeText = (value, max, optional = false) => typeof value === "string" && value.length <= max && !/[\x00-\x1f]/.test(value) && (optional || value.trim().length > 0);
const safeToken = token => typeof token === "string" && /^[A-Za-z0-9_-]{1,128}$/.test(token);
const clean = value => (value || "").replace(/[<>]/g, "").trim();
const fail = (status, message) => Object.assign(new Error(message), { status });
const expires = data => {
  const value = data.expiresAt;
  const millis = value && typeof value.toMillis === "function" ? value.toMillis() : typeof value === "string" ? Date.parse(value) : NaN;
  return data.used === true || data.isUsed === true || !Number.isFinite(millis) || millis <= Date.now();
};
const errorResponse = (res, error) => {
  const status = [401, 403, 404, 409, 410].includes(error.status) ? error.status : 500;
  return res.status(status).json({ success: false, message: status === 500 ? "QR service unavailable" : error.message });
};

const generateQR = async (req, res) => {
  if (!req.user?.uid) return res.status(401).json({ success: false, message: "Unauthorized" });
  try {
    const origin = process.env.FRONTEND_URL;
    const url = new URL(origin);
    if (url.protocol !== "https:" || url.username || url.password || url.search || url.hash) throw fail(409, "QR frontend is not configured safely");
    const db = getDb(), token = "SYS-" + crypto.randomBytes(16).toString("hex").toUpperCase();
    const expiresAt = Timestamp.fromMillis(Date.now() + 5 * 60 * 1000);
    await db.runTransaction(async tx => {
      const actor = await tx.get(db.collection("users").doc(req.user.uid));
      if (!actor.exists || actor.data().status !== "active" || !["qr_generator", "admin", "super_admin"].includes(actor.data().role)) throw fail(403, "Account not authorized");
      tx.create(db.collection("qr_tokens").doc(token), { token, used: false, isUsed: false, expiresAt, createdAt: FieldValue.serverTimestamp() });
    });
    return res.status(200).json({ success: true, token, expiresAt: expiresAt.toDate().toISOString(), scanUrl: url.origin + "/scan?token=" + encodeURIComponent(token) });
  } catch (error) { return errorResponse(res, error); }
};

const verifyQR = async (req, res) => {
  const token = req.query?.token;
  if (!safeToken(token)) return res.status(400).json({ success: false, message: "Invalid QR token" });
  try {
    const snapshot = await getDb().collection("qr_tokens").doc(token).get();
    if (!snapshot.exists) throw fail(404, "Invalid QR token");
    if (expires(snapshot.data())) throw fail(410, "QR token expired or already used");
    return res.status(200).json({ success: true, message: "Token verified", token });
  } catch (error) { return errorResponse(res, error); }
};

const scanQR = async (req, res) => {
  if (!req.user?.uid) return res.status(401).json({ success: false, message: "Unauthorized" });
  const body = req.body;
  if (!body || typeof body !== "object" || Array.isArray(body) || Object.keys(body).some(key => !KEYS.has(key)) ||
      !safeToken(body.token) || !safeText(body.driverId, 128) || !safeText(body.driverName, 100) ||
      !safeText(body.truckType, 10) || !safeText(body.plateNumber, 20) ||
      ["driverFN", "driverMI", "driverLN", "driverPhone", "truckCompany", "helperName"].some(key => body[key] != null && !safeText(body[key], key === "driverPhone" ? 20 : 100, true))) {
    return res.status(400).json({ success: false, message: "Invalid QR registration fields" });
  }
  if (body.driverId !== req.user.uid) return res.status(403).json({ success: false, message: "Cannot scan for another driver" });
  const truckType = body.truckType.trim().toUpperCase();
  if (!TYPES.includes(truckType)) return res.status(400).json({ success: false, message: "Invalid truck type" });
  try {
    const db = getDb(), tokenRef = db.collection("qr_tokens").doc(body.token);
    const admission = db.collection("qr_admissions").doc(req.user.uid), queue = db.collection("queue").doc();
    await db.runTransaction(async tx => {
      const actor = await tx.get(db.collection("users").doc(req.user.uid));
      const token = await tx.get(tokenRef);
      await tx.get(admission);
      const active = await tx.get(db.collection("queue").where("driverId", "==", req.user.uid).where("status", "in", ACTIVE).limit(1));
      if (!actor.exists || actor.data().role !== "driver" || actor.data().status !== "active") throw fail(403, "Active driver required");
      if (!token.exists) throw fail(404, "Invalid QR token");
      if (expires(token.data())) throw fail(410, "QR token expired or already used");
      if (!active.empty) throw fail(409, "Driver already has an active queue entry");
      const now = FieldValue.serverTimestamp();
      tx.set(queue, { id: queue.id, driverId: req.user.uid, driverName: clean(body.driverName),
        driverFN: clean(body.driverFN), driverMI: clean(body.driverMI), driverLN: clean(body.driverLN), driverPhone: clean(body.driverPhone),
        truckType, plateNumber: clean(body.plateNumber), truckCompany: clean(body.truckCompany), helperName: clean(body.helperName), helper: clean(body.helperName),
        token: body.token, status: "waiting", registrationComplete: false, isAssigned: false, isFull: false, joinedAt: now, entryTime: now });
      tx.set(admission, { queueId: queue.id, updatedAt: now });
      tx.update(tokenRef, { used: true, isUsed: true, usedBy: req.user.uid, usedAt: now, queueId: queue.id });
    });
    const io = req.app.get("io");
    if (io) { try { io.to("driver:" + req.user.uid).emit("queue_admitted", { queueId: queue.id }); } catch (_) {} }
    return res.status(200).json({ success: true, message: "Successfully joined the queue", queueId: queue.id, queuePosition: null });
  } catch (error) { return errorResponse(res, error); }
};

const getQueue = async (req, res) => {
  try {
    const query = getDb().collection("queue").where("status", "in", ["waiting", "called"]);
    const snapshot = await (req.query.all === "1" ? query : query.where("registrationComplete", "==", true)).orderBy("joinedAt", "asc").limit(101).get();
    const queue = Object.fromEntries(TYPES.map(type => [type, []]));
    for (const doc of snapshot.docs.slice(0, 100)) {
      const data = doc.data();
      if (TYPES.includes(data.truckType)) queue[data.truckType].push({ id: doc.id, driverId: data.driverId, driverName: data.driverName, truckType: data.truckType, plateNumber: data.plateNumber, status: data.status, registrationComplete: data.registrationComplete === true });
    }
    return res.status(200).json({ success: true, queue, truncated: snapshot.size > 100 });
  } catch (_) { return res.status(500).json({ success: false, message: "Queue unavailable" }); }
};

// Legacy arbitrary state setter bypassed dispatch decisions. Use the web workflow.
const updateQueueStatus = (_req, res) => res.status(410).json({ success: false, message: "Use the SYSU web queue workflow" });
module.exports = { generateQR, verifyQR, scanQR, getQueue, updateQueueStatus };
