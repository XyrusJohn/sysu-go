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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MyTripTabAdapter(
    var hasActiveTrip: Boolean,
    var activeShipmentId: String?,
    private val onTripActionClicked: (Assignment) -> Unit,
    private val onObdTap: (List<Map<String, Any>>, String) -> Unit,
    private val onShowVerification: (Assignment) -> Unit
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
            val ctx = binding.root.context

            // ─── Header fields (mirrors AssignmentAdapter exactly) ───────────────

            binding.tvShipmentNumber.text = assignment.shipmentNumber

            binding.tvPlateNo.text = assignment.plateNumber.ifEmpty { "—" }
            binding.tvTruckType.text = assignment.truckType.ifEmpty { "—" }
            binding.tvHelper.text = assignment.helper.ifEmpty { "—" }

            // Delivery date
            if (assignment.deliveryTimestamp > 0) {
                val sdf = SimpleDateFormat("MMM dd, yyyy", Locale.US)
                binding.tvDeliveryDate.text = sdf.format(Date(assignment.deliveryTimestamp))
            } else {
                binding.tvDeliveryDate.text = "—"
            }

            // OBD count
            binding.tvObdCount.text = assignment.lineItems.size.toString()

            // ─── Guard verified pill ───────────────────────────────────────────

            binding.guardPillDot.background = ContextCompat.getDrawable(
                ctx,
                if (assignment.guardVerified) R.drawable.bg_status_dot_green
                else R.drawable.bg_status_dot_gold
            )
            binding.guardPill.setBackgroundResource(
                if (assignment.guardVerified) R.drawable.bg_status_pill_green
                else R.drawable.bg_status_pill_amber
            )
            if (assignment.guardVerified) {
                val guardName = buildString {
                    if (assignment.guardFirstName.isNotEmpty()) {
                        append(assignment.guardFirstName)
                        if (assignment.guardLastName.isNotEmpty()) {
                            append(" ${assignment.guardLastName.firstOrNull() ?: ""}.")
                        }
                    }
                }
                binding.tvGuardStatus.text = guardName.ifEmpty {
                    ctx.getString(R.string.guard_status_verified)
                }
                binding.tvGuardStatus.setTextColor(
                    ContextCompat.getColor(ctx, R.color.white)
                )
            } else {
                binding.tvGuardStatus.text = ctx.getString(R.string.guard_status_pending)
                binding.tvGuardStatus.setTextColor(
                    ContextCompat.getColor(ctx, R.color.text_amber)
                )
            }

            // ─── Driver confirmed pill (same logic as guard pill) ─────────────

            binding.driverPillDot.background = ContextCompat.getDrawable(
                ctx,
                if (assignment.driverConfirmed) R.drawable.bg_status_dot_green
                else R.drawable.bg_status_dot_gold
            )
            binding.driverPill.setBackgroundResource(
                if (assignment.driverConfirmed) R.drawable.bg_status_pill_green
                else R.drawable.bg_status_pill_amber
            )
            binding.tvDriverStatus.text = if (assignment.driverConfirmed) {
                ctx.getString(R.string.guard_status_verified)
            } else {
                ctx.getString(R.string.guard_status_pending)
            }
            binding.tvDriverStatus.setTextColor(
                ContextCompat.getColor(
                    ctx,
                    if (assignment.driverConfirmed) R.color.white else R.color.text_amber
                )
            )

            // ─── Customer requirements table ────────────────────────────────

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

            // ─── Trip status badge + button state ──────────────────────────

            when (assignment.tripStatus) {
                "in_transit" -> {
                    binding.tvTripStatusBadge.text = "IN TRANSIT"
                    binding.tvTripStatusBadge.setBackgroundResource(R.drawable.bg_status_pill_red)
                    binding.tvTripStatusBadge.setTextColor(
                        ContextCompat.getColor(ctx, R.color.white)
                    )

                    // END TRIP is gated on all PODs being captured
                    val canEndTrip = assignment.isPodComplete
                    binding.btnTripAction.text = if (canEndTrip) "END TRIP" else "END TRIP 🔒"
                    binding.btnTripAction.isEnabled = canEndTrip
                    binding.btnTripAction.alpha = if (canEndTrip) 1.0f else 0.4f
                    binding.btnTripAction.backgroundTintList = ContextCompat.getColorStateList(ctx, R.color.sysu_red)

                    binding.btnShowVerification.visibility = View.GONE
                }
                "completed" -> {
                    binding.tvTripStatusBadge.text = "COMPLETED"
                    binding.tvTripStatusBadge.setBackgroundResource(R.drawable.bg_status_pill_green)
                    binding.tvTripStatusBadge.setTextColor(
                        ContextCompat.getColor(ctx, R.color.white)
                    )

                    binding.btnTripAction.text = "VIEW SUMMARY"
                    binding.btnTripAction.isEnabled = true
                    binding.btnTripAction.alpha = 1.0f
                    binding.btnTripAction.backgroundTintList = ContextCompat.getColorStateList(ctx, R.color.sysu_red)

                    binding.btnShowVerification.visibility = View.GONE
                }
                else -> {
                    // pending / confirmed
                    if (assignment.guardVerified && assignment.driverConfirmed) {
                        binding.tvTripStatusBadge.text = "CONFIRMED"
                        binding.tvTripStatusBadge.setBackgroundResource(R.drawable.bg_status_pill_amber)
                        binding.tvTripStatusBadge.setTextColor(
                            ContextCompat.getColor(ctx, R.color.white)
                        )
                    } else {
                        binding.tvTripStatusBadge.text = assignment.tripStatus?.uppercase() ?: "PENDING"
                        binding.tvTripStatusBadge.setBackgroundResource(R.drawable.bg_status_pill_gray)
                        binding.tvTripStatusBadge.setTextColor(
                            ContextCompat.getColor(ctx, R.color.text_secondary)
                        )
                    }

                    binding.btnTripAction.text = "START TRIP"

                    if (hasActiveTrip) {
                        // Lock: another trip is already running
                        binding.btnTripAction.isEnabled = false
                        binding.btnTripAction.alpha = 0.4f
                    } else {
                        binding.btnTripAction.isEnabled = true
                        binding.btnTripAction.alpha = 1.0f
                    }
                    binding.btnTripAction.backgroundTintList = ContextCompat.getColorStateList(ctx, R.color.sysu_red)

                    // Show verification button if guard verified (to re-show saved verification)
                    binding.btnShowVerification.visibility = if (assignment.guardVerified) {
                        View.VISIBLE
                    } else {
                        View.GONE
                    }
                    binding.btnShowVerification.setOnClickListener {
                        onShowVerification(assignment)
                    }
                }
            }

            // OBD row click → open the OBD items bottom sheet (all shipment states)
            binding.rowObd.setOnClickListener {
                onObdTap(assignment.lineItems, assignment.docId)
            }

            binding.btnTripAction.setOnClickListener {
                if (!assignment.isPodComplete) {
                    val incomplete = assignment.lineItems.count { (it["isPodCompleted"] as? Boolean) != true }
                    android.widget.Toast.makeText(
                        ctx,
                        "Kompletuhin muna ang POD ($incomplete item${if (incomplete > 1) "s" else ""} ang natitira)",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                    return@setOnClickListener
                }
                onTripActionClicked(assignment)
            }

            binding.root.setOnClickListener {
                // Card tap: if in_transit, trigger OBD view; otherwise nop
                // isPressed() = true means the click came from a child button
                // that already handled it — don't double-fire in that case.
                if (assignment.tripStatus == "in_transit" && !binding.btnTripAction.isPressed) {
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