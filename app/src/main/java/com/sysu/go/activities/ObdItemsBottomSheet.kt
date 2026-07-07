package com.sysu.go.activities

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.card.MaterialCardView
import android.content.res.ColorStateList
import android.app.AlertDialog
import android.util.Log
import com.sysu.go.R
import com.sysu.go.databinding.DialogObdItemsBinding

/**
 * Which row layout to inflate per item.
 *
 * POD    — item_my_trip_obd.xml  : OBD# + customer + status pill + left red bar
 *                                        Camera icon (pending) + badge (completed) gated by podCaptureEnabled.
 *                                        Used by MyTripActivity.
 *
 * DETAIL — item_obd_detail.xml    : OBD# + customer name + volume row + qty row + top status bar
 *                                        Circular badge only (completed). Used by MainActivity, etc.
 */
enum class RowLayoutType {
    POD,    // OBD# + customer + status pill  (left-red-bar style, podCaptureEnabled gates camera)
    DETAIL  // OBD# + customer + volume + qty rows  (top-status-bar style, read-only)
}

/**
 * Visual appearance for a completed delivery status — badge bg, pill bg, pill text color, label.
 */
private data class StatusAppearance(
    val badgeBg: Int,
    val pillBg: Int,
    val pillTextColor: Int,
    val label: String
)

class ObdItemsBottomSheet : BottomSheetDialogFragment() {

    private var _binding: DialogObdItemsBinding? = null
    private val binding get() = _binding!!

    /** Exposed for read-only access from callers (e.g. MyTripActivity onUnloadConfirm). */
    val lineItems: List<Map<String, Any?>> get() = _lineItems
    private var _lineItems: List<Map<String, Any?>> = emptyList()
    private var shipmentDocId: String = ""
    private var rowLayoutType: RowLayoutType = RowLayoutType.POD

    /** When false: hides the camera icon for pending rows entirely — used in non-MyTrip tabs. */
    private var podCaptureEnabled: Boolean = true

    /** OBD numbers currently being reloaded — shown with per-row spinner. */
    private val loadingForObdNumbers: MutableSet<String> = mutableSetOf()

    var onItemClick: ((item: Map<String, Any?>) -> Unit)? = null
    var onObdCameraTap: ((lineItem: Map<String, Any?>, shipmentDocId: String) -> Unit)? = null
    /** Called when driver taps DONE UNLOADING — triggers POD capture */
    var onUnloadingDone: ((lineItem: Map<String, Any?>, shipmentDocId: String) -> Unit)? = null
    var onRefreshRequest: (() -> Unit)? = null
    /** Called when driver confirms START UNLOADING. Caller writes unloadStatus=UNLOADING to Firestore. */
    var onUnloadConfirm: ((item: Map<String, Any?>, shipmentDocId: String) -> Unit)? = null
    /** Called when RETAKE is tapped on a COMPLETE OBD row — triggers retake flow in MyTripActivity. */
    var onRetakeTap: ((item: Map<String, Any?>, shipmentDocId: String) -> Unit)? = null

    /**
     * Shows loading feedback while data is being refreshed.
     *
     * Per-row mode (obdNumber != null):
     *   - All rows stay visible with normal colors
     *   - Loading row shows pbItemLoading spinner + ivPodIndicator dims to 40% alpha
     *   - Sheet-level spinner is hidden
     *
     * Full-sheet mode (obdNumber == null):
     *   - All rows hidden; sheet-level ProgressBar shown centered
     */
    fun showRefreshing(obdNumber: String? = null) {
        if (_binding == null) return
        if (obdNumber != null) {
            // Per-row mode: track the specific row, show per-row spinner, keep rows visible
            loadingForObdNumbers.add(obdNumber)
            binding.pbObdRefresh.visibility = View.GONE
        } else {
            // Full-sheet mode: hide rows, show centered spinner
            binding.pbObdRefresh.visibility = View.VISIBLE
        }
        binding.tvObdSheetEmpty.visibility = View.GONE
    }

    fun hideRefreshing() {
        if (_binding == null) return
        binding.pbObdRefresh.visibility = View.GONE
        loadingForObdNumbers.clear()
    }

