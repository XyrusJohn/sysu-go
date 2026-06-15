package com.sysu.go.activities

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.sysu.go.databinding.DialogRequirementsEditBinding
import com.google.firebase.firestore.FirebaseFirestore

class RequirementsEditBottomSheet : BottomSheetDialogFragment() {

    private var _binding: DialogRequirementsEditBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: RequirementEditAdapter
    private val firestore = FirebaseFirestore.getInstance()
    private var driverId: String? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogRequirementsEditBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        driverId = arguments?.getString(ARG_DRIVER_ID)

        setupRecyclerView()
        loadCurrentRequirements()
        setupButtons()
    }

    private fun setupRecyclerView() {
        adapter = RequirementEditAdapter { _, _ -> }
        binding.rvRequirements.layoutManager = LinearLayoutManager(requireContext())
        binding.rvRequirements.adapter = adapter
        adapter.submitList(RequirementData.getAll())
    }

    private fun loadCurrentRequirements() {
        val id = driverId ?: return
        firestore.collection("drivers").document(id)
            .get()
            .addOnSuccessListener { doc ->
                @Suppress("UNCHECKED_CAST")
                val reqMap = doc.get("requirements") as? Map<String, Boolean>
                if (reqMap != null) {
                    adapter.setRequirementsMap(reqMap)
                }
            }
    }

    private fun setupButtons() {
        binding.btnCancel.setOnClickListener {
            dismiss()
        }
        binding.btnSave.setOnClickListener {
            saveRequirements()
        }
    }

    private fun saveRequirements() {
        val id = driverId
        if (id == null) {
            Toast.makeText(requireContext(), "Driver ID not found", Toast.LENGTH_SHORT).show()
            return
        }

        binding.btnSave.isEnabled = false
        binding.btnSave.text = "Saving..."

        val updates = mapOf(
            "requirements" to adapter.getFireStoreMap()
        )

        firestore.collection("drivers").document(id)
            .update(updates)
            .addOnSuccessListener {
                Toast.makeText(requireContext(), "Requirements saved", Toast.LENGTH_SHORT).show()
                dismiss()
            }
            .addOnFailureListener {
                binding.btnSave.isEnabled = true
                binding.btnSave.text = "Save"
                Toast.makeText(requireContext(), "Failed to save", Toast.LENGTH_SHORT).show()
            }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "RequirementsEditBottomSheet"
        private const val ARG_DRIVER_ID = "driver_id"

        fun newInstance(driverId: String): RequirementsEditBottomSheet {
            return RequirementsEditBottomSheet().apply {
                arguments = Bundle().apply {
                    putString(ARG_DRIVER_ID, driverId)
                }
            }
        }
    }
}