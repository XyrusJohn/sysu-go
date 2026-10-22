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
    fun arrivalReservationPinsAndClosesItsOriginalSessionBeforeGpsReturns() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase("driver_action_outbox.db")
        DriverActionOutbox(context).use { outbox ->
            val uid = "synthetic-arrival-driver"
            val before = outbox.enqueue(uid, "transit_started")!!
            val arrival = outbox.reserveEvent(uid, "arrived_at_warehouse", closeSession = true)!!
            val nextSession = outbox.enqueue(uid, "app_open")!!
            assertEquals(before.sessionId, arrival.sessionId)
            assertEquals(arrival.sessionId, outbox.pending(uid).first().sessionId)
            assertEquals(false, arrival.sessionId == nextSession.sessionId)
            outbox.resolveReservation(uid, arrival, 14.6, 121.0, 15f, "available")
            val finalBatch = outbox.pending(uid)
            assertEquals("arrived_at_warehouse", finalBatch.last().eventType)
            outbox.acknowledge(uid, finalBatch.map { it.eventId })
            assertEquals(nextSession.sessionId, outbox.pending(uid).single().sessionId)
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
