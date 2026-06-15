package com.sysu.go.activities

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.sysu.go.databinding.DialogGuardSelectionBinding
import com.sysu.go.model.SecurityGuard

class GuardSelectionBottomSheet : BottomSheetDialogFragment() {

    private var _binding: DialogGuardSelectionBinding? = null
    private val binding get() = _binding!!

    private var guards: List<SecurityGuard> = emptyList()
    private var onGuardSelected: ((SecurityGuard) -> Unit)? = null

    companion object {
        const val TAG = "GuardSelectionBottomSheet"

        fun newInstance(
            guards: List<SecurityGuard>,
            onGuardSelected: (SecurityGuard) -> Unit
        ): GuardSelectionBottomSheet {
            return GuardSelectionBottomSheet().apply {
                this.guards = guards
                this.onGuardSelected = onGuardSelected
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogGuardSelectionBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.rvGuards.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = GuardSelectionAdapter(guards) { guard ->
                onGuardSelected?.invoke(guard)
                dismiss()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}