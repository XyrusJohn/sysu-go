package com.sysu.go.audit

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DriverActionOutboxTest {
    @Test
    fun eventsRemainQueuedUntilAcknowledgedAndAssignmentSnapshotsAreDeduplicated() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase("driver_action_outbox.db")
        DriverActionOutbox(context).use { outbox ->
            val uid = "synthetic-test-driver"
            assertEquals(
                listOf("dispatch-b"),
                outbox.newlyObservedAssignments(uid, listOf("dispatch-a", "dispatch-b"), listOf("dispatch-b"))
            )
            assertEquals(emptyList<String>(), outbox.newlyObservedAssignments(uid, listOf("dispatch-a", "dispatch-b")))

            val event = outbox.enqueue(
                ownerUid = uid,
                eventType = "shipment_assigned",
                subjectId = "dispatch-b",
                locationState = "unavailable",
                idempotencyKey = "assignment:dispatch-b"
            )
            assertNotNull(event)
            assertEquals(1, outbox.pendingCount(uid))
            assertNull(outbox.enqueue(
                ownerUid = uid,
                eventType = "shipment_assigned",
                subjectId = "dispatch-b",
                locationState = "unavailable",
                idempotencyKey = "assignment:dispatch-b"
            ))
            assertEquals(event!!.sessionId, outbox.pending(uid).single().sessionId)
            outbox.acknowledge(uid, listOf(event.eventId))
            assertEquals(0, outbox.pendingCount(uid))
        }
        context.deleteDatabase("driver_action_outbox.db")
    }

    @Test
    fun warehouseArrivalStaysInTripSessionUntilCompletionIsQueued() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase("driver_action_outbox.db")
        DriverActionOutbox(context).use { outbox ->
            val uid = "synthetic-arrival-driver"
            val before = outbox.enqueue(uid, "transit_started")!!
            val arrival = outbox.reserveEvent(uid, "arrived_at_warehouse")!!
            val prompt = outbox.enqueue(uid, "arrival_confirmation_opened")!!
            val completed = outbox.reserveEvent(uid, "session_completed", closeSession = true)!!
            val nextSession = outbox.enqueue(uid, "app_open")!!
            assertEquals(before.sessionId, arrival.sessionId)
            assertEquals(before.sessionId, prompt.sessionId)
            assertEquals(before.sessionId, completed.sessionId)
            assertEquals(arrival.sessionId, outbox.pending(uid).first().sessionId)
            assertEquals(false, arrival.sessionId == nextSession.sessionId)
            outbox.resolveReservation(uid, arrival, 14.6, 121.0, 15f, "available")
            outbox.resolveReservation(uid, completed, 14.6, 121.0, 15f, "available")
            val finalBatch = outbox.pending(uid)
            assertEquals(listOf("transit_started", "arrived_at_warehouse", "arrival_confirmation_opened", "session_completed"),
                finalBatch.map { it.eventType })
            outbox.acknowledge(uid, finalBatch.map { it.eventId })
            assertEquals(nextSession.sessionId, outbox.pending(uid).single().sessionId)
        }
        context.deleteDatabase("driver_action_outbox.db")
    }

    @Test
    fun outboxAcceptsEveryLifecycleEventTypeUsedByTheDriverApp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase("driver_action_outbox.db")
        DriverActionOutbox(context).use { outbox ->
            val uid = "synthetic-lifecycle-driver"
            val lifecycleEvents = listOf(
                "qr_scan_opened", "registration_opened", "loading_cancelled", "verification_cancelled",
                "trip_confirmation", "active_shipment_selected", "warehouse_arrival_confirmed",
                "all_deliveries_completed", "return_prompt_opened", "return_to_base_requested",
                "arrival_confirmation_opened", "session_completed"
            )
            lifecycleEvents.forEach { eventType ->
                assertNotNull("Outbox must support $eventType", outbox.enqueue(uid, eventType))
            }
            assertEquals(lifecycleEvents, outbox.pending(uid).map { it.eventType })
        }
        context.deleteDatabase("driver_action_outbox.db")
    }

    @Test
    fun pendingBatchStopsBeforeAnUnresolvedLocationReservation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase("driver_action_outbox.db")
        DriverActionOutbox(context).use { outbox ->
            val uid = "synthetic-ordering-driver"
            val first = outbox.enqueue(uid, "app_open")!!
            val reserved = outbox.reserveEvent(uid, "qr_scan")!!
            val third = outbox.enqueue(uid, "trip_registration")!!

            assertEquals(listOf(first.eventId), outbox.pending(uid).map { it.eventId })
            outbox.acknowledge(uid, listOf(first.eventId))
            assertEquals(emptyList<String>(), outbox.pending(uid).map { it.eventId })

            outbox.resolveReservation(uid, reserved, locationState = "unavailable")
            assertEquals(listOf(reserved.eventId, third.eventId), outbox.pending(uid).map { it.eventId })
        }
        context.deleteDatabase("driver_action_outbox.db")
    }
}
