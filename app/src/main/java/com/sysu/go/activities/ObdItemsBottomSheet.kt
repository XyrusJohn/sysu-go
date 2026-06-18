package com.sysu.go.activities

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.sysu.go.R
import com.sysu.go.databinding.DialogObdItemsBinding
import com.sysu.go.databinding.ItemObdLineBinding

class ObdItemsBottomSheet : BottomSheetDialogFragment() {

    private var _binding: DialogObdItemsBinding? = null
    private val binding get() = _binding!!

    private var lineItems: List<Map<String, Any?>> = emptyList()

    /** Optional callback when an individual OBD row is tapped.
     *  Used when the shipment is in_transit so tapping a row opens MyTripActivity. */
    var onItemClick: ((item: Map<String, Any?>) -> Unit)? = null

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

        // Transparent background so bg_bottom_sheet drawable fills the sheet
        dialog?.window?.setBackgroundDrawableResource(android.R.color.transparent)

        // Open fully and set peek height
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
            val rowBinding = ItemObdLineBinding.inflate(
                LayoutInflater.from(requireContext()),
                container,
                false
            )

            if (index % 2 == 1) {
                rowBinding.root.setBackgroundResource(R.drawable.bg_obd_row_stripe)
            }

            rowBinding.tvObdNumber.text = item["obdNumber"] as? String ?: "—"

            val qty = (item["quantity"] as? Number)?.toDouble()
            rowBinding.tvObdQuantity.text = if (qty != null) {
                if (qty == qty.toLong().toDouble()) {
                    qty.toLong().toString()
                } else {
                    qty.toString()
                }
            } else {
                "—"
            }

            rowBinding.tvCustomerName.text = item["customerName"] as? String ?: "—"

            // Wire click listener if callback is set (active trip mode)
            onItemClick?.let { callback ->
                rowBinding.root.setOnClickListener {
                    callback.invoke(item)
                }
            }

            container.addView(rowBinding.root)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "ObdItemsBottomSheet"

        fun newInstance(
            lineItems: List<Map<String, Any?>>,
            onItemClick: ((item: Map<String, Any?>) -> Unit)? = null
        ): ObdItemsBottomSheet {
            return ObdItemsBottomSheet().apply {
                this.lineItems = lineItems
                this.onItemClick = onItemClick
            }
        }
    }
}