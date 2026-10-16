const crypto = require('node:crypto');
const express = require('express');
const {Timestamp} = require('firebase-admin/firestore');
const {getDb} = require('../config/firebaseAdmin');
const {verifyToken} = require('../middleware/auth');
const {applyPodGroupProgress, applyPodProgress, validatePodGroupRequest, validatePodRequest} = require('../services/podProgress');

const router = express.Router();

// Keep the image once per grouped capture while retaining OBD-keyed lookup documents for existing readers.
router.post('/:shipmentId/group', verifyToken, async (req, res) => {
    if (req.userProfile.role !== 'driver') return res.status(403).json({success: false, message: 'Driver account required'});
    try {
        const {shipmentId} = req.params;
        if (!/^[A-Za-z0-9_-]{1,160}$/.test(shipmentId)) return res.status(400).json({success: false, message: 'Invalid shipment'});
        const now = Date.now();
        const input = validatePodGroupRequest(req.body, now);
        const db = getDb();
        const ref = db.collection('sap_assignments').doc(shipmentId);
        const captureHash = input.action === 'complete'
            ? crypto.createHash('sha256').update(`${req.user.uid}\u0000${shipmentId}\u0000${input.captureId}`).digest('hex')
            : null;
        const groupRef = captureHash ? ref.collection('pod_groups').doc(captureHash) : null;
        await db.runTransaction(async tx => {
            const snap = await tx.get(ref);
            if (!snap.exists) {
                const error = new Error('Shipment not found');
                error.status = 404;
                throw error;
            }
            const groupSnap = groupRef ? await tx.get(groupRef) : null;
            const rows = applyPodGroupProgress(snap.data(), req.user.uid, input, captureHash, now)
                .map(row => input.action === 'complete' && input.obdNumbers.includes(row.obdNumber)
                    && typeof row.podCapturedAt === 'number'
                    ? {...row, podCapturedAt: Timestamp.fromMillis(row.podCapturedAt)} : row);
            if (groupSnap?.exists) {
                const prior = groupSnap.data();
                if (prior.captureId !== input.captureId || prior.driverId !== req.user.uid
                    || prior.capturedAt?.toMillis?.() !== input.capturedAtMillis
                    || prior.deliveryStatus !== input.deliveryStatus) {
                    const error = new Error('Capture ID has already been used');
                    error.status = 409;
                    throw error;
                }
            }
            tx.update(ref, {lineItems: rows});
            if (input.action === 'complete') {
                const capturedAt = Timestamp.fromMillis(input.capturedAtMillis);
                if (!groupSnap?.exists) {
                    tx.create(groupRef, {
                        captureId: input.captureId,
                        base64Image: input.base64Image,
                        capturedAt,
                        recordedAt: Timestamp.fromMillis(now),
                        driverId: req.user.uid,
                        obdNumbers: input.obdNumbers,
                        deliveryStatus: input.deliveryStatus,
                    });
                }
                for (const obdNumber of input.obdNumbers) {
                    tx.set(ref.collection('pod_images').doc(obdNumber), {
                        groupPodId: captureHash,
                        capturedAt,
                        recordedAt: Timestamp.fromMillis(now),
                        driverId: req.user.uid,
                        obdNumber,
                    });
                }
            }
        });
        req.app.get('io')?.to('staff:tracking').emit('pod_updated', {shipmentDocId: shipmentId, driverId: req.user.uid, source: 'driver_app'});
        return res.json({success: true, obdNumbers: input.obdNumbers, podGroupId: captureHash, capturedAtMillis: input.capturedAtMillis ?? null});
    } catch (error) {
        return res.status(error.status || 500).json({success: false, message: error.status ? error.message : 'Unable to save POD group. Please retry.'});
    }
});

router.post('/:shipmentId/:obdNumber', verifyToken, async (req, res) => {
    if (req.userProfile.role !== 'driver') return res.status(403).json({success: false, message: 'Driver account required'});
    try {
        const {shipmentId, obdNumber} = req.params;
        if (!/^[A-Za-z0-9_-]{1,160}$/.test(shipmentId) || !/^[-A-Za-z0-9_. ]{1,100}$/.test(obdNumber)) {
            return res.status(400).json({success: false, message: 'Invalid shipment or OBD'});
        }
        const now = Date.now();
        const input = validatePodRequest(req.body, now);
        const db = getDb();
        const ref = db.collection('sap_assignments').doc(shipmentId);
        const imageRef = ref.collection('pod_images').doc(obdNumber);
        await db.runTransaction(async tx => {
            const snap = await tx.get(ref);
            if (!snap.exists) {
                const error = new Error('Shipment not found');
                error.status = 404;
                throw error;
            }
            const previousImage = input.action === 'unload' ? null : await tx.get(imageRef);
            const rows = applyPodProgress(snap.data(), req.user.uid, obdNumber, input, now)
                .map(row => row.obdNumber === obdNumber && typeof row.podCapturedAt === 'number'
                    ? {...row, podCapturedAt: Timestamp.fromMillis(row.podCapturedAt)} : row);
            const previousGroupId = previousImage?.get('groupPodId');
            if (previousGroupId && !rows.some(row => row.podGroupId === previousGroupId)) {
                tx.delete(ref.collection('pod_groups').doc(previousGroupId));
            }
            tx.update(ref, {lineItems: rows});
            if (input.action === 'complete') {
                tx.set(imageRef, {
                    base64Image: input.base64Image,
                    capturedAt: Timestamp.fromMillis(input.capturedAtMillis),
                    recordedAt: Timestamp.fromMillis(now),
                    driverId: req.user.uid,
                    obdNumber,
                });
            }
            if (input.action === 'retake') tx.delete(imageRef);
        });
        req.app.get('io')?.to('staff:tracking').emit('pod_updated', {shipmentDocId: shipmentId, driverId: req.user.uid, source: 'driver_app'});
        return res.json({success: true, obdNumber, capturedAtMillis: input.capturedAtMillis ?? null});
    } catch (error) {
        return res.status(error.status || 500).json({success: false, message: error.status ? error.message : 'Unable to save POD. Please retry.'});
    }
});

module.exports = router;