    /**
     * Update lineItems after a Firestore refresh.
     * Also clears any UNLOADING states so items show their current isPodCompleted status.
     */
    fun setLineItems(items: List<Map<String, Any?>>) {
        _lineItems = items
        if (_binding != null) {
            populateData()
        }
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
        binding.pbObdRefresh.visibility = View.GONE

        val container = binding.llObdSheetContainer
        container.removeAllViews()

        if (lineItems.isEmpty()) {
            binding.tvObdSheetEmpty.isVisible = true
            binding.llObdSheetContainer.isVisible = false
            return
        }

        binding.tvObdSheetEmpty.isVisible = false
        binding.llObdSheetContainer.isVisible = true

        val rowInflater = LayoutInflater.from(requireContext())

        lineItems.forEachIndexed { index, item ->
            val rowView: View = when (rowLayoutType) {
                RowLayoutType.POD    -> inflatePodRow(rowInflater, container, item)
                RowLayoutType.DETAIL -> inflateDetailRow(rowInflater, container, item)
            }

            if (index % 2 == 1) {
                rowView.findViewById<MaterialCardView>(R.id.cardRoot)?.setCardBackgroundColor(
                    ContextCompat.getColor(requireContext(), R.color.surface2)
                )
            }

            container.addView(rowView)
        }
    }

    // ─── POD row — item_my_trip_obd.xml ─────────────────────────────────────────

    private fun inflatePodRow(inflater: LayoutInflater, container: ViewGroup, item: Map<String, Any?>): View {
        val rowView = inflater.inflate(R.layout.item_my_trip_obd, container, false) as MaterialCardView

        val tvObdNumber    = rowView.findViewById<TextView>(R.id.tvObdNumber)
        val tvCustomerName = rowView.findViewById<TextView>(R.id.tvCustomerName)
        val tvQuantity     = rowView.findViewById<TextView>(R.id.tvQuantity)
        val tvDeliveryStatus = rowView.findViewById<TextView>(R.id.tvDeliveryStatus)
        // Tag the item for later use in configureStatusPill
        tvDeliveryStatus.tag = item
        val ivPodIndicator = rowView.findViewById<ImageView>(R.id.ivPodIndicator)
        val pbItemLoading  = rowView.findViewById<ProgressBar>(R.id.pbItemLoading)

        val obd = safeObdNumber(item["obdNumber"])

        // OBD number
        tvObdNumber.text = obd
        tvCustomerName.text = item["customerName"] as? String ?: "—"

        // Quantity — gone from bottom sheet but tvQuantity is in the layout (unused here via width/height 0dp)
        tvQuantity.text = formatQuantityForDisplay(item["quantity"])

        // State
        val isPodCompleted = item["isPodCompleted"] as? Boolean ?: false
        val deliveryStatus  = (item["deliveryStatus"] as? String)?.trim()?.uppercase() ?: ""
        val podImageUrl     = item["podImageUrl"] as? String ?: ""

        // Per-row loading state
        val isLoading = loadingForObdNumbers.contains(obd)
        pbItemLoading.isVisible = isLoading

        // Dim the indicator when this row is loading else configure normally
        if (!isLoading) {
            configureStatusPill(tvDeliveryStatus, ivPodIndicator, isPodCompleted, deliveryStatus, obd, item, podImageUrl)
        } else {
            // Loading: keep status pill but dim the indicator
            tvDeliveryStatus.alpha = 1f
            ivPodIndicator.alpha = 0.35f
        }

        // Click handling — disabled while loading
        if (!isLoading) {
            if (isPodCompleted) {
                ivPodIndicator.isClickable = true
                ivPodIndicator.setOnClickListener { onItemClick?.invoke(item) }
                rowView.setOnClickListener { onItemClick?.invoke(item) }
            } else {
                if (podCaptureEnabled) {
                    // Camera icon tap: ALWAYS fires direct POD capture — original behavior
                    ivPodIndicator.setOnClickListener {
                        onObdCameraTap?.invoke(item, shipmentDocId)
                    }
                    // Row/card tap: UNLOADING → DONE UNLOADING, else → direct POD capture
                    val unloadStatus = item["unloadStatus"] as? String ?: ""
                    rowView.setOnClickListener {
                        if (unloadStatus == "UPLOADING") {
                            // no-op while uploading
                        } else if (unloadStatus == "UNLOADING") {
                            onUnloadingDone?.invoke(item, shipmentDocId)
                        } else {
                            onObdCameraTap?.invoke(item, shipmentDocId)
                        }
                    }
                }
            }
        } else {
            ivPodIndicator.isClickable = false
            rowView.setOnClickListener(null)
        }

        return rowView
    }

    // ─── DETAIL row — item_obd_detail.xml ──────────────────────────────────────

