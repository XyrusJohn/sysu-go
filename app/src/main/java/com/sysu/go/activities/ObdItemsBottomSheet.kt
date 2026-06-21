package com.sysu.go.activities

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.card.MaterialCardView
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import com.sysu.go.R
import com.sysu.go.databinding.DialogObdItemsBinding
import com.sysu.go.utils.ImageStorageHelper

/**
 * Displays OBD items for a shipment as clickable/active rows.
 *
 * Row layout: item_my_trip_obd.xml — shows status pill + camera POD indicator per item.
 *
 * State logic per row:
 *   - !isPodCompleted  → "PENDING" pill (amber) + camera icon (ivPodIndicator) clickable
 *   - DELIVERED        → "DELIVERED" pill (green) + checkmark; non-clickable
 *   - REJECTED         → "REJECTED" pill (red) + checkmark; non-clickable
 *   - DELIVERED W/ REJ → "DELIVERED W/ REJ" pill (orange) + checkmark; non-clickable
 *
 * Card tap → onItemClick (if set, for in-transit navigation)
 * Camera tap (PENDING) → onObdCameraTap (fires MyTripActivity's POD capture flow)
 */
class ObdItemsBottomSheet : BottomSheetDialogFragment() {

    private var _binding: DialogObdItemsBinding? = null
    private val binding get() = _binding!!

    private var lineItems: List<Map<String, Any?>> = emptyList()

    /** The sap_assignments document ID — needed by onObdCameraTap for Firestore update */
    private var shipmentDocId: String = ""

    /** Called when a completed row is tapped (in-transit navigation back to shipment) */
    var onItemClick: ((item: Map<String, Any?>) -> Unit)? = null

    /** Called when the camera button is tapped on a PENDING row.
     *  Arguments: (lineItem, shipmentDocId) — driver is ready to capture POD. */
    var onObdCameraTap: ((lineItem: Map<String, Any?>, shipmentDocId: String) -> Unit)? = null

    /** Called when the sheet needs to reload its lineItems from fresh Firestore data.
     *  Triggers a direct Firestore re-fetch + refreshWith().
     *  Shows a spinner while reloading so the driver knows data is being updated. */
    var onRefreshRequest: (() -> Unit)? = null

    /** Shows spinner indicating a background refresh is in progress.
     *  Called immediately when the user needs feedback that an update is happening. */
    fun showRefreshing() {
        if (_binding == null) return
        binding.pbObdRefresh.visibility = View.VISIBLE
        binding.tvObdSheetEmpty.visibility = View.GONE
        binding.llObdSheetContainer.visibility = View.GONE
    }

    /** Hides spinner after background refresh completes. */
    fun hideRefreshing() {
        if (_binding == null) return
        binding.pbObdRefresh.visibility = View.GONE
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, R.style.BottomSheetTheme)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogObdItemsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        dialog?.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val bottomSheet = dialog?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
        bottomSheet?.let {
            val behavior = BottomSheetBehavior.from(it)
            behavior.peekHeight = 560
            behavior.state = BottomSheetBehavior.STATE_EXPANDED
        }

