package com.sysu.go.model

internal object QueueSnapshotPolicy {
    private val activeStatuses = setOf(
        "assigned", "waiting", "called", "loading", "fully_loaded", "in_transit", "returning_to_base"
    )

    fun hasActiveQueue(statuses: Iterable<String?>): Boolean = statuses.any { it in activeStatuses }
}
