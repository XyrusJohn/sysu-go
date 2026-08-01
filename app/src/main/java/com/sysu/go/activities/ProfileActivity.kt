package com.sysu.go.activities

import android.view.animation.AccelerateDecelerateInterpolator
import android.animation.Animator
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import com.sysu.go.utils.Sanitizer
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.appcompat.app.AlertDialog
import com.google.android.material.button.MaterialButton
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import android.content.SharedPreferences
import com.sysu.go.R
import com.sysu.go.databinding.ActivityProfileBinding
import com.sysu.go.firebase.FirebaseAuthManager
import com.sysu.go.network.SocketManager
import com.sysu.go.utils.SessionManager
import org.json.JSONObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class ProfileActivity : AppCompatActivity() {

    private lateinit var binding: ActivityProfileBinding
    private val db = FirebaseFirestore.getInstance()
    private val prefs: SharedPreferences by lazy {
        getSharedPreferences("go_prefs", MODE_PRIVATE)
    }
    private val circleAnimators = mutableListOf<Animator>()
    private val circleViews = mutableListOf<View>()
    private var driverId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityProfileBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        setupUI()
        loadDriverInfo()
        startCircleAnimation()

        binding.btnEditRequirements.setOnClickListener {
            driverId?.let { id ->
                RequirementsEditBottomSheet.newInstance(id)
                    .show(supportFragmentManager, RequirementsEditBottomSheet.TAG)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        loadDriverInfo()
        loadActiveShipments()
    }

    private fun startCircleAnimation() {
        binding.circleContainer.post {
            val containerW = binding.circleContainer.width
            val containerH = binding.circleContainer.height
            val random = java.util.Random()

            val count = random.nextInt(3) + 2
            for (i in 0 until count) {
                val sizeDp = 30 + random.nextInt(81)
                val sizePx = (sizeDp * resources.displayMetrics.density).toInt()
                val startX = random.nextInt(containerW).toFloat()
                val startY = random.nextInt(containerH).toFloat()
                val minSpeed = 0.15f + random.nextFloat() * 0.15f
                val maxSpeed = minSpeed + 0.15f + random.nextFloat() * 0.2f
                val alpha = 0.25f + random.nextFloat() * 0.25f

                val circle = View(this).apply {
                    layoutParams = FrameLayout.LayoutParams(sizePx, sizePx)
                    background = ContextCompat.getDrawable(this@ProfileActivity, R.drawable.bg_circle_light_red)
                    this.alpha = alpha
                }

                binding.circleContainer.addView(circle)
                circleViews.add(circle)
                startBounceAnimation(circle, sizePx, containerW, containerH, minSpeed, maxSpeed, startX, startY, (i * 200L) + random.nextInt(400))
            }
        }
    }

    private fun startBounceAnimation(
        view: View,
        viewSize: Int,
        containerWidth: Int,
        containerHeight: Int,
        minSpeed: Float,
        maxSpeed: Float,
        initialX: Float,
        initialY: Float,
        startDelayMs: Long
    ) {
        val random = java.util.Random()
        val maxX = (containerWidth - viewSize).toFloat().coerceAtLeast(0f)
        val maxY = (containerHeight - viewSize).toFloat().coerceAtLeast(0f)

        view.translationX = initialX
        view.translationY = initialY

        var velX = (random.nextFloat() * (maxSpeed - minSpeed) + minSpeed) * if (random.nextBoolean()) 1f else -1f
        var velY = (random.nextFloat() * (maxSpeed - minSpeed) + minSpeed) * if (random.nextBoolean()) 1f else -1f

        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 16L
            repeatCount = ValueAnimator.INFINITE
            this.startDelay = startDelayMs
            addUpdateListener {
                if (random.nextFloat() < 0.02f) {
                    velX += (random.nextFloat() - 0.5f) * 0.3f
                    velX = velX.coerceIn(-maxSpeed, maxSpeed)
                }
                if (random.nextFloat() < 0.02f) {
                    velY += (random.nextFloat() - 0.5f) * 0.3f
                    velY = velY.coerceIn(-maxSpeed, maxSpeed)
                }

                var newX = view.translationX + velX
                var newY = view.translationY + velY

                if (newX <= 0) {
                    newX = 0f
                    velX = kotlin.math.abs(velX).coerceIn(minSpeed, maxSpeed)
                } else if (newX >= maxX) {
                    newX = maxX
                    velX = -kotlin.math.abs(velX).coerceIn(minSpeed, maxSpeed)
                }

                if (newY <= 0) {
                    newY = 0f
                    velY = kotlin.math.abs(velY).coerceIn(minSpeed, maxSpeed)
                } else if (newY >= maxY) {
                    newY = maxY
                    velY = -kotlin.math.abs(velY).coerceIn(minSpeed, maxSpeed)
                }

                view.translationX = newX
                view.translationY = newY
            }
            circleAnimators.add(this)
            start()
        }

        ObjectAnimator.ofFloat(view, "scaleX", 1f, 1.08f).apply {
            duration = 4000L + random.nextInt(1500)
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            startDelay = startDelayMs
            circleAnimators.add(this)
            start()
        }

        ObjectAnimator.ofFloat(view, "scaleY", 1f, 1.06f).apply {
            duration = 4500L + random.nextInt(1500)
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            startDelay = startDelayMs + 600L
            circleAnimators.add(this)
            start()
        }
    }

    @SuppressLint("SetTextI18n")
    private fun setupUI() {
        val session = SessionManager(this)

        // Set driver name
        val driverName = session.getDriverName() ?: "Driver"
        binding.tvDriverName.text = driverName
        binding.tvAvatarInitial.text = driverName.firstOrNull()?.uppercaseChar()?.toString() ?: "D"

        // Set email
        binding.tvEmail.text = session.getDriverEmail() ?: "Not available"

        // Set full name - build from stored parts
        val fn = session.getDriverFN() ?: ""
        val mi = session.getDriverMI() ?: ""
        val ln = session.getDriverLN() ?: ""
        binding.tvFullName.text = if (fn.isNotEmpty() && ln.isNotEmpty()) {
            val miPart = if (mi.isNotEmpty()) " ${mi.replace(".", "")}." else ""
            "$fn$miPart $ln"
        } else {
            driverName
        }

        // Set phone
        binding.tvPhone.text = session.getDriverPhone() ?: "Not available"

        // Set company from session (instant, no waiting for Firestore).
        // Strip any forwarder code / truck-company number and force ALL CAPS so
        // "001-LAZADA SHIPPING" surfaces as "LAZADA SHIPPING" only.
        val companyDisplay = Sanitizer.sanitizeCompanyName(session.getDriverCompany())
        binding.tvCompany.text = companyDisplay.ifEmpty { "Not set" }

        // Requirements section — always show. Defaults to all unchecked (false).
        // Firestore callback (loadRequirements) will merge saved values on top.
        binding.requirementsSection.visibility = View.VISIBLE
        binding.rvRequirements.layoutManager = LinearLayoutManager(this)
        val defaultAdapter = RequirementDisplayAdapter()
        binding.rvRequirements.adapter = defaultAdapter
        val defaultList = listOf(
            "aircon", "reflectorizedVest", "safetyShoes",
            "headcap", "pants", "closedShoes"
        ).map { it to false }
        defaultAdapter.submitList(defaultList)

        // Set app version
        try {
            val pInfo = packageManager.getPackageInfo(packageName, 0)
            binding.tvAppVersion.text = pInfo.versionName
        } catch (e: PackageManager.NameNotFoundException) {
            binding.tvAppVersion.text = "1.0.0"
        }

        // Back button
        binding.btnBack.setOnClickListener {
            finish()
        }

        // Logout button
        binding.btnLogout.setOnClickListener {
            logout()
        }

        // Tutorials toggle
        val tutorialsEnabled = prefs.getBoolean("tutorials_enabled", true)
        binding.switchTutorials.isChecked = tutorialsEnabled
        binding.switchTutorials.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit()
                    .putBoolean("tutorials_enabled", isChecked)
                    .apply()
        }

        // Load active shipment count + driver status
        loadActiveShipments()
    }

    private fun updateStatusBadge(status: String, backgroundRes: Int, textColorRes: Int) {
        binding.tvStatusBadgeText.text = status
        binding.statusBadge.setBackgroundResource(backgroundRes)
        binding.tvStatusBadgeText.setTextColor(ContextCompat.getColor(this, textColorRes))
    }

    private fun loadActiveShipments() {
        val session = SessionManager(this)
        val uid = session.getDriverId() ?: return

        // queueDocId (queue document ID) is used as driverId in sap_assignments
        // sessionDriverId (Firebase UID) is used as driverId in the queue collection
        val queueDocIdOrUid = session.getCurrentQueueDocId() ?: uid

        CoroutineScope(Dispatchers.Main).launch {
            try {
                // Queue check uses Firebase UID (driverId field in queue collection)
                val queueSnap = db.collection("queue")
                    .whereEqualTo("driverId", uid)
                    .get()
                    .await()

                val inQueue = queueSnap.documents.any {
                    val s = it.getString("status")
                    s != null && s != "left"
                }

                // sap_assignments uses queue doc ID as driverId (not Firebase UID)
                val snaps = db.collection("sap_assignments")
                    .whereEqualTo("driverId", queueDocIdOrUid)
                    .get()
                    .await()

                val list = snaps.documents.mapNotNull { snap ->
                    snap.data as? Map<String, Any?>
                }

                val count = list.size
                val hasInTransit = list.any {
                    val t = it["tripStatus"] as? String
                    t == "in_transit" || t == "returning_to_base"
                }
                val hasAssigned = list.any {
                    (it["guardVerified"] as? Boolean) == true &&
                        (it["driverConfirmed"] as? Boolean) == true
                }

                when {
                    !inQueue && count == 0 -> {
                        updateStatusBadge("Not in queue", R.drawable.bg_badge_frosted_not_in_queue, R.color.status_not_in_queue)
                        binding.tvAssignedCount.visibility = View.GONE
                    }
                    hasInTransit -> {
                        updateStatusBadge("In transit", R.drawable.bg_badge_frosted_transit, R.color.status_transit_green)
                        binding.tvAssignedCount.visibility = View.VISIBLE
                        binding.tvAssignedCount.text = if (count == 1) "Assigned: 1 shipment" else "Assigned: $count shipments"
                    }
                    hasAssigned -> {
                        updateStatusBadge("Assigned", R.drawable.bg_badge_frosted_assigned, R.color.status_assigned)
                        binding.tvAssignedCount.visibility = View.VISIBLE
                        binding.tvAssignedCount.text = if (count == 1) "Assigned: 1 shipment" else "Assigned: $count shipments"
                    }
                    count > 0 -> {
                        updateStatusBadge("In queue", R.drawable.bg_badge_frosted_queue, R.color.status_in_queue)
                        binding.tvAssignedCount.visibility = View.VISIBLE
                        binding.tvAssignedCount.text = if (count == 1) "Assigned: 1 shipment" else "Assigned: $count shipments"
                    }
                    inQueue -> {
                        updateStatusBadge("In queue", R.drawable.bg_badge_frosted_queue, R.color.status_in_queue)
                        binding.tvAssignedCount.visibility = View.GONE
                    }
                    else -> {
                        updateStatusBadge("Not in queue", R.drawable.bg_badge_frosted_not_in_queue, R.color.status_not_in_queue)
                        binding.tvAssignedCount.visibility = View.GONE
                    }
                }
            } catch (e: Exception) {
                updateStatusBadge("Not in queue", R.drawable.bg_badge_frosted_not_in_queue, R.color.status_not_in_queue)
                binding.tvAssignedCount.visibility = View.GONE
            }
        }
    }

    private fun loadDriverInfo() {
        val session = SessionManager(this)
        val uid = session.getDriverId() ?: return
        driverId = uid

        CoroutineScope(Dispatchers.Main).launch {
            try {
                val doc = db.collection("drivers").document(uid).get().await()
                if (doc.exists()) {
                    val company = doc.getString("truckingCompany") ?: doc.getString("company") ?: ""
                    val phone = doc.getString("phone") ?: ""

                    // Update SessionManager with fresh data from Firestore.
                    // Rebuild display name from FN/MI/LN parts so we don't propagate
                    // any legacy double-dot value that might still be in `name`.
                    val fn = session.getDriverFN()
                    val mi = session.getDriverMI()
                    val ln = session.getDriverLN()
                    val cleanName = buildString {
                        append(fn)
                        if (mi.isNotEmpty()) append(" ${mi.replace(".", "")}.")
                        if (ln.isNotEmpty()) append(" $ln")
                    }.trim().ifEmpty { session.getDriverName() }

                    session.saveDriverSessionWithSplit(
                        uid = uid,
                        fn = fn,
                        mi = mi,
                        ln = ln,
                        displayName = cleanName,
                        email = session.getDriverEmail() ?: "",
                        phone = if (phone.isNotEmpty()) phone else session.getDriverPhone() ?: "",
                        company = company
                    )

                    binding.tvCompany.text = Sanitizer.sanitizeCompanyName(company).ifEmpty { "Not set" }
                    if (phone.isNotEmpty()) {
                        binding.tvPhone.text = phone
                    }

                    @Suppress("UNCHECKED_CAST")
                    val reqMap = doc.get("requirements") as? Map<String, Boolean>
                    if (reqMap != null) {
                        loadRequirements(reqMap)
                    }
                }
            } catch (e: Exception) {
                // Silently fail - profile will show placeholder values
            }
        }
    }

    private fun logout() {
        val dlgView = LayoutInflater.from(this).inflate(R.layout.dialog_sysu_confirmation, null)
        dlgView.findViewById<ImageView>(R.id.ivDialogIcon).setImageResource(R.drawable.ic_dialog_destructive)
        dlgView.findViewById<TextView>(R.id.tvDialogTitle).text = "Logout"
        dlgView.findViewById<TextView>(R.id.tvDialogMessage).text = "Are you sure you want to sign out?"
        val dialog = AlertDialog.Builder(this).setView(dlgView).create()
        dlgView.findViewById<MaterialButton>(R.id.btnConfirm).apply {
            text = "Sign Out"
            backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this@ProfileActivity, R.color.sysu_red))
            setOnClickListener { dialog.dismiss()
                FirebaseAuth.getInstance().signOut()
                SessionManager(this@ProfileActivity).clearSession()
                FirebaseAuthManager.signOut(this@ProfileActivity)
                val intent = Intent(this@ProfileActivity, LoginActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                startActivity(intent)
                finish()
            }
        }
        dlgView.findViewById<MaterialButton>(R.id.btnCancel).setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun loadRequirements(reqMap: Map<String, Boolean>) {
        binding.requirementsSection.visibility = View.VISIBLE

        val adapter = binding.rvRequirements.adapter as? RequirementDisplayAdapter
            ?: RequirementDisplayAdapter().also { binding.rvRequirements.adapter = it }

        val displayList = listOf(
            "aircon", "reflectorizedVest", "safetyShoes",
            "headcap", "pants", "closedShoes"
        ).mapNotNull { key ->
            reqMap[key]?.let { checked -> key to checked }
        }

        adapter.submitList(displayList)
    }

    override fun onDestroy() {
        super.onDestroy()
        circleAnimators.forEach { it.cancel() }
        circleAnimators.clear()
        circleViews.forEach { binding.circleContainer.removeView(it) }
        circleViews.clear()
    }
}