    private fun inflateDetailRow(inflater: LayoutInflater, container: ViewGroup, item: Map<String, Any?>): View {
        val rowView = inflater.inflate(R.layout.item_obd_detail, container, false) as MaterialCardView

        val tvObdNumber      = rowView.findViewById<TextView>(R.id.tvObdNumber)
        val tvCustomerName   = rowView.findViewById<TextView>(R.id.tvCustomerName)
        val tvVolume         = rowView.findViewById<TextView>(R.id.tvVolume)
        val tvDetailQuantity = rowView.findViewById<TextView>(R.id.tvDetailQuantity)
        val viewStatusBar    = rowView.findViewById<View>(R.id.viewStatusBar)
        val tvDeliveryStatus = rowView.findViewById<TextView>(R.id.tvDeliveryStatus)
        // Tag the item for later use in configureStatusPill
        tvDeliveryStatus.tag = item
        val ivPodIndicator   = rowView.findViewById<ImageView>(R.id.ivPodIndicator)
        val pbItemLoading    = rowView.findViewById<ProgressBar>(R.id.pbItemLoading)

        val obd = safeObdNumber(item["obdNumber"])

        // Fields
        tvObdNumber.text = obd
        tvCustomerName.text = item["customerName"] as? String ?: "—"

        // Volume — stored as numeric in Firestore
        tvVolume.text = formatVolumeForDisplay(item["volume"])

        // Quantity — stored as string "39.000" or numeric 39
        tvDetailQuantity.text = formatQuantityForDisplay(item["quantity"])

        // State
        val isPodCompleted = item["isPodCompleted"] as? Boolean ?: false
        val deliveryStatus = (item["deliveryStatus"] as? String)?.trim()?.uppercase() ?: ""
        val podImageUrl    = item["podImageUrl"] as? String ?: ""

        // Per-row loading state
        val isLoading = loadingForObdNumbers.contains(obd)
        pbItemLoading.isVisible = isLoading

        // Dim the indicator during loading
        if (!isLoading) {
            bindStatusBar(viewStatusBar, isPodCompleted, deliveryStatus)
            configureStatusPill(tvDeliveryStatus, ivPodIndicator, isPodCompleted, deliveryStatus, obd, item, podImageUrl)
        } else {
            viewStatusBar.alpha = 0.4f
            tvDeliveryStatus.alpha = 1f
            ivPodIndicator.alpha = 0.35f
        }

        // Click: all rows tap to item detail — disabled while loading
        if (!isLoading) {
            ivPodIndicator.setOnClickListener { onItemClick?.invoke(item) }
            rowView.setOnClickListener { onItemClick?.invoke(item) }
        } else {
            ivPodIndicator.isClickable = false
            rowView.setOnClickListener(null)
        }

        return rowView
    }

    // ─── Shared helpers ─────────────────────────────────────────────────────────

    private fun safeObdNumber(raw: Any?): String = when (raw) {
        is String -> raw.ifBlank { "—" }
        is Number -> raw.toString()
        else -> (raw as? String)?.ifBlank { "—" } ?: "—"
    }

    private fun formatQuantityForDisplay(raw: Any?): String {
        return when (raw) {
            is String -> raw.ifBlank { "—" }
            is Number -> raw.toString()
            else -> "—"
        }
    }

    private fun formatVolumeForDisplay(raw: Any?): String {
        val num = when (raw) {
            is Number -> raw.toDouble()
            is String -> raw.toDoubleOrNull()
            else -> null
        }
        return if (num != null) String.format("%.3f m³", num) else "—"
    }

    /**
     * Top status bar color — DETAIL rows only.
     * POD rows use the left red accent bar already baked into item_my_trip_obd.xml.
     */
    private fun bindStatusBar(viewStatusBar: View, isPodCompleted: Boolean, deliveryStatus: String) {
        val ctx = requireContext()
        val color = when {
            !isPodCompleted || deliveryStatus.isBlank() -> R.color.sysu_red
            deliveryStatus == getString(R.string.pod_status_delivered).uppercase() -> R.color.sysu_green
            deliveryStatus == getString(R.string.pod_status_rejected).uppercase() -> R.color.text_red
            deliveryStatus == getString(R.string.pod_status_delivered_rejection).uppercase() -> R.color.sysu_gold
            else -> R.color.sysu_red
        }
        viewStatusBar.setBackgroundColor(ContextCompat.getColor(ctx, color))
        viewStatusBar.alpha = 1f
    }

