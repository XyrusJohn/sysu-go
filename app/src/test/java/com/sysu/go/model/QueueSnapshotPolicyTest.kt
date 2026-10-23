package com.sysu.go.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueSnapshotPolicyTest {
    @Test
    fun completedQueueDoesNotKeepRestartedDriverInLoadingState() {
        assertFalse(QueueSnapshotPolicy.hasActiveQueue(listOf("completed", "cancelled", null)))
    }

    @Test
    fun activeQueuePreventsFallbackFromShowingReadyToScan() {
        listOf("assigned", "waiting", "called", "loading", "fully_loaded", "in_transit", "returning_to_base").forEach { status ->
            assertTrue("$status must remain active", QueueSnapshotPolicy.hasActiveQueue(listOf(status)))
        }
    }
}