        populateData()
    }

    private fun populateData() {
        binding.tvObdSheetCount.text = lineItems.size.toString()
        binding.pbObdRefresh.visibility = View.GONE  // hide spinner on every repopulation

        val container = binding.llObdSheetContainer
        container.removeAllViews()

        if (lineItems.isEmpty()) {
            binding.tvObdSheetEmpty.isVisible = true
            binding.llObdSheetContainer.isVisible = false
            return
        }

        binding.tvObdSheetEmpty.isVisible = false
        binding.llObdSheetContainer.isVisible = true

        lineItems.forEachIndexed { index, item ->
            // Inflate row using the existing item_my_trip_obd.xml layout
            val rowView = LayoutInflater.from(requireContext())
                .inflate(R.layout.item_my_trip_obd, container, false) as MaterialCardView

            if (index % 2 == 1) {
                rowView.setCardBackgroundColor(
                    ContextCompat.getColor(requireContext(), R.color.surface2)
                )
            }

            // ─── Bind field values ─────────────────────────────────────────

            val tvObdNumber = rowView.findViewById<TextView>(R.id.tvObdNumber)
            val tvCustomerName = rowView.findViewById<TextView>(R.id.tvCustomerName)
            val tvQuantity = rowView.findViewById<TextView>(R.id.tvQuantity)
            val tvDeliveryStatus = rowView.findViewById<TextView>(R.id.tvDeliveryStatus)
            val ivPodIndicator = rowView.findViewById<ImageView>(R.id.ivPodIndicator)

            tvObdNumber.text = item["obdNumber"] as? String ?: "—"

            val qty = (item["quantity"] as? Number)?.toDouble()
            tvQuantity.text = if (qty != null) {
                if (qty == qty.toLong().toDouble()) {
                    qty.toLong().toString()
                } else {
                    qty.toString()
                }
            } else {
                "—"
            }

            tvCustomerName.text = item["customerName"] as? String ?: "—"

            // ─── State: delivery status pill + ivPodIndicator ─────────────
            val isPodCompleted = item["isPodCompleted"] as? Boolean ?: false
            val deliveryStatus = item["deliveryStatus"] as? String ?: ""
            val podImageUrl = item["podImageUrl"] as? String ?: ""

            tvDeliveryStatus.post {
                configureStatusPill(tvDeliveryStatus, ivPodIndicator, isPodCompleted, deliveryStatus, podImageUrl)
            }

            // ─── Click handling ───────────────────────────────────────────

            if (isPodCompleted) {
                // Completed rows: thumbnail (green square) tap → open fullscreen preview.
                // The ivPodIndicator may display a base64 thumbnail; tapping it triggers the
                // fullscreen preview regardless of which part of the card is tapped.
                ivPodIndicator.isClickable = true
                ivPodIndicator.setOnClickListener {
                    onItemClick?.invoke(item)
                }
                rowView.setOnClickListener {
                    onItemClick?.invoke(item)
                }
            } else {
                // Pending rows: camera button tap → trigger POD capture flow
                ivPodIndicator.setOnClickListener {
                    onObdCameraTap?.invoke(item, shipmentDocId)
                }
                // Card tap → camera (same action as icon tap for pending items)
                rowView.setOnClickListener {
                    onObdCameraTap?.invoke(item, shipmentDocId)
                }
            }

            container.addView(rowView)
        }
    }

    /**
     * Replaces the displayed lineItems with fresh data and rebuilds the list UI.
     * Hides any visible refresh spinner as soon as data is available — called by
     * the socket pod_updated listener OR the onRefreshRequest fallback in MyTripActivity.
     */
    fun refreshWith(newLineItems: List<Map<String, Any?>>) {
        lineItems = newLineItems
        hideRefreshing()  // hide spinner ASAP once data arrives
        if (_binding != null) {
            binding.pbObdRefresh.visibility = View.GONE
            populateData()
        }
    }

    /**
     * Sets tvDeliveryStatus pill text/color/bg and ivPodIndicator icon based on state.
     * Non-clickable for completed rows (no camera re-shoot by default).
     * When podImageUrl is available on a completed item, ivPodIndicator shows a
     * thumbnail of the captured POD photo instead of a checkmark icon.
     */
    private fun configureStatusPill(
        tvDeliveryStatus: TextView,
        ivPodIndicator: ImageView,
        isPodCompleted: Boolean,
        deliveryStatus: String,
        podImageUrl: String = ""
    ) {
        val ctx = requireContext()

        // Attempt to decode the POD thumbnail if a base64 data URI is present
        val thumbnailBitmap = if (podImageUrl.isNotBlank()) {
            try {
                ImageStorageHelper.decodeBase64ToBitmap(podImageUrl)
            } catch (e: Exception) {
                null
            }
        } else null

        if (!isPodCompleted || deliveryStatus.isBlank()) {
            // ── PENDING ──
            tvDeliveryStatus.text = "PENDING"
            tvDeliveryStatus.setBackgroundResource(R.drawable.bg_status_pill_amber)
            tvDeliveryStatus.setTextColor(ContextCompat.getColor(ctx, R.color.text_amber))

            ivPodIndicator.visibility = View.VISIBLE
            ivPodIndicator.setImageResource(R.drawable.ic_camera_pod)
            ivPodIndicator.imageTintList = ContextCompat.getColorStateList(ctx, R.color.text_amber)
            ivPodIndicator.isClickable = true
        } else {
            // ── COMPLETED states: show photo thumbnail if available ──────
            when (deliveryStatus) {
                getString(R.string.pod_status_delivered) -> {
                    tvDeliveryStatus.text = "DELIVERED"
                    tvDeliveryStatus.setBackgroundResource(R.drawable.bg_status_pill_green)
                    tvDeliveryStatus.setTextColor(ContextCompat.getColor(ctx, R.color.white))

                    ivPodIndicator.visibility = View.VISIBLE
                    if (thumbnailBitmap != null) {
                        ivPodIndicator.setImageBitmap(thumbnailBitmap)
                    } else {
                        ivPodIndicator.setImageResource(R.drawable.ic_checkmark_white)
                    }
                    ivPodIndicator.isClickable = true
                }
                getString(R.string.pod_status_rejected) -> {
                    tvDeliveryStatus.text = "REJECTED"
                    tvDeliveryStatus.setBackgroundResource(R.drawable.bg_status_pill_red)
                    tvDeliveryStatus.setTextColor(ContextCompat.getColor(ctx, R.color.white))

                    ivPodIndicator.visibility = View.VISIBLE
                    if (thumbnailBitmap != null) {
                        ivPodIndicator.setImageBitmap(thumbnailBitmap)
                    } else {
                        ivPodIndicator.setImageResource(R.drawable.ic_checkmark_white)
                    }
                    ivPodIndicator.isClickable = true
                }
                getString(R.string.pod_status_delivered_rejection) -> {
                    tvDeliveryStatus.text = "DELIVERED W/ REJ"
                    tvDeliveryStatus.setBackgroundResource(R.drawable.bg_status_pill_orange)
                    tvDeliveryStatus.setTextColor(ContextCompat.getColor(ctx, R.color.white))

                    ivPodIndicator.visibility = View.VISIBLE
                    if (thumbnailBitmap != null) {
                        ivPodIndicator.setImageBitmap(thumbnailBitmap)
                    } else {
                        ivPodIndicator.setImageResource(R.drawable.ic_checkmark_white)
                    }
                    ivPodIndicator.isClickable = true
                }
                else -> {
                    // Fallback: show PENDING indicator
                    tvDeliveryStatus.text = "PENDING"
                    tvDeliveryStatus.setBackgroundResource(R.drawable.bg_status_pill_amber)
                    tvDeliveryStatus.setTextColor(ContextCompat.getColor(ctx, R.color.text_amber))

                    ivPodIndicator.visibility = View.VISIBLE
                    ivPodIndicator.setImageResource(R.drawable.ic_camera_pod)
                    ivPodIndicator.imageTintList = ContextCompat.getColorStateList(ctx, R.color.text_amber)
                    ivPodIndicator.isClickable = true
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "ObdItemsBottomSheet"

        private const val ARG_LINE_ITEMS = "arg_line_items"
        private const val ARG_SHIPMENT_DOC_ID = "arg_shipment_doc_id"

        fun newInstance(
            lineItems: List<Map<String, Any?>>,
            shipmentDocId: String,
            onItemClick: ((item: Map<String, Any?>) -> Unit)? = null,
            onObdCameraTap: ((lineItem: Map<String, Any?>, shipmentDocId: String) -> Unit)? = null
        ): ObdItemsBottomSheet {
            return ObdItemsBottomSheet().apply {
                this.lineItems = lineItems
                this.shipmentDocId = shipmentDocId
                this.onItemClick = onItemClick
                this.onObdCameraTap = onObdCameraTap
            }
        }
    }
}