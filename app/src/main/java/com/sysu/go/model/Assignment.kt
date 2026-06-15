package com.sysu.go.model

/**
 * Represents a single shipment assignment for a driver.
 *
 * Used in the multi-shipment dashboard tabs (MainActivity) and the
 * Assignment screen (My Trip). One per sap_assignments document.
 *
 * @param docId  Firestore document ID of the sap_assignments entry — used as shipment number
 * @param queueId  Firestore document ID of the linked queue entry
 * @param driverId  Firebase UID (from queue.driverId or session)
 * @param status  Queue status: waiting / called / assigned / loading / in_transit
 * @param tripStatus  Trip status from sap_assignments: null / in_transit / completed
 * @param plateNumber  Resolved plate number (queue or sap field)
 * @param truckType  From queue.truckType
 * @param helper  From queue.helper / queue.helperName
 * @param guardVerified  True after guard signs and submits verification
 * @param guardFirstName  Guard's first name (populated after verification)
 * @param guardLastName  Guard's last name (populated after verification)
 * @param deliveryTimestamp  actualDelDate from sap_assignments (ms)
 * @param obdCount  Number of line items
 * @param requirements  Map of requirement keys → boolean (from drivers/{driverId}/requirements)
 * @param shipmentNumber  Display alias — returns obdNumber if set, else docId fallback
 * @param obdNumber        OBD number from sap_assignments.data.obdNumber — used in card subtitle
 */
data class Assignment(
    val docId: String,
    val queueId: String,
    val driverId: String,
    val status: String,
    val tripStatus: String?,
    val plateNumber: String,
    val truckType: String,
    val helper: String,
    val guardVerified: Boolean,
    val guardFirstName: String = "",
    val guardLastName: String = "",
    val guardSignature: String = "",
    val deliveryTimestamp: Long = 0L,
    val obdCount: Int = 0,
    val requirements: Map<String, Boolean> = emptyMap(),
    /**
     * Raw OBD line items from sap_assignments.data.lineItems — used to
     * populate ObdItemsBottomSheet when user taps OBD Items card.
     */
    val lineItems: List<Map<String, Any>> = emptyList(),
    val obdNumber: String = ""
) {
    /** Returns OBD number for display; falls back to docId if not set */
    val shipmentNumber: String get() = obdNumber.ifBlank { docId }

    /** True if this shipment's docId matches the active in-transit shipment in SessionManager */
    fun isActive(sessionActiveDocId: String?): Boolean =
        tripStatus == "in_transit" && docId == sessionActiveDocId

    /** True if this shipment has locked START TRIP — another shipment is in transit */
    fun isLocked(sessionActiveDocId: String?): Boolean =
        sessionActiveDocId != null && sessionActiveDocId != docId

    /** True if guard has verified and trip hasn't started yet — driver can tap START TRIP */
    val canStartTrip: Boolean get() = guardVerified && tripStatus != "in_transit" && tripStatus != "completed"

    /** True if trip is in progress on THIS shipment */
    val isInTransit: Boolean get() = tripStatus == "in_transit"

    /** True if trip is completed */
    val isCompleted: Boolean get() = tripStatus == "completed"
}