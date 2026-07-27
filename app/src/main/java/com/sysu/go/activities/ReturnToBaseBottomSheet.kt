package com.sysu.go.activities

import android.app.Dialog
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.sysu.go.R
import com.sysu.go.databinding.LayoutReturnPanelBinding
import com.sysu.go.utils.SessionManager
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Floating bottom panel shown when a driver taps the map icon after all
 * shipments are delivered (tripStatus = "delivered" or "returning_to_base").
 *
 * One trip, one panel. For multi-shipment trips, all non-completed docIds
 * are passed and updated together on each confirm.
 *
 * Flow:
 *   - Delivered (all):       "START RETURNING TO BASE"
 *   - Returning (any):       "ARRIVED AT WAREHOUSE" → tap → inline confirm
 *   - All completed:         exits the panel (no panel shown)
 *
 * The inline confirm step shows "Arrived at Warehouse?" with a green
 * "YES, CONFIRM ARRIVAL" button — driver must tap twice to complete.
 */
class ReturnToBaseBottomSheet : DialogFragment() {

    private var _binding: LayoutReturnPanelBinding? = null
    private val binding get() = _binding!!
    private val db = FirebaseFirestore.getInstance()
    private val sessionManager by lazy { SessionManager(requireContext()) }

    /** True when at least one shipment is already in returning_to_base state. */
    private var isAlreadyReturning: Boolean = false

    /**
     * Comma-separated docIds of non-completed shipments.
     * MainActivity passes all remaining shipments so they can all be updated.
     */
    private var shipmentDocIds: String = ""

    /** True once "ARRIVED AT WAREHOUSE" has been tapped once — shows confirm step. */
    private var awaitingWarehouseConfirm: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isAlreadyReturning = arguments?.getBoolean(ARG_ALREADY_RETURNING, false) ?: false
        shipmentDocIds = arguments?.getString(ARG_SHIPMENT_DOC_IDS, "") ?: ""
        setStyle(STYLE_NORMAL, R.style.ThemeOverlay_FrostedBlueBottomSheet)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        dialog.window?.requestFeature(Window.FEATURE_NO_TITLE)
        return dialog
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = LayoutReturnPanelBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.overlay.setOnClickListener { dismiss() }
        binding.btnDismiss.setOnClickListener { dismiss() }

