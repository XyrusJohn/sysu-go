# Firestore Composite Indexes Required

Several queries in this codebase use compound filters that **require composite indexes** in Firestore.
Without these indexes, queries silently return zero results or throw "missing index" errors that are only
visible in the server logs as `console.warn` messages.

**To create these**: Firebase Console → Firestore → Indexes → Composite → Add each index below.

---

## queue collection

| Index Name | Fields |
|------------|--------|
| `status_registrationComplete_joinedAt` | `status ASC`, `registrationComplete ASC`, `joinedAt ASC` |
| `status_joinedAt` | `status ASC`, `joinedAt ASC` |
| `truckType_status_joinedAt` | `truckType ASC`, `status ASC`, `joinedAt ASC` |

**Used by:**
- `getQueue` (default, when `registrationComplete == true`)
- `getQueue` (showAll, when `req.query.all === "1"`)
- `scanQR` position query
- `registerTrip` broadcast queue update

### qr_tokens collection

| Index Name | Fields |
|------------|--------|
| `isUsed_expiresAt` | `isUsed ASC`, `expiresAt ASC` |

**Used by:** Token expiration cleanup cron job (if implemented future).

---

## Verification

After deploying, test each affected endpoint:
1. Call `GET /api/qr/queue` — should return queued entries sorted by `joinedAt`
2. From the Android app, scan a QR code — `queuePosition` should be non-null in the response
3. Call `POST /api/trip/register` — should broadcast a `queue_update` Socket event to all clients

If the responses are empty or console shows "Position query failed (likely missing index)", the
indexes have not been created yet, or the Firestore project is still building the index (can take 1-5 min).