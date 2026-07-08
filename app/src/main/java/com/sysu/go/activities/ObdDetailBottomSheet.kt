package com.sysu.go.activities

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.sysu.go.R

/**
 * Lightweight bottom sheet that shows OBD detail when driver taps the OBD number
 * on a COMPLETED row inside ObdItemsBottomSheet.
 *
 * Surgical: only touched by the OBD number tap handler.
 * Arguments are passed via Bundle (same pattern as ObdStatusBottomSheet).
 */
class ObdDetailBottomSheet : BottomSheetDialogFragment() {

    companion object {
        const val TAG = "ObdDetailBottomSheet"

        fun newInstance(
            obdNumber: String,
            customerName: String,
            deliveryStatus: String?
        ): ObdDetailBottomSheet {
            return ObdDetailBottomSheet().apply {
                arguments = Bundle().apply {
                    putString("obdNumber", obdNumber)
                    putString("customerName", customerName)
                    putString("deliveryStatus", deliveryStatus)
                }
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.dialog_obd_detail, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val obdNumber = arguments?.getString("obdNumber") ?: "—"
        val customerName = arguments?.getString("customerName") ?: "—"
        val deliveryStatus = arguments?.getString("deliveryStatus")?.uppercase()?.trim() ?: ""

        val tvObdNumber = view.findViewById<TextView>(R.id.tvObdNumber)
        val tvCustomerName = view.findViewById<TextView>(R.id.tvCustomerName)
        val tvDeliveryStatus = view.findViewById<TextView>(R.id.tvDeliveryStatus)
        val llStatusRow = view.findViewById<View>(R.id.llStatusRow)
        val btnClose = view.findViewById<MaterialButton>(R.id.btnClose)

        tvObdNumber.text = obdNumber
        tvCustomerName.text = customerName

        // Style delivery status pill with correct color
        if (deliveryStatus.isNotBlank()) {
            llStatusRow.visibility = View.VISIBLE
            val (bgRes, textColor) = when (deliveryStatus) {
                "DELIVERED" -> R.drawable.bg_status_pill_green to R.color.white
                "REJECTED"  -> R.drawable.bg_status_pill_red to R.color.white
                "DELIVERED W/ REJECTION" -> R.drawable.bg_status_pill_amber to R.color.text_amber
                else -> R.drawable.bg_status_pill_amber to R.color.text_amber
            }
            tvDeliveryStatus.text = deliveryStatus
            tvDeliveryStatus.setBackgroundResource(bgRes)
            tvDeliveryStatus.setTextColor(ContextCompat.getColor(requireContext(), textColor))
        } else {
            llStatusRow.visibility = View.GONE
        }

        btnClose.setOnClickListener { dismiss() }
    }
}