        bindPanelContent()
        bindConfirmButton()
    }

    private fun bindPanelContent() {
        when {
            awaitingWarehouseConfirm -> configureForWarehouseConfirmStep()
            isAlreadyReturning -> configureForReturningState()
            else -> configureForDeliveredState()
        }
    }

    private fun bindConfirmButton() {
        binding.btnConfirmReturn.setOnClickListener {
            when {
                // ARRIVED AT WAREHOUSE — first tap: show inline confirm step
                isAlreadyReturning && !awaitingWarehouseConfirm -> {
                    awaitingWarehouseConfirm = true
                    bindPanelContent()
                    bindConfirmButton() // rebind to new state
                }
                // ARRIVED AT WAREHOUSE — confirm step: complete all remaining shipments
                isAlreadyReturning && awaitingWarehouseConfirm -> {
                    performConfirmArrival()
                }
                // START RETURNING TO BASE: begin the return journey
                else -> {
                    performStartReturning()
                }
            }
        }
    }

    // ─── DocId helpers ────────────────────────────────────────────────────────

    private fun getFirstDocId(): String? =
        shipmentDocIds.split(",").firstOrNull { it.isNotBlank() }
            ?: sessionManager.getActiveSapDocId()

    private fun getAllDocIds(): List<String> =
        shipmentDocIds.split(",").filter { it.isNotBlank() }.ifEmpty {
            listOfNotNull(sessionManager.getActiveSapDocId())
        }

    // ─── Panel content ───────────────────────────────────────────────────────

    private fun configureForDeliveredState() {
        binding.tvPanelTitle.text = "All Deliveries Complete"
        binding.tvPanelSubtitle.text = "All shipments have been delivered. Ready to return to base?"
        binding.tvDeliveredSummary.visibility = View.VISIBLE
        binding.tvDeliveredSummary.text = getString(R.string.return_panel_all_delivered)
        binding.btnConfirmReturn.text = "START RETURNING TO BASE"
        binding.btnConfirmReturn.backgroundTintList = ContextCompat.getColorStateList(
            requireContext(), R.color.sysu_blue
        )
    }

    private fun configureForReturningState() {
        binding.tvPanelTitle.text = "Returning to Base"
        binding.tvPanelSubtitle.text = "You are heading back to the warehouse."
        binding.tvDeliveredSummary.visibility = View.GONE
        binding.btnConfirmReturn.text = "ARRIVED AT WAREHOUSE"
        binding.btnConfirmReturn.backgroundTintList = ContextCompat.getColorStateList(
            requireContext(), R.color.sysu_blue
        )
    }

    /** Step 2 of ARRIVED AT WAREHOUSE — inline confirmation. */
    private fun configureForWarehouseConfirmStep() {
        binding.tvPanelTitle.text = "Confirm Arrival"
        binding.tvPanelSubtitle.text = "Tap CONFIRM to mark the trip as complete and return to queue."
        binding.tvDeliveredSummary.visibility = View.GONE
        binding.btnConfirmReturn.text = "YES, CONFIRM ARRIVAL"
        binding.btnConfirmReturn.backgroundTintList = ContextCompat.getColorStateList(
            requireContext(), R.color.sysu_green
        )
    }

    // ─── Actions ──────────────────────────────────────────────────────────────

    private fun performStartReturning() {
        val docId = getFirstDocId()
        if (docId.isNullOrBlank()) {
            Toast.makeText(requireContext(), "No active trip found.", Toast.LENGTH_SHORT).show()
            dismiss(); return
        }

        binding.btnConfirmReturn.isEnabled = false
        binding.btnConfirmReturn.text = "UPDATING..."

        val updates = mapOf(
            "tripStatus" to "returning_to_base",
            "returnedToBaseAt" to Timestamp.now()
        )
        lifecycleScope.launch {
            try {
                db.collection("sap_assignments").document(docId)
                    .update(updates)
                    .await()
                Log.d(TAG, "performStartReturning: $docId → returning_to_base")
                dismiss()
            } catch (e: FirebaseFirestoreException) {
                Log.e(TAG, "performStartReturning error: ${e.message}")
                Toast.makeText(requireContext(), "Failed. Check connection.", Toast.LENGTH_SHORT).show()
                binding.btnConfirmReturn.isEnabled = true
                binding.btnConfirmReturn.text = getString(R.string.btn_start_returning_to_base)
            }
        }
    }

    /**
     * ARRIVED AT WAREHOUSE confirm step — updates ALL non-completed shipments
     * to tripStatus="completed" then navigates to AssignmentActivity.
     */
    private fun performConfirmArrival() {
        val docIds = getAllDocIds()
        if (docIds.isEmpty()) {
            Toast.makeText(requireContext(), "No active trip found.", Toast.LENGTH_SHORT).show()
            dismiss(); return
        }

        // Capture queue doc ID BEFORE clearing session — needed to update Firestore queue
        val queueDocId = sessionManager.getCurrentQueueDocId()

        binding.btnConfirmReturn.isEnabled = false
        binding.btnConfirmReturn.text = "ENDING..."

        val updates = mapOf(
            "tripStatus" to "completed",
            "completedAt" to Timestamp.now()
        )
        lifecycleScope.launch {
            try {
                // Update all shipments to completed
                for (docId in docIds) {
                    db.collection("sap_assignments").document(docId)
                        .update(updates)
                        .await()
                    Log.d(TAG, "performConfirmArrival: $docId → completed")
                }

                // Clear session state
                sessionManager.clearTripSession()
                sessionManager.setTrackingActive(false)

                // Clear queue doc ID from local session — releases driver from queue lookup
                sessionManager.saveCurrentQueueDocId(null)

                // Update Firestore queue document to release this driver
                if (!queueDocId.isNullOrBlank()) {
                    try {
                        db.collection("queue").document(queueDocId)
                            .update(mapOf(
                                "driverId" to FieldValue.delete(),
                                "status" to "available"
                            ))
                            .await()
                        Log.d(TAG, "performConfirmArrival: queue $queueDocId released")
                    } catch (e: FirebaseFirestoreException) {
                        // Queue doc may already be cleaned up — log and continue
                        Log.w(TAG, "performConfirmArrival: queue doc update failed: ${e.message}")
                    }
                }

                dismiss()
                val intent = Intent(requireContext(), AssignmentActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                startActivity(intent)
                requireActivity().finish()
            } catch (e: FirebaseFirestoreException) {
                Log.e(TAG, "performConfirmArrival error: ${e.message}")
                Toast.makeText(requireContext(), "Failed. Check connection.", Toast.LENGTH_SHORT).show()
                binding.btnConfirmReturn.isEnabled = true
                binding.btnConfirmReturn.text = "YES, CONFIRM ARRIVAL"
            }
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundDrawableResource(android.R.color.transparent)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val TAG = "ReturnToBaseBottomSheet"
        private const val ARG_ALREADY_RETURNING = "already_returning"
        private const val ARG_SHIPMENT_DOC_IDS = "shipment_doc_ids"

        /**
         * Factory: all shipments delivered — show "START RETURNING TO BASE".
         * @param shipmentDocIds comma-separated docIds of all non-completed shipments
         */
        fun newInstance(shipmentDocIds: String = ""): ReturnToBaseBottomSheet {
            return ReturnToBaseBottomSheet().apply {
                arguments = Bundle().apply {
                    putBoolean(ARG_ALREADY_RETURNING, false)
                    putString(ARG_SHIPMENT_DOC_IDS, shipmentDocIds)
                }
            }
        }

        /**
         * Factory: shipment(s) already in returning_to_base — show "ARRIVED AT WAREHOUSE".
         * @param shipmentDocIds comma-separated docIds of all non-completed shipments
         */
        fun newInstanceReturning(shipmentDocIds: String = ""): ReturnToBaseBottomSheet {
            return ReturnToBaseBottomSheet().apply {
                arguments = Bundle().apply {
                    putBoolean(ARG_ALREADY_RETURNING, true)
                    putString(ARG_SHIPMENT_DOC_IDS, shipmentDocIds)
                }
            }
        }
    }
}