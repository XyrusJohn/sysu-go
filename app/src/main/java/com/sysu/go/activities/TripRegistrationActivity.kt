package com.sysu.go.activities

import android.content.Intent
import android.os.Bundle
import android.text.InputFilter
import android.view.LayoutInflater
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.sysu.go.databinding.ActivityTripRegistrationBinding
import com.sysu.go.databinding.ItemHelperInputBinding
import com.sysu.go.firebase.FirebaseAuthManager
import com.sysu.go.network.QRApiService
import com.sysu.go.network.TripApiService
import com.sysu.go.utils.Sanitizer
import com.sysu.go.utils.SessionManager
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

class TripRegistrationActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTripRegistrationBinding
    private var queueId: String? = null
    private var currentToken: String? = null
    private val helperBindings = mutableListOf<ItemHelperInputBinding>()
    private lateinit var requirementsAdapter: RequirementEditAdapter

    // Validation state
    private var helperDone = false
    private var truckDone = true   // pre-filled from profile so default true
    private var plateDone = false
    private var requirementsDone = true  // checkbox-only, default true

    private val truckTypes = arrayOf("Select Truck Type", "AUV", "4W", "6W", "10W", "20F", "40F")
    private val platePrefixInputFilter = InputFilter { source, start, end, dest, dstart, dend ->
        val filtered = StringBuilder()
        for (i in start until end) {
            val c = source[i]
            if (c in 'A'..'Z' || c in 'a'..'z') {
                filtered.append(c.uppercaseChar())
            }
        }
        filtered.toString()
    }

    private val plateSuffixInputFilter = InputFilter { source, start, end, dest, dstart, dend ->
        val filtered = StringBuilder()
        for (i in start until end) {
            val c = source[i]
            if (c in '0'..'9') {
                filtered.append(c)
            }
        }
        filtered.toString()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityTripRegistrationBinding.inflate(layoutInflater)
        binding.etPlatePrefix.filters = arrayOf(platePrefixInputFilter, InputFilter.LengthFilter(3))
        binding.etPlateSuffix.filters = arrayOf(plateSuffixInputFilter, InputFilter.LengthFilter(4))
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        queueId = intent.getStringExtra("queueId")
        currentToken = intent.getStringExtra("token")

        if (queueId == null && currentToken == null) {
            AlertDialog.Builder(this)
                .setTitle("Error")
                .setMessage("Invalid session. Please scan QR code again.")
                .setPositiveButton("OK") { _, _ -> finish() }
                .setCancelable(false)
                .show()
            return
        }

        setupTruckTypeSpinner()
        setupRequirements()
        // Load saved requirements into adapter immediately
        SessionManager(this).getDriverId()?.let { loadSavedRequirements(it) }

        if (queueId != null) {
            // Re-filling from existing queue entry
            fetchQueueData()
        } else {
            // New one-step flow: Pre-fill from driver profile
            loadDriverProfile()
        }

        binding.btnAddHelper.setOnClickListener { addHelperView() }
        binding.btnSubmit.setOnClickListener { submitTrip() }
        binding.btnBack.setOnClickListener { finish() }
    }

    private fun loadDriverProfile() {
        val session = SessionManager(this)
        val uid = session.getDriverId() ?: return

        setLoading(true)
        lifecycleScope.launch {
            try {
                val db = FirebaseFirestore.getInstance()
                val doc = db.collection("drivers").document(uid).get().await()
                if (doc.exists()) {
                    val company = doc.getString("truckingCompany") ?: doc.getString("company") ?: ""
                    val truckType = doc.getString("truckType") ?: ""
                    val plateNumber = doc.getString("plateNumber") ?: ""

                    binding.etCompany.setText(company)

                    val typeIndex = truckTypes.indexOf(truckType)
                    if (typeIndex >= 0) {
                        binding.spTruckType.setSelection(typeIndex)
                    }

                    if (plateNumber.contains("-")) {
                        val parts = plateNumber.split("-")
                        binding.etPlatePrefix.setText(parts[0])
                        if (parts.size > 1) binding.etPlateSuffix.setText(parts[1])
                    } else if (plateNumber.isNotEmpty()) {
                        binding.etPlatePrefix.setText(plateNumber)
                    }

                    val helperFull = doc.getString("helper") ?: ""
                    if (helperFull.isNotEmpty()) {
                        val parts = helperFull.split(",")[0].trim().split(" ")
                        if (helperBindings.isEmpty()) addHelperView()
                        val b = helperBindings[0]
                        if (parts.size >= 1) b.etHelperFN.setText(parts[0])
                        if (parts.size >= 3) {
                            b.etHelperMI.setText(parts[1].replace(".", ""))
                            b.etHelperLN.setText(parts.subList(2, parts.size).joinToString(" "))
                        } else if (parts.size == 2) {
                            b.etHelperLN.setText(parts[1])
                        }
                    } else {
                        if (helperBindings.isEmpty()) addHelperView()
                    }
                } else {
                    if (helperBindings.isEmpty()) addHelperView()
                }
            } catch (e: Exception) {
                if (helperBindings.isEmpty()) addHelperView()
            } finally {
                setLoading(false)
                validateHelper()
                validatePlate()
                updateValidationUI()
            }
        }
    }

    private fun fetchQueueData() {
        val qid = queueId ?: return
        setLoading(true)
        lifecycleScope.launch {
            try {
                val db = FirebaseFirestore.getInstance()
                val doc = db.collection("queue").document(qid).get().await()
                if (doc.exists()) {
                    val company = doc.getString("truckCompany") ?: doc.getString("company") ?: ""
                    val truckType = doc.getString("truckType") ?: ""
                    val plateNumber = doc.getString("plateNumber") ?: ""
                    
                    binding.etCompany.setText(company)
                    
                    val typeIndex = truckTypes.indexOf(truckType)
                    if (typeIndex >= 0) {
                        binding.spTruckType.setSelection(typeIndex)
                    }

                    if (plateNumber.contains("-")) {
                        val parts = plateNumber.split("-")
                        binding.etPlatePrefix.setText(parts[0])
                        if (parts.size > 1) binding.etPlateSuffix.setText(parts[1])
                    } else if (plateNumber.isNotEmpty()) {
                        binding.etPlatePrefix.setText(plateNumber)
                    }

                    val helperFull = doc.getString("helper") ?: ""
                    if (helperFull.isNotEmpty()) {
                        val parts = helperFull.split(",")[0].trim().split(" ")
                        if (helperBindings.isEmpty()) addHelperView()
                        val b = helperBindings[0]
                        if (parts.size >= 1) b.etHelperFN.setText(parts[0])
                        if (parts.size >= 3) {
                            b.etHelperMI.setText(parts[1].replace(".", ""))
                            b.etHelperLN.setText(parts.subList(2, parts.size).joinToString(" "))
                        } else if (parts.size == 2) {
                            b.etHelperLN.setText(parts[1])
                        }
                    } else {
                        if (helperBindings.isEmpty()) addHelperView()
                    }
                } else {
                    if (helperBindings.isEmpty()) addHelperView()
                }
            } catch (e: Exception) {
                if (helperBindings.isEmpty()) addHelperView()
            } finally {
                setLoading(false)
            }
        }
    }

    private fun updateValidationUI() {
        // ── Section indicators in form ──
        updateIndicator(binding.indicatorHelper, helperDone)
        updateIndicator(binding.indicatorTruck, truckDone)
        updateIndicator(binding.indicatorPlate, plateDone)
        updateIndicator(binding.indicatorRequirements, requirementsDone)

        // ── Pill status bar ──
        updateIndicator(binding.pillHelper, helperDone)
        updateIndicator(binding.pillTruck, truckDone)
        updateIndicator(binding.pillPlate, plateDone)
        updateIndicator(binding.pillRequirements, requirementsDone)

        // Connector lines: fill green if section before is done
        val colorDone = ContextCompat.getColor(this, com.sysu.go.R.color.sysu_green)
        val colorEmpty = ContextCompat.getColor(this, com.sysu.go.R.color.border)
        binding.line1.setBackgroundColor(if (helperDone) colorDone else colorEmpty)
        binding.line2.setBackgroundColor(if (truckDone) colorDone else colorEmpty)
        binding.line3.setBackgroundColor(if (requirementsDone) colorDone else colorEmpty)

        // ── Submit button enabled only when all 3 form sections done ──
        // requirementsDone is always true (checkboxes, not a blocker)
        val allDone = helperDone && truckDone && plateDone && requirementsDone
        binding.btnSubmit.isEnabled = allDone
        binding.btnSubmit.alpha = if (allDone) 1.0f else 0.38f
        binding.btnSubmit.backgroundTintList = if (allDone) {
            ContextCompat.getColorStateList(this, com.sysu.go.R.color.sysu_red)
        } else {
            ContextCompat.getColorStateList(this, com.sysu.go.R.color.text_muted)
        }
    }

    private fun updateIndicator(view: android.view.View, done: Boolean) {
        view.setBackgroundResource(
            if (done) com.sysu.go.R.drawable.indicator_done
            else com.sysu.go.R.drawable.indicator_empty
        )
    }

    private fun validateHelper() {
        helperDone = helperBindings.isNotEmpty() && helperBindings.all { b ->
            b.etHelperFN.text.toString().trim().isNotEmpty() &&
            b.etHelperLN.text.toString().trim().isNotEmpty()
        }
    }

    private fun validatePlate() {
        val px = binding.etPlatePrefix.text.toString().trim()
        val sx = binding.etPlateSuffix.text.toString().trim()
        plateDone = px.length == 3 && sx.length in 3..4
    }

    private fun addHelperView() {
        val inflater = LayoutInflater.from(this)
        val helperBinding = ItemHelperInputBinding.inflate(inflater, binding.helpersContainer, true)
        helperBindings.add(helperBinding)

        updateHelperLabels()

        // Watch each field for changes to update validation state
        val helperWatcher = object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                validateHelper()
                updateValidationUI()
            }
        }
        helperBinding.etHelperFN.addTextChangedListener(helperWatcher)
        helperBinding.etHelperMI.addTextChangedListener(helperWatcher)
        helperBinding.etHelperLN.addTextChangedListener(helperWatcher)

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

        // Spinner validation watcher — only reacts to USER changes, not programmatic setSelection()
        binding.spTruckType.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, pos: Int, id: Long) {
                val fromUser = (parent?.selectedItem != null)
                // Only update truckDone from user interaction
                // Profile/setSelection calls come from adapter init (pos=1=first real item),
                // we skip those since truckDone is pre-set to true for pre-filled screens
                updateValidationUI()
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        // Plate field watchers
        val plateWatcher = object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                validatePlate()
                updateValidationUI()
            }
        }
        binding.etPlatePrefix.addTextChangedListener(plateWatcher)
        binding.etPlateSuffix.addTextChangedListener(plateWatcher)

        // Initial validation pass
        updateValidationUI()
    }

    private fun setupRequirements() {
        requirementsAdapter = RequirementEditAdapter { _, _ ->
            // Debounced auto-save whenever a checkbox is toggled
            scheduleRequirementsSave()
        }
        binding.rvRequirements.layoutManager = LinearLayoutManager(this)
        binding.rvRequirements.adapter = requirementsAdapter
        requirementsAdapter.submitList(RequirementData.getAll())
    }

    private var requirementsSaveJob: kotlinx.coroutines.Job? = null

    private fun scheduleRequirementsSave() {
        requirementsSaveJob?.cancel()
        requirementsSaveJob = lifecycleScope.launch {
            kotlinx.coroutines.delay(800) // debounce rapid toggles
            saveRequirementsToProfile()
        }
    }

    private fun saveRequirementsToProfile() {
        val session = SessionManager(this)
        val uid = session.getDriverId() ?: return

        val updates = mapOf(
            "requirements" to requirementsAdapter.getFireStoreMap()
        )

        FirebaseFirestore.getInstance().collection("drivers").document(uid)
            .update(updates)
            .addOnFailureListener {
                // Silent fail — driver can retry or save again later
            }
    }

    private fun loadSavedRequirements(uid: String) {
        FirebaseFirestore.getInstance().collection("drivers").document(uid)
            .get()
            .addOnSuccessListener { doc ->
                @Suppress("UNCHECKED_CAST")
                val reqMap = doc.get("requirements") as? Map<String, Boolean>
                if (reqMap != null) {
                    requirementsAdapter.setRequirementsMap(reqMap)
                }
            }
    }

    private fun submitTrip() {
        val company = Sanitizer.sanitize(binding.etCompany.text.toString().trim(), 100)
        val platePrefix = Sanitizer.sanitizePlatePrefix(binding.etPlatePrefix.text.toString().trim())
        val plateSuffix = Sanitizer.sanitizePlateSuffix(binding.etPlateSuffix.text.toString().trim())
        val truckType = binding.spTruckType.selectedItem?.toString() ?: ""

        var hasError = false

        if (platePrefix.length != 3) {
            binding.tilPlatePrefix.error = "Exactly 3 letters"
            hasError = true
        }
        if (plateSuffix.length !in 3..4) {
            binding.tilPlateSuffix.error = "3-4 digits required"
            hasError = true
        }

        val helpersList = mutableListOf<Triple<String, String, String>>()

        helperBindings.forEach { b ->
            val fn = Sanitizer.sanitize(b.etHelperFN.text.toString().trim(), 50)
            val mi = Sanitizer.sanitizeMI(b.etHelperMI.text.toString().trim())
            val ln = Sanitizer.sanitize(b.etHelperLN.text.toString().trim(), 50)

            if (fn.isEmpty()) {
                b.tilHelperFN.error = "Required"
                hasError = true
            }
            if (ln.isEmpty()) {
                b.tilHelperLN.error = "Required"
                hasError = true
            }
            if (!hasError) {
                helpersList.add(Triple(fn, mi, ln))
            }
        }

        if (hasError) {
            Toast.makeText(this, "Please fix errors", Toast.LENGTH_SHORT).show()
            return
        }

        setLoading(true)

        lifecycleScope.launch {
            val idToken = FirebaseAuthManager.getIdToken()
            if (idToken == null) {
                setLoading(false)
                Toast.makeText(this@TripRegistrationActivity, "Auth session expired", Toast.LENGTH_SHORT).show()
                return@launch
            }

            val session = SessionManager(this@TripRegistrationActivity)
            val driverId = session.getDriverId() ?: return@launch
            val driverPhone = session.getDriverPhone()
            val driverName = session.getDriverName()
            val (driverFN, driverMI, driverLN) = session.getDriverNameParts()

            // Prepare helper names for the backend
            val firstHelper = helpersList.first()
            val otherHelpers = if (helpersList.size > 1) {
                ", " + helpersList.drop(1).joinToString(", ") { (f, m, l) ->
                    buildString {
                        append(f)
                        if (m.isNotEmpty()) append(" $m")
                        append(" $l")
                    }
                }
            } else ""

            val helperNameJoined = buildString {
                append(firstHelper.first)
                if (firstHelper.second.isNotEmpty()) append(" ${firstHelper.second}")
                append(" ${firstHelper.third}")
                if (otherHelpers.isNotEmpty()) append(otherHelpers)
            }

            // PHASE 1: Join Queue if we only have a token
            if (queueId == null && currentToken != null) {
                val fullPlate = "$platePrefix-$plateSuffix"
                val scanRequest = com.sysu.go.model.ScanRequest(
                    token = currentToken!!,
                    driverId = driverId,
                    driverName = driverName,
                    driverFN = driverFN,
                    driverMI = driverMI,
                    driverLN = driverLN,
                    truckType = truckType,
                    truckCompany = company,
                    plateNumber = fullPlate,
                    helperName = helperNameJoined,
                    driverPhone = driverPhone
                )

                val scanResult = withContext(Dispatchers.IO) {
                    QRApiService.scanToken(scanRequest)
                }

                when (scanResult) {
                    is QRApiService.Result.Success -> {
                        queueId = scanResult.data.queueId
                    }
                    is QRApiService.Result.Error -> {
                        if (scanResult.code != 400) {
                            setLoading(false)
                            Toast.makeText(this@TripRegistrationActivity, "Queue Error: ${scanResult.message}", Toast.LENGTH_LONG).show()
                            return@launch
                        }
                        // If 400 (already in queue), we'll try to identify by driverId later or proceed if possible
                    }
                }
            }

            // PHASE 2: Register Trip / Update Details
            if (queueId != null) {
                // Flush any pending requirements save before registering
                saveRequirementsToProfile()

                val request = TripApiService.TripRegisterRequest(
                    queueId = queueId!!,
                    helperFN = firstHelper.first,
                    helperMI = firstHelper.second,
                    helperLN = firstHelper.third + otherHelpers,
                    company = company,
                    truckType = truckType,
                    platePrefix = platePrefix,
                    plateSuffix = plateSuffix,
                    driverPhone = driverPhone
                )

                val result = withContext(Dispatchers.IO) {
                    TripApiService.registerTrip(request, idToken)
                }
                setLoading(false)

                when (result) {
                    is TripApiService.Result.Success -> {
                        Toast.makeText(this@TripRegistrationActivity, "Trip Registered!", Toast.LENGTH_SHORT).show()
                        navigateToHome()
                    }
                    is TripApiService.Result.Error -> {
                        if (result.code == 500 || result.code == 409) {
                            navigateToHome()
                        } else {
                            AlertDialog.Builder(this@TripRegistrationActivity)
                                .setTitle("Failed")
                                .setMessage(result.message)
                                .setPositiveButton("OK", null)
                                .show()
                        }
                    }
                }
            } else {
                setLoading(false)
                Toast.makeText(this@TripRegistrationActivity, "Could not identify queue entry", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun navigateToHome() {
        val intent = Intent(this, MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }

    private fun setLoading(loading: Boolean) {
        binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        binding.btnSubmit.isEnabled = !loading
        binding.btnAddHelper.isEnabled = !loading
        // etCompany is a TextView (read-only) — no need to toggle, always static
        binding.etPlatePrefix.isEnabled = !loading
        binding.etPlateSuffix.isEnabled = !loading
        binding.spTruckType.isEnabled = !loading
        binding.rvRequirements.isEnabled = !loading
        helperBindings.forEach {
            it.etHelperFN.isEnabled = !loading
            it.etHelperMI.isEnabled = !loading
            it.etHelperLN.isEnabled = !loading
            it.btnRemoveHelper.isEnabled = !loading
        }
    }
}