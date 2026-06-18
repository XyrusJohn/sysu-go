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
 *
 * Dual-button state machine per card:
 * ─────────────────────────────────────────────────────
 * Top (btnTripAction):  PENDING VERIFICATION | LOCKED | START TRIP | END TRIP | COMPLETED
 * Bottom (btnVerification):
 *   Pre-verify  (guardVerified=false) → "GUARD VERIFICATION" — triggers the full flow
 *   Post-verify (guardVerified=true)  → "SHOW VERIFICATION"   — shows saved verification
 */
class AssignmentAdapter(
    private val onTripActionClick: (Assignment) -> Unit,
    private val onGuardPillTap: (Assignment) -> Unit,
    private val onObdTap: (Assignment) -> Unit,
    private val onVerificationAction: (assignment: Assignment, guardVerified: Boolean) -> Unit
) : ListAdapter<Assignment, AssignmentAdapter.AssignmentViewHolder>(AssignmentDiffCallback()) {

    // sessionActiveDocId is refreshed before every submitList() call so bind() always has the current value
    var sessionActiveDocId: String? = null

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AssignmentViewHolder {
        val binding = ItemAssignmentCardBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        val holder = AssignmentViewHolder(binding)

        // Guard status row click — initiates guard verification flow (always opens selection)
        holder.itemView.findViewById<View>(R.id.guardStatusRow)?.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) onGuardPillTap(getItem(pos))
        }

        // OBD row click — opens ObdItemsBottomSheet
        holder.itemView.findViewById<View>(R.id.rowObd)?.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) onObdTap(getItem(pos))
        }

        // Bottom button — routes based on guardVerified state
        // Pre-verify  → "GUARD VERIFICATION"  → opens guard selection flow
        // Post-verify → "SHOW VERIFICATION"   → shows saved verification sheet
        holder.itemView.findViewById<View>(R.id.btnShowVerification)?.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) {
                val assignment = getItem(pos)
                onVerificationAction(assignment, assignment.guardVerified)
            }
        }

        return holder
    }

    override fun onBindViewHolder(holder: AssignmentViewHolder, position: Int) {
        holder.bind(getItem(position), onTripActionClick)
    }

    inner class AssignmentViewHolder(
        private val binding: ItemAssignmentCardBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(
            assignment: Assignment,
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

            // Guard status — pill style (green=verified, amber=pending)
            val guardPillDot: android.graphics.drawable.Drawable? = if (assignment.guardVerified) {
                ContextCompat.getDrawable(ctx, R.drawable.bg_status_dot_green)
            } else {
                ContextCompat.getDrawable(ctx, R.drawable.bg_status_dot_gold)
            }
            binding.guardPillDot.background = guardPillDot
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
                binding.tvGuardStatus.text = guardName.ifEmpty { ctx.getString(R.string.guard_status_verified) }
                binding.tvGuardStatus.setTextColor(ContextCompat.getColor(ctx, R.color.white))
            } else {
                binding.tvGuardStatus.text = ctx.getString(R.string.guard_status_pending)
                binding.tvGuardStatus.setTextColor(ContextCompat.getColor(ctx, R.color.text_amber))
            }

            // OBD count
            binding.tvObdCount.text = assignment.obdCount.toString()

            // Delivery date — clean calendar format only (no time metadata)
            if (assignment.deliveryTimestamp > 0) {
                val sdf = SimpleDateFormat("MMM dd, yyyy", Locale.US)
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

            // =============================================
            // DUAL-BUTTON STATE MACHINE
            //
            // Top (btnTripAction):     PENDING VERIFICATION | LOCKED | START TRIP | END TRIP | COMPLETED
            // Bottom (btnShowVerification):
            //   !guardVerified  → "GUARD VERIFICATION" (solid red) — opens verification flow
            //   guardVerified   → "SHOW VERIFICATION"  (outline red) — shows saved verification
            // =============================================

            when {
                // ── STATE A: Guard NOT yet verified ──────────────────────────────
                !assignment.guardVerified -> {
                    // TOP: Locked out, awaiting guard verification
                    binding.btnTripAction.text = "PENDING VERIFICATION"
                    binding.btnTripAction.setBackgroundColor(ContextCompat.getColor(ctx, R.color.gray_disabled))
                    binding.btnTripAction.isEnabled = false
                    binding.btnTripAction.alpha = 0.7f
                    // BOTTOM: "GUARD VERIFICATION" — solid red, clickable, triggers guard flow
                    binding.btnShowVerification.text = "GUARD VERIFICATION"
                    binding.btnShowVerification.setBackgroundColor(ContextCompat.getColor(ctx, R.color.sysu_red))
                    binding.btnShowVerification.setTextColor(ContextCompat.getColor(ctx, R.color.white))
                    binding.btnShowVerification.isEnabled = true
                    binding.btnShowVerification.alpha = 1f
                    binding.btnShowVerification.visibility = View.VISIBLE
                    // Clear stroke for solid look
                    binding.btnShowVerification.strokeWidth = 0
                }

                // ── Another shipment in transit — lock this one ──────────────────
                isLocked -> {
                    binding.btnTripAction.text = "LOCKED"
                    binding.btnTripAction.setBackgroundColor(ContextCompat.getColor(ctx, R.color.text_muted))
                    binding.btnTripAction.isEnabled = false
                    binding.btnTripAction.alpha = 0.6f
                    // BOTTOM: "SHOW VERIFICATION" — outline style (post-verify, read-only)
                    binding.btnShowVerification.text = "SHOW VERIFICATION"
                    binding.btnShowVerification.setBackgroundResource(R.drawable.bg_btn_outline_red)
                    binding.btnShowVerification.setTextColor(ContextCompat.getColor(ctx, R.color.sysu_red))
                    binding.btnShowVerification.isEnabled = true
                    binding.btnShowVerification.alpha = 1f
                    binding.btnShowVerification.visibility = View.VISIBLE
                }

                // ── This shipment is in progress ─────────────────────────────────
                isInTransit -> {
                    binding.btnTripAction.text = "END TRIP"
                    binding.btnTripAction.setBackgroundColor(ContextCompat.getColor(ctx, R.color.transit_green))
                    binding.btnTripAction.isEnabled = true
                    binding.btnTripAction.alpha = 1f
                    // BOTTOM: "SHOW VERIFICATION" — outline
                    binding.btnShowVerification.text = "SHOW VERIFICATION"
                    binding.btnShowVerification.setBackgroundResource(R.drawable.bg_btn_outline_red)
                    binding.btnShowVerification.setTextColor(ContextCompat.getColor(ctx, R.color.sysu_red))
                    binding.btnShowVerification.isEnabled = true
                    binding.btnShowVerification.alpha = 1f
                    binding.btnShowVerification.visibility = View.VISIBLE
                }

                // ── Trip completed ────────────────────────────────────────────────
                assignment.isCompleted -> {
                    binding.btnTripAction.text = "COMPLETED"
                    binding.btnTripAction.setBackgroundColor(ContextCompat.getColor(ctx, R.color.text_muted))
                    binding.btnTripAction.isEnabled = false
                    binding.btnTripAction.alpha = 0.6f
                    // BOTTOM: "SHOW VERIFICATION" — outline
                    binding.btnShowVerification.text = "SHOW VERIFICATION"
                    binding.btnShowVerification.setBackgroundResource(R.drawable.bg_btn_outline_red)
                    binding.btnShowVerification.setTextColor(ContextCompat.getColor(ctx, R.color.sysu_red))
                    binding.btnShowVerification.isEnabled = true
                    binding.btnShowVerification.alpha = 1f
                    binding.btnShowVerification.visibility = View.VISIBLE
                }

                // ── STATE B: Guard verified AND driver confirmed — READY for transit ──
                assignment.guardVerified && assignment.driverConfirmed -> {
                    binding.btnTripAction.text = "READY ✓"
                    binding.btnTripAction.setBackgroundColor(ContextCompat.getColor(ctx, R.color.transit_green))
                    binding.btnTripAction.isEnabled = false
                    binding.btnTripAction.alpha = 0.8f
                    // BOTTOM: "SHOW VERIFICATION" — outline
                    binding.btnShowVerification.text = "SHOW VERIFICATION"
                    binding.btnShowVerification.setBackgroundResource(R.drawable.bg_btn_outline_red)
                    binding.btnShowVerification.setTextColor(ContextCompat.getColor(ctx, R.color.sysu_red))
                    binding.btnShowVerification.isEnabled = true
                    binding.btnShowVerification.alpha = 1f
                    binding.btnShowVerification.visibility = View.VISIBLE
                }

                // ── STATE C: Guard verified, driver NOT yet confirmed ──────────────
                assignment.canConfirmTrip -> {
                    binding.btnTripAction.text = "CONFIRM SHIPMENT"
                    binding.btnTripAction.setBackgroundColor(ContextCompat.getColor(ctx, R.color.sysu_red))
                    binding.btnTripAction.isEnabled = true
                    binding.btnTripAction.alpha = 1f
                    // BOTTOM: "SHOW VERIFICATION" — outline
                    binding.btnShowVerification.text = "SHOW VERIFICATION"
                    binding.btnShowVerification.setBackgroundResource(R.drawable.bg_btn_outline_red)
                    binding.btnShowVerification.setTextColor(ContextCompat.getColor(ctx, R.color.sysu_red))
                    binding.btnShowVerification.isEnabled = true
                    binding.btnShowVerification.alpha = 1f
                    binding.btnShowVerification.visibility = View.VISIBLE
                }

                // ── Fallback — should not reach here ─────────────────────────────
                else -> {
                    binding.btnTripAction.text = "—"
                    binding.btnTripAction.isEnabled = false
                    binding.btnShowVerification.visibility = View.GONE
                }
            }

            // Click listener — top button, guards via isEnabled state
            binding.btnTripAction.setOnClickListener {
                if (!isLocked && !assignment.isCompleted && assignment.guardVerified) {
                    onTripActionClick(assignment)
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