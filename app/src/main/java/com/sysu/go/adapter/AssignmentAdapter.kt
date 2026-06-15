package com.sysu.go.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.sysu.go.R
import com.sysu.go.databinding.ItemAssignmentCardBinding
import com.sysu.go.model.Assignment
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Adapter for assignment cards in the My Trip screen.
 * Each card shows shipment details + Start/End Trip button.
 * Button state: enabled if unlocked, disabled + "LOCKED" if another trip is in progress.
 */
class AssignmentAdapter(
    private val sessionActiveDocId: String?,
    private val onTripActionClick: (Assignment) -> Unit
) : ListAdapter<Assignment, AssignmentAdapter.AssignmentViewHolder>(AssignmentDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AssignmentViewHolder {
        val binding = ItemAssignmentCardBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return AssignmentViewHolder(binding)
    }

    override fun onBindViewHolder(holder: AssignmentViewHolder, position: Int) {
        holder.bind(getItem(position), sessionActiveDocId, onTripActionClick)
    }

    class AssignmentViewHolder(
        private val binding: ItemAssignmentCardBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(
            assignment: Assignment,
            sessionActiveDocId: String?,
            onTripActionClick: (Assignment) -> Unit
        ) {
            val ctx = binding.root.context
            val isLocked = assignment.isLocked(sessionActiveDocId)
            val isInTransit = assignment.isInTransit

            // Shipment ID
            binding.tvShipmentNumber.text = assignment.shipmentNumber

            // Core fields
            binding.tvPlateNo.text = assignment.plateNumber.ifEmpty { "—" }
            binding.tvTruckType.text = assignment.truckType.ifEmpty { "—" }
            binding.tvHelper.text = assignment.helper.ifEmpty { "—" }

            // Guard status
            if (assignment.guardVerified) {
                val guardName = buildString {
                    if (assignment.guardFirstName.isNotEmpty()) {
                        append(assignment.guardFirstName)
                        if (assignment.guardLastName.isNotEmpty()) {
                            append(" ${assignment.guardLastName.firstOrNull() ?: ""}.")
                        }
                    }
                }
                binding.tvGuardStatus.text = if (guardName.isNotEmpty()) "✔ $guardName" else "✔ Verified"
                binding.tvGuardStatus.setTextColor(ContextCompat.getColor(ctx, R.color.transit_green))
            } else {
                binding.tvGuardStatus.text = "Not verified"
                binding.tvGuardStatus.setTextColor(ContextCompat.getColor(ctx, R.color.text_muted))
            }

            // OBD count
            binding.tvObdCount.text = assignment.obdCount.toString()

            // Delivery date
            if (assignment.deliveryTimestamp > 0) {
                val sdf = SimpleDateFormat("MMM dd, yyyy  hh:mm a", Locale.US)
                binding.tvDeliveryDate.text = sdf.format(Date(assignment.deliveryTimestamp))
                binding.rowDeliveryDate.visibility = View.VISIBLE
            } else {
                binding.tvDeliveryDate.text = "—"
            }

            // Customer requirements
            val reqMap = assignment.requirements
            fun setReq(tv: android.widget.TextView, key: String) {
                val checked = reqMap[key] ?: false
                tv.text = if (checked) "✔" else "—"
                tv.setTextColor(
                    ContextCompat.getColor(
                        ctx,
                        if (checked) R.color.transit_green else R.color.text_muted
                    )
                )
            }
            setReq(binding.tvReqAircon, "aircon")
            setReq(binding.tvReqVest, "reflectorizedVest")
            setReq(binding.tvReqHeadcap, "headcap")
            setReq(binding.tvReqSafetyShoes, "safetyShoes")
            setReq(binding.tvReqPants, "pants")
            setReq(binding.tvReqClosedShoes, "closedShoes")

            // Button state
            binding.btnTripAction.setOnClickListener {
                if (!isLocked) {
                    onTripActionClick(assignment)
                }
            }

            when {
                isLocked -> {
                    // Another shipment's trip is active — lock this one
                    binding.btnTripAction.text = "LOCKED"
                    binding.btnTripAction.setBackgroundColor(ContextCompat.getColor(ctx, R.color.text_muted))
                    binding.btnTripAction.isEnabled = false
                    binding.btnTripAction.alpha = 0.6f
                }
                isInTransit -> {
                    // This shipment is in transit — show END TRIP
                    binding.btnTripAction.text = "END TRIP"
                    binding.btnTripAction.setBackgroundColor(ContextCompat.getColor(ctx, R.color.transit_green))
                    binding.btnTripAction.isEnabled = true
                    binding.btnTripAction.alpha = 1f
                }
                assignment.isCompleted -> {
                    // Trip completed
                    binding.btnTripAction.text = "COMPLETED"
                    binding.btnTripAction.setBackgroundColor(ContextCompat.getColor(ctx, R.color.text_muted))
                    binding.btnTripAction.isEnabled = false
                    binding.btnTripAction.alpha = 0.6f
                }
                assignment.guardVerified -> {
                    // Guard verified, not started — show START TRIP
                    binding.btnTripAction.text = "START TRIP"
                    binding.btnTripAction.setBackgroundColor(ContextCompat.getColor(ctx, R.color.sysu_red))
                    binding.btnTripAction.isEnabled = true
                    binding.btnTripAction.alpha = 1f
                }
                else -> {
                    // Not yet verified — show pending
                    binding.btnTripAction.text = "PENDING VERIFICATION"
                    binding.btnTripAction.setBackgroundColor(ContextCompat.getColor(ctx, R.color.text_muted))
                    binding.btnTripAction.isEnabled = false
                    binding.btnTripAction.alpha = 0.6f
                }
            }
        }
    }

    class AssignmentDiffCallback : DiffUtil.ItemCallback<Assignment>() {
        override fun areItemsTheSame(oldItem: Assignment, newItem: Assignment): Boolean =
            oldItem.docId == newItem.docId

        override fun areContentsTheSame(oldItem: Assignment, newItem: Assignment): Boolean =
            oldItem == newItem
    }
}