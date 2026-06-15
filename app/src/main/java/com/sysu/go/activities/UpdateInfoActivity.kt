package com.sysu.go.activities

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.sysu.go.R
import com.sysu.go.databinding.ActivityUpdateInfoBinding
import com.sysu.go.databinding.ItemHelperInputBinding
import com.sysu.go.model.ScanRequest
import com.sysu.go.network.QRApiService
import com.sysu.go.utils.Sanitizer
import com.sysu.go.utils.SessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

class UpdateInfoActivity : AppCompatActivity() {

    private lateinit var binding: ActivityUpdateInfoBinding
    private val db = FirebaseFirestore.getInstance()
    private var currentToken: String? = null
    private val helperBindings = mutableListOf<ItemHelperInputBinding>()

    private lateinit var requirementsAdapter: RequirementEditAdapter

    private val truckTypes = arrayOf("AUV", "4W", "6W", "10W", "20F", "40F")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityUpdateInfoBinding.inflate(layoutInflater)
        setContentView(binding.root)

        currentToken = intent.getStringExtra("token")
        if (currentToken == null) {
            Toast.makeText(this, "No token provided", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val sessionManager = SessionManager(this)
        if (sessionManager.getDriverId() == null) {
            Toast.makeText(this, "Not logged in", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        setupTruckTypeSpinner()
        setupRequirements()

        // Load existing requirements if driver already has a profile
        val session = SessionManager(this)
        session.getDriverId()?.let { loadRequirements(it) }

        // Add initial helper
        addHelperView()

        binding.btnAddHelper.setOnClickListener { addHelperView() }
        binding.btnSubmit.setOnClickListener { submitInfo() }
    }

    private fun addHelperView() {
        val inflater = LayoutInflater.from(this)
        val helperBinding = ItemHelperInputBinding.inflate(inflater, binding.helpersContainer, true)
        helperBindings.add(helperBinding)
        
        updateHelperLabels()

        helperBinding.btnRemoveHelper.setOnClickListener {
            if (helperBindings.size > 1) {
                binding.helpersContainer.removeView(helperBinding.root)
                helperBindings.remove(helperBinding)
                updateHelperLabels()
            } else {
                Toast.makeText(this, "At least one helper is required", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateHelperLabels() {
        helperBindings.forEachIndexed { index, b ->
            b.tvHelperLabel.text = "Helper ${index + 1}"
        }
    }

    private fun setupTruckTypeSpinner() {
        val spinnerAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            truckTypes
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        binding.spTruckType.adapter = spinnerAdapter
    }

    private fun setupRequirements() {
        requirementsAdapter = RequirementEditAdapter { _, _ -> }
        binding.requirementsContainer.layoutManager = LinearLayoutManager(this)
        binding.requirementsContainer.adapter = requirementsAdapter
        requirementsAdapter.submitList(RequirementData.getAll())
    }

    private fun loadRequirements(driverId: String) {
        db.collection("drivers").document(driverId).get()
            .addOnSuccessListener { doc ->
                @Suppress("UNCHECKED_CAST")
                val reqMap = doc.get("requirements") as? Map<String, Boolean> ?: return@addOnSuccessListener
                requirementsAdapter.setRequirementsMap(reqMap)
            }
    }

    private fun submitInfo() {
        val truckCompany = Sanitizer.sanitize(binding.etTruckCompany.text.toString().trim(), 100)
        val platePrefix = Sanitizer.sanitizePlatePrefix(binding.etPlatePrefix.text.toString().trim())
        val plateSuffix = Sanitizer.sanitizePlateSuffix(binding.etPlateSuffix.text.toString().trim())
        val truckType = binding.spTruckType.selectedItem?.toString() ?: ""

        if (platePrefix.length < 2) {
            binding.etPlatePrefix.error = "2-4 alphanumeric characters"
            return
        }
        if (plateSuffix.length < 3) {
            binding.etPlateSuffix.error = "3-4 digits required"
            return
        }

        val helpers = mutableListOf<String>()
        var hasError = false
        
        helperBindings.forEach { b ->
            val fn = Sanitizer.sanitize(b.etHelperFN.text.toString().trim(), 50)
            val mi = Sanitizer.sanitizeMI(b.etHelperMI.text.toString().trim())
            val ln = Sanitizer.sanitize(b.etHelperLN.text.toString().trim(), 50)
            
            if (fn.isEmpty()) {
                b.etHelperFN.error = "Required"
                hasError = true
            }
            if (ln.isEmpty()) {
                b.etHelperLN.error = "Required"
                hasError = true
            }
            
            if (!hasError) {
                val fullName = buildString {
                    append(fn)
                    if (mi.isNotEmpty()) append(" $mi.")
                    append(" $ln")
                }.trim()
                helpers.add(fullName)
            }
        }

        if (hasError) return
        if (truckCompany.isEmpty()) {
            binding.etTruckCompany.error = "Required"
            return
        }

        setLoading(true)

        val sessionManager = SessionManager(this)
        val driverId = sessionManager.getDriverId() ?: return
        val driverName = sessionManager.getDriverName()
        val (driverFN, driverMI, driverLN) = sessionManager.getDriverNameParts()
        val driverPhone = sessionManager.getDriverPhone()
        val token = currentToken ?: return

        val fullPlate = "$platePrefix-$plateSuffix"
        val helpersJoined = helpers.joinToString(", ")

        val request = ScanRequest(
            token = token,
            driverId = driverId,
            driverName = driverName,
            driverFN = driverFN,
            driverMI = driverMI,
            driverLN = driverLN,
            truckType = truckType,
            truckCompany = truckCompany,
            plateNumber = fullPlate,
            helperName = helpersJoined,
            driverPhone = driverPhone
        )

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                QRApiService.scanToken(request)
            }
            setLoading(false)

            when (result) {
                is QRApiService.Result.Success -> {
                    // Save driver profile to Firestore for history + auto-fill
                    saveDriverProfile(
                        uid = driverId,
                        name = driverName,
                        fn = driverFN,
                        mi = driverMI,
                        ln = driverLN,
                        truckType = truckType,
                        plateNumber = fullPlate,
                        truckCompany = truckCompany,
                        helperName = helpersJoined,
                        requirements = requirementsAdapter.getFireStoreMap()
                    )

                    val returnedQueueId = result.data.queueId
                    if (returnedQueueId == null) {
                        AlertDialog.Builder(this@UpdateInfoActivity)
                            .setTitle("Error")
                            .setMessage("No queue ID returned. Please contact support.")
                            .setPositiveButton("OK") { _, _ -> finish() }
                            .show()
                        return@launch
                    }

                    Toast.makeText(
                        this@UpdateInfoActivity,
                        getString(R.string.join_queue_success),
                        Toast.LENGTH_LONG
                    ).show()

                    // Reset token in local session if needed, but backend handles refresh
                    
                    // Navigate to TripRegistrationActivity with queueId
                    val intent = Intent(this@UpdateInfoActivity, TripRegistrationActivity::class.java)
                    intent.putExtra("queueId", returnedQueueId)
                    intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
                    startActivity(intent)
                    finish()
                }
                is QRApiService.Result.Error -> {
                    val msg = when {
                        result.code == 400 -> getString(R.string.already_in_queue)
                        result.code == 410 -> getString(R.string.token_expired)
                        else -> "Error ${result.code}: ${result.message}"
                    }
                    AlertDialog.Builder(this@UpdateInfoActivity)
                        .setTitle("Registration Error")
                        .setMessage(msg)
                        .setPositiveButton("OK") { _, _ ->
                            if (result.code == 410) finish()
                        }
                        .show()
                }
            }
        }
    }

    private suspend fun saveDriverProfile(
        uid: String,
        name: String,
        fn: String,
        mi: String,
        ln: String,
        truckType: String,
        plateNumber: String,
        truckCompany: String,
        helperName: String,
        requirements: Map<String, Boolean>
    ) {
        try {
            val updates = mapOf(
                "name" to name,
                "fn" to fn,
                "mi" to mi,
                "ln" to ln,
                "truckType" to truckType,
                "plateNumber" to plateNumber,
                "truckingCompany" to truckCompany,
                "helper" to helperName,
                "requirements" to requirements,
                "updatedAt" to com.google.firebase.firestore.FieldValue.serverTimestamp()
            )
            db.collection("drivers").document(uid).set(updates as Map<String, Any>, SetOptions.merge()).await()
        } catch (e: Exception) {
            // Profile save failure is non-fatal
        }
    }

    private fun setLoading(loading: Boolean) {
        binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        binding.btnSubmit.isEnabled = !loading
        binding.btnAddHelper.isEnabled = !loading
        binding.etTruckCompany.isEnabled = !loading
        binding.etPlatePrefix.isEnabled = !loading
        binding.etPlateSuffix.isEnabled = !loading
        binding.spTruckType.isEnabled = !loading
        helperBindings.forEach { 
            it.etHelperFN.isEnabled = !loading
            it.etHelperMI.isEnabled = !loading
            it.etHelperLN.isEnabled = !loading
            it.btnRemoveHelper.isEnabled = !loading
        }
    }
}