package com.sysu.go.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.sysu.go.R
import com.sysu.go.databinding.ItemMyTripTabCardBinding
import com.sysu.go.model.Assignment

class MyTripTabAdapter(
    var hasActiveTrip: Boolean,
    var activeShipmentId: String?,
    private val onTripActionClicked: (Assignment) -> Unit
) : ListAdapter<Assignment, MyTripTabAdapter.MyTripTabViewHolder>(AssignmentDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MyTripTabViewHolder {
        val binding = ItemMyTripTabCardBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return MyTripTabViewHolder(binding)
    }

    override fun onBindViewHolder(holder: MyTripTabViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class MyTripTabViewHolder(val binding: ItemMyTripTabCardBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(assignment: Assignment) {
            val context = binding.root.context

            binding.tvPlateNumber.text = assignment.plateNumber.orEmpty()
            binding.tvOrigin.text = assignment.shipmentNumber.ifBlank { assignment.docId }
            binding.tvDestination.text = assignment.obdNumber.ifBlank { "-" }
            binding.tvHelperName.text = "Helper: ${assignment.helper.orEmpty()}"
            binding.tvObdCount.text = "${assignment.lineItems.size} OBDs"

            when (assignment.tripStatus) {
                "in_transit" -> {
                    binding.tvTripStatusBadge.text = "IN TRANSIT"
                    binding.tvTripStatusBadge.setBackgroundResource(R.drawable.bg_status_pill_red)
                    binding.tvTripStatusBadge.setTextColor(ContextCompat.getColor(context, R.color.white))

                    binding.btnTripAction.text = "VIEW OBD ITEMS"
                    binding.btnTripAction.isEnabled = true
                    binding.btnTripAction.alpha = 1.0f
                    binding.cardOverlayLock.visibility = View.GONE
                    binding.ivLockIcon.visibility = View.GONE
                }
                "completed" -> {
                    binding.tvTripStatusBadge.text = "COMPLETED"
                    binding.tvTripStatusBadge.setBackgroundResource(R.drawable.bg_status_pill_green)
                    binding.tvTripStatusBadge.setTextColor(ContextCompat.getColor(context, R.color.white))

                    binding.btnTripAction.text = "VIEW SUMMARY"
                    binding.btnTripAction.isEnabled = true
                    binding.btnTripAction.alpha = 1.0f
                    binding.cardOverlayLock.visibility = View.GONE
                    binding.ivLockIcon.visibility = View.GONE
                }
                else -> {
                    // pending / confirmed — show CONFIRMED badge if applicable
                    if (assignment.guardVerified && assignment.driverConfirmed) {
                        binding.tvTripStatusBadge.text = "CONFIRMED"
                        binding.tvTripStatusBadge.setBackgroundResource(R.drawable.bg_status_pill_amber)
                        binding.tvTripStatusBadge.setTextColor(ContextCompat.getColor(context, R.color.white))
                    } else {
                        binding.tvTripStatusBadge.text = assignment.tripStatus?.uppercase() ?: "PENDING"
                        binding.tvTripStatusBadge.setBackgroundResource(R.drawable.bg_status_pill_gray)
                        binding.tvTripStatusBadge.setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                    }

                    binding.btnTripAction.text = "START TRIP"

                    if (hasActiveTrip) {
                        // Lock: another trip is already running
                        binding.btnTripAction.isEnabled = false
                        binding.btnTripAction.alpha = 0.4f
                        binding.cardOverlayLock.visibility = View.VISIBLE
                        binding.ivLockIcon.visibility = View.VISIBLE
                    } else {
                        binding.btnTripAction.isEnabled = true
                        binding.btnTripAction.alpha = 1.0f
                        binding.cardOverlayLock.visibility = View.GONE
                        binding.ivLockIcon.visibility = View.GONE
                    }
                }
            }

            binding.btnTripAction.setOnClickListener {
                onTripActionClicked(assignment)
            }

            binding.root.setOnClickListener {
                if (assignment.tripStatus == "in_transit") {
                    onTripActionClicked(assignment)
                }
            }
        }
    }

    private class AssignmentDiffCallback : DiffUtil.ItemCallback<Assignment>() {
        override fun areItemsTheSame(oldItem: Assignment, newItem: Assignment): Boolean =
            oldItem.docId == newItem.docId

        override fun areContentsTheSame(oldItem: Assignment, newItem: Assignment): Boolean =
            oldItem == newItem
    }
}