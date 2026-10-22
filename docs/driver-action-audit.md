# Driver Action audit

SYSU Go records an operational activity timeline after the driver acknowledges the in-app notice. Events are buffered in a private SQLite outbox and synchronized over authenticated Socket.IO. Existing queue, shipment, and live-location operations remain authoritative; audit capture is best-effort and must not block those actions.

## Event and access contract

- Mobile batches use `driver_actions:sync` with a session ID and ordered event IDs/sequences. The server derives driver identity from the authenticated socket, validates bounded event/location fields, and ACKs accepted event IDs. The client retains unacknowledged records for retry.
- Audit writes are confined to `driver_action_sessions` and `driver_action_events`; they do not create or mutate canonical `drivers` profile documents.
- Authorized staff request paginated history with `driver_actions:get_sessions` and `driver_actions:get_events`. Reads are limited to `fleet_manager`, `admin`, and `super_admin`; driver identity is never accepted from a driver's event payload.
- App lifecycle events and sparse route points are stored in `driver_action_sessions` and `driver_action_events`. Firestore client rules deny direct client access; backend Admin SDK owns persistence.
- Sessions close only after the confirmed `arrived_at_warehouse` event. The arrival action remains tied to the existing warehouse geofence/confirmation flow.

## Data handling and limitations

- Events may include precise device-reported coordinates, accuracy, timestamps, shipment references, and action outcomes. Collection begins only after the driver acknowledges the notice. Do not treat acknowledgement as a substitute for the organization’s privacy/legal review.
- Synced server records are retained for up to 90 days after receipt. Device events older than 90 days are terminally acknowledged as expired and removed from the local outbox so they cannot retry forever. Firestore TTL is not required for correctness because a scheduled cleanup deletes expired event and session documents.
- Reported GPS history is useful for operational review but does not prove a device location was authentic; rooted devices and mock-location tooling can falsify client GPS. It does not replace server-side geofence enforcement.
- If GPS permission/fix is unavailable, the action is retained with an explicit unavailable/permission-denied location state. The feature does not delay dispatch or shipment workflows while reconnecting.

## Deployment and verification

Deploy the SYSU Go backend together with the composite indexes in `firestore.indexes.json` before enabling staff history reads in production. Confirm that the hosting environment runs the backend cleanup scheduler and has the existing Admin SDK Firestore permissions. Do not deploy Firebase credentials to the Android client.

The Android instrumentation test `app/src/androidTest/java/com/sysu/go/audit/DriverActionOutboxTest.kt` requires an emulator/device. Automated unit/backend tests do not replace physical-device checks for consent, offline buffering/reconnect, permission-denied location, app process death, multi-shipment lifecycle, and geofenced warehouse arrival.