    /**
     * Status pill text/color + ivPodIndicator appearance.
     *
     * POD layout (podCaptureEnabled-gated):
     *   - PENDING: amber camera icon with amber bg; tap fires onObdCameraTap
     *   - COMPLETED: circular badge + checkmark; tap fires onItemClick
     *
     * DETAIL layout:
     *   - PENDING: amber "PENDING" pill; no pod indicator
     *   - COMPLETED: colored pill + circular badge; tap fires onItemClick
     *
     * Thumbnail decode is NEVER shown inline — only the circular badge.
     * Full photo preview fires via onItemClick.
     */
    private fun configureStatusPill(
        tvDeliveryStatus: TextView,
        ivPodIndicator: ImageView,
        isPodCompleted: Boolean,
        deliveryStatus: String,
        obdNumber: String = "",
        item: Map<String, Any?> = emptyMap(),
        @Suppress("UNUSED_PARAMETER") podImageUrl: String = ""
    ) {
        val ctx = requireContext()

        if (!isPodCompleted || deliveryStatus.isBlank()) {
            // ── PENDING ──
            tvDeliveryStatus.text = "PENDING"
            tvDeliveryStatus.setBackgroundResource(R.drawable.bg_status_pill_amber)
            tvDeliveryStatus.setTextColor(ContextCompat.getColor(ctx, R.color.text_amber))
            tvDeliveryStatus.alpha = 1f

            // Always show camera icon for pending — same as old design
            ivPodIndicator.visibility = View.VISIBLE
            ivPodIndicator.alpha = 1f
            ivPodIndicator.setImageResource(R.drawable.ic_camera_pod)
            ivPodIndicator.imageTintList = ContextCompat.getColorStateList(ctx, R.color.text_amber)
            ivPodIndicator.isClickable = true

            if (rowLayoutType == RowLayoutType.POD && podCaptureEnabled) {
                // Show UNLOAD button for pending POD rows
                val unloadStatus = item["unloadStatus"] as? String ?: ""
                val isUnloading = unloadStatus == "UNLOADING"

                if (isUnloading) {
                    tvDeliveryStatus.text = "UNLOADING..."
                }

                tvDeliveryStatus.parent?.parent?.let { parentView ->
                    val rowView = parentView as? android.view.View ?: return@let
                    val tvUnloadAction = rowView.findViewById<TextView>(R.id.tvUnloadAction)

                    tvUnloadAction?.visibility = View.VISIBLE
                    tvUnloadAction?.text = if (isUnloading) "DONE" else "UNLOAD"
                    tvUnloadAction?.setBackgroundResource(
                        when {
                            isUnloading -> R.drawable.bg_status_pill_green
                            else -> R.drawable.bg_pod_pending_pill
                        }
                    )
                    tvUnloadAction?.setTextColor(
                        ContextCompat.getColor(ctx,
                            when {
                                isUnloading -> R.color.white
                                else -> R.color.transit_amber_dark
                            }
                        )
                    )
                    tvUnloadAction?.setOnClickListener {
                        val context = rowView.context
                        val clickedItem: Map<String, Any?>? = tvDeliveryStatus.tag as? Map<String, Any?>
                        if (clickedItem == null) return@setOnClickListener

                        if (isUnloading) {
                            // UNLOADING → DONE
                            onUnloadingDone?.invoke(clickedItem, shipmentDocId)
                        } else {
                            // PENDING → confirm start unloading
                            AlertDialog.Builder(context)
                                .setTitle("Start Unloading?")
                                .setMessage("Begin unloading for $obdNumber at ${clickedItem["customerName"] as? String ?: "this location"}?")
                                .setPositiveButton("Confirm") { _, _ ->
                                    // Tell MyTripActivity to persist unloadStatus=UNLOADING in Firestore
                                    onUnloadConfirm?.invoke(clickedItem, shipmentDocId)
                                }
                                .setNegativeButton("Cancel", null)
                                .show()
                        }
                    }
                }
            } else {
                // DETAIL layout: no UNLOAD button
            }
        } else {
            // ── COMPLETED states: delivery status pill + RETAKE button ──
            val statusUpper = (item["deliveryStatus"] as? String)?.uppercase()?.trim() ?: ""
            val isPodCompletedItem = item["isPodCompleted"] as? Boolean ?: false

            if (isPodCompletedItem && statusUpper.isNotBlank()) {
                // Show delivery status pill (DELIVERED/REJECTED/DEL. W/ REJ)
                val appearance: StatusAppearance = when (statusUpper) {
                    "DELIVERED" -> StatusAppearance(R.drawable.bg_badge_green, R.drawable.bg_status_pill_green, R.color.white, "DELIVERED")
                    "REJECTED"  -> StatusAppearance(R.drawable.bg_badge_red, R.drawable.bg_status_pill_red, R.color.white, "REJECTED")
                    "DELIVERED W/ REJECTION" -> StatusAppearance(R.drawable.bg_badge_amber, R.drawable.bg_status_pill_amber, R.color.text_amber, "DEL. W/ REJ")
                    else -> StatusAppearance(R.drawable.bg_badge_amber, R.drawable.bg_status_pill_amber, R.color.text_amber, statusUpper)
                }
                tvDeliveryStatus.text = appearance.label
                tvDeliveryStatus.setBackgroundResource(appearance.pillBg)
                tvDeliveryStatus.setTextColor(ContextCompat.getColor(ctx, appearance.pillTextColor))
                tvDeliveryStatus.alpha = 1f

                // Show RETAKE in the tvUnloadAction slot (same position as UNLOAD/CAPTURE)
                val rowCardView = (tvDeliveryStatus.parent as? android.view.View)?.parent as? android.view.View
                val tvRetake = rowCardView?.findViewById<TextView>(R.id.tvUnloadAction)
                tvRetake?.visibility = View.VISIBLE
                tvRetake?.text = "RETAKE"
                tvRetake?.setBackgroundResource(R.drawable.bg_status_pill_outline_grey)
                tvRetake?.setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
                tvRetake?.setOnClickListener {
                    val clickedItem: Map<String, Any?>? = tvDeliveryStatus.tag as? Map<String, Any?>
                    if (clickedItem != null) {
                        onRetakeTap?.invoke(clickedItem, shipmentDocId)
                    }
                }
            } else {
                // isPodCompleted but no deliveryStatus yet — treat as PENDING
                tvDeliveryStatus.text = "PENDING"
                tvDeliveryStatus.setBackgroundResource(R.drawable.bg_status_pill_amber)
                tvDeliveryStatus.setTextColor(ContextCompat.getColor(ctx, R.color.text_amber))
                tvDeliveryStatus.alpha = 1f
            }
        }
    }

    /**
     * Replaces lineItems with fresh data and refreshes the list UI.
     *
     * Per-row mode (calling showRefreshing(obdNumber) first):
     *   - Clears loading state for that specific OBD only
     *   - Sheet-level spinner stays hidden
     *   - Only the updated row changes — no full repaint flicker
     *
     * Full-sheet mode (calling showRefreshing() first, no obdNumber):
     *   - Clears all loading, hides sheet spinner
     *   - Full populateData() repaint
     *
     * Guard: if the sheet has been dismissed (_binding == null), this is a no-op.
     */
    fun refreshWith(
        newLineItems: List<Map<String, Any?>>,
        obdNumberToClear: String? = null
    ) {
        _lineItems = newLineItems

        // Clear loading state for targeted OBD, or all if null
        if (obdNumberToClear != null) {
            loadingForObdNumbers.remove(obdNumberToClear)
        } else {
            loadingForObdNumbers.clear()
        }

        // If any OBD is still loading (async in-flight from another update), keep sheet spinner
        if (loadingForObdNumbers.isEmpty()) {
            binding.pbObdRefresh.visibility = View.GONE
        }

        if (_binding == null) {
            Log.w(TAG, "refreshWith: sheet dismissed, skipping UI rebuild")
            return
        }
        populateData()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "ObdItemsBottomSheet"

        fun newInstance(
            lineItems: List<Map<String, Any?>>,
            shipmentDocId: String,
            rowLayoutType: RowLayoutType = RowLayoutType.POD,
            podCaptureEnabled: Boolean = true,
            onItemClick: ((item: Map<String, Any?>) -> Unit)? = null,
            onObdCameraTap: ((lineItem: Map<String, Any?>, shipmentDocId: String) -> Unit)? = null,
            onUnloadingDone: ((lineItem: Map<String, Any?>, shipmentDocId: String) -> Unit)? = null,
            onRetakeTap: ((item: Map<String, Any?>, shipmentDocId: String) -> Unit)? = null,
            onUnloadConfirm: ((item: Map<String, Any?>, shipmentDocId: String) -> Unit)? = null
        ): ObdItemsBottomSheet {
            return ObdItemsBottomSheet().apply {
                this._lineItems = lineItems
                this.shipmentDocId = shipmentDocId
                this.rowLayoutType = rowLayoutType
                this.podCaptureEnabled = podCaptureEnabled
                this.onItemClick = onItemClick
                this.onObdCameraTap = onObdCameraTap
                this.onUnloadingDone = onUnloadingDone
                this.onRetakeTap = onRetakeTap
                this.onUnloadConfirm = onUnloadConfirm
            }
        }
    }

    }