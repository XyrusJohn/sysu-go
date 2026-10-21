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
import android.widget.LinearLayout
import android.widget.Toast
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
import com.sysu.go.utils.ConnectivityMonitor
import com.sysu.go.utils.PermissionHelper
import com.sysu.go.utils.SessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import android.graphics.Typeface
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import com.google.firebase.Timestamp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ProfileActivity : AppCompatActivity(), ConnectivityMonitor.ConnectivityChangeListener {

    private lateinit var binding: ActivityProfileBinding
    private val db = FirebaseFirestore.getInstance()
    private val prefs: SharedPreferences by lazy {
        getSharedPreferences("go_prefs", MODE_PRIVATE)
    }
    private val circleAnimators = mutableListOf<Animator>()
    private val circleViews = mutableListOf<View>()
    private var driverId: String? = null

    // Status badge â€” uses Firestore snapshot listener on drivers/{uid}.status.
    // Source of truth: drivers/{uid}.status field. With offline persistence enabled
    // (GoApplication.kt), the snapshot listener reads from local cache when offline
    // and gets live updates when online. This is the simplest and most reliable path.
    private var driverStatusListener: (() -> Unit)? = null

    // Cached value from the Firestore snapshot listener. We keep this around because
    // connectivity changes don't affect Firestore's view of the lifecycle state
    // ("in_transit", "returning_to_base") â€” only the offline/online indicator.
    @Volatile
    private var lastFirestoreStatus: String = "online"

    // Last connectivity status we painted the badge for. We use this to skip
    // redundant re-renders from the fallback poll, and to detect missed events.
    @Volatile
    private var lastPaintedConnectivity: ConnectivityMonitor.Status = ConnectivityMonitor.Status.OFFLINE

    // Fallback poll â€” every 2s, reconcile badge with current ConnectivityMonitor
    // state. This is a belt-and-suspenders defense: even if the listener
    // somehow misses a transition (e.g., NetworkCallback glitch on this OEM),
    // the badge still catches up within 2s. Lightweight â€” just compares a
    // status enum and re-renders if different.
    private val connectivityPoller = android.os.Handler(android.os.Looper.getMainLooper())
    private val connectivityPollRunnable = object : Runnable {
        override fun run() {
            val current = ConnectivityMonitor.getCurrentStatus()
            if (current != lastPaintedConnectivity) {
                Log.w("ProfileActivity", ">>> [POLL] detected missed transition: $lastPaintedConnectivity â†’ $current")
                onConnectivityChanged(current)
            }
            connectivityPoller.postDelayed(this, 2000L)
        }
    }

    // Server-pushed driver_status_change listener. The server's presence
    // watcher (backend/utils/presenceWatcher.js) is now authoritative for
    // OFFLINE detection â€” when it sees a stale driver, it writes "offline"
    // to Firestore AND broadcasts driver_status_change to room driver:{uid}.
    // We listen for that broadcast and flip the badge IMMEDIATELY, without
    // waiting for the Firestore snapshot to land (which may take a moment
    // due to listener dispatch + cache update).
    private val serverStatusListener: (Any?) -> Unit = listener@{ data ->
        try {
            val json = data as? org.json.JSONObject ?: return@listener
            val status = json.optString("status", "online")
            val source = json.optString("source", "unknown")
            Log.w("ProfileActivity", ">>> [SERVER] driver_status_change â†’ $status (source=$source)")
            // Server is authoritative for OFFLINE. For ONLINE / in_transit /
            // returning_to_base, prefer the Firestore snapshot (which may
            // include additional lifecycle fields), but the server push is
            // fine for the badge too.
            lastFirestoreStatus = status
            if (ConnectivityMonitor.getCurrentStatus() != ConnectivityMonitor.Status.OFFLINE) {
                renderStatusBadge(status)
                lastPaintedConnectivity = ConnectivityMonitor.getCurrentStatus()
            }
        } catch (e: Exception) {
            Log.w("ProfileActivity", ">>> [SERVER] driver_status_change parse error: ${e.message}")
        }
    }

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

        binding.btnOpenAppInfo.setOnClickListener {
            PermissionHelper.openAppSettings(this)
        }
    }

    override fun onResume() {
        super.onResume()
        loadDriverInfo()
        refreshPermissionStates()
        SocketManager.connect(this)
        // Subscribe to drivers/{uid}.status â€” Firestore snapshot listener.
        // Reads from local cache (offline) per GoApplication persistence config.
        setupDriverStatusListener()
        // Subscribe to ConnectivityMonitor so the badge flips OFFLINE/ONLINE
        // IMMEDIATELY when WiFi is toggled, without waiting for the Firestore
        // write to round-trip through the (potentially flaky) network.
        ConnectivityMonitor.addListener(this)
        // Subscribe to server-pushed driver_status_change broadcasts. The
        // server's presence watcher is authoritative for OFFLINE â€” when it
        // detects a stale driver, it broadcasts driver_status_change:offline
        // and we flip the badge without waiting for Firestore.
        SocketManager.addEventListener("driver_status_change", serverStatusListener)
        // Start the 2-second poll so we still catch up even if the listener
        // somehow misses an event (defensive â€” covers OEM-specific quirks).
        lastPaintedConnectivity = ConnectivityMonitor.Status.OFFLINE  // force first paint to take effect
        connectivityPoller.post(connectivityPollRunnable)
    }

    /**
     * Checks and updates UI reflecting live OS permission statuses:
     * - Location (GPS)
     * - Unrestricted Battery (Doze bypass)
     * - System Notifications
     * - Camera
     */
    private fun refreshPermissionStates() {
        val hasLoc = PermissionHelper.hasLocationPermission(this)
        val hasBatt = PermissionHelper.hasBatteryOptimizationDisabled(this)
        val hasNotif = PermissionHelper.hasNotificationPermission(this)
        val hasCam = PermissionHelper.hasCameraPermission(this)

        fun updateBadge(tv: TextView, granted: Boolean) {
            tv.text = if (granted) "Active" else "Missing / Denied"
            tv.setTextColor(
                ContextCompat.getColor(
                    this,
                    if (granted) R.color.transit_green else R.color.sysu_red
                )
            )
        }

        updateBadge(binding.tvStatusPermLocation, hasLoc)
        updateBadge(binding.tvStatusPermBattery, hasBatt)
        updateBadge(binding.tvStatusPermNotification, hasNotif)
        updateBadge(binding.tvStatusPermCamera, hasCam)

        val allOk = hasLoc && hasBatt && hasNotif && hasCam
        binding.permSectionAccent.setBackgroundColor(
            ContextCompat.getColor(this, if (allOk) R.color.transit_green else R.color.sysu_red)
        )
        binding.tvPermOverview.text = if (allOk) {
            "Lahat ng Background & Tracking Permissions: MAAYOS (ACTIVE)"
        } else {
            "May Kulang na Permission para sa Background Tracking!"
        }
        binding.tvPermOverview.setTextColor(
            ContextCompat.getColor(this, if (allOk) R.color.text_muted else R.color.sysu_red)
        )

        binding.btnOpenAppInfo.text = if (allOk) {
            "Buksan ang App Info / Settings"
        } else {
            "I-Grant o Ayusin sa App Settings"
        }
    }

    override fun onPause() {
        super.onPause()
        teardownDriverStatusListener()
        ConnectivityMonitor.removeListener(this)
        SocketManager.removeEventListener("driver_status_change", serverStatusListener)
        connectivityPoller.removeCallbacks(connectivityPollRunnable)
    }

    /**
     * React to local connectivity changes for the badge only. We do NOT write
     * to Firestore here â€” DriverStatus already does that via ConnectivityMonitor's
     * own listener. The badge just needs to render the right thing *now*, before
     * the Firestore write completes (which can take seconds when DNS is broken).
     *
     * Lifecycle state (in_transit / returning_to_base) is preserved from the last
     * Firestore snapshot so we don't clobber it with a generic ONLINE/OFFLINE.
     */
    override fun onConnectivityChanged(status: ConnectivityMonitor.Status) {
        Log.w("ProfileActivity", ">>> [BADGE] connectivity â†’ $status (lastFirestoreStatus=$lastFirestoreStatus, lastPainted=$lastPaintedConnectivity)")
        lastPaintedConnectivity = status
        if (status == ConnectivityMonitor.Status.OFFLINE) {
            // Connectivity is OFFLINE â€” show OFFLINE badge immediately, regardless
            // of whatever lifecycle state the Firestore cache happens to hold.
            // This is the CRITICAL path the user hit: Firestore write is queued
            // offline, but the badge MUST flip immediately so the user sees reality.
            renderStatusBadge("offline")
            Log.w("ProfileActivity", ">>> [BADGE] âœ“ OFFLINE badge rendered (immediate, no Firestore wait)")
        } else {
            // Connectivity is back. Re-render with whatever lifecycle state we last
            // saw from Firestore. If that state was also "offline" (because the
            // server write hadn't flushed yet), render "online" now.
            val effective = if (lastFirestoreStatus == "offline") "online" else lastFirestoreStatus
            renderStatusBadge(effective)
            Log.w("ProfileActivity", ">>> [BADGE] âœ“ re-rendered with state='$effective'")
        }
    }

    /**
     * Subscribes to drivers/{uid}.status via Firestore snapshot listener.
     *
     * Persistence is enabled in GoApplication.kt, so:
     *   - When online: snapshot listener fires whenever the server value changes
     *   - When offline: snapshot listener serves from local cache (last known value)
     *   - When transitioning offline â†’ online: persisted writes flush, listener fires
     *
     * This is the most reliable path because Firestore SDK handles all the
     * networking, caching, and reconnection logic. We just react to the result.
     */
    private fun setupDriverStatusListener() {
        val uid = driverId ?: SessionManager(this).getDriverId() ?: return
        teardownDriverStatusListener()
        driverStatusListener = SocketManager.subscribeRealtime(
            topic = "driver",
            onData = { payload ->
                val data = payload as? Map<*, *>
                val status = data?.get("status") as? String ?: "online"
                lastFirestoreStatus = status
                runOnUiThread {
                    if (!isFinishing && ConnectivityMonitor.getCurrentStatus() != ConnectivityMonitor.Status.OFFLINE) {
                        renderStatusBadge(status)
                    }
                }
            },
            onError = { message ->
                Log.w("ProfileActivity", "driver realtime error: $message")
                com.sysu.go.diagnostics.UserErrorNotifier.showRealtimeFailure(this@ProfileActivity, "profile-subscription")
            },
        )
    }

    private fun teardownDriverStatusListener() {
        driverStatusListener?.invoke()
        driverStatusListener = null
    }
    /**
     * Maps the canonical `drivers/{uid}.status` value to a badge.
     * Values: "online" (default), "offline", "in_transit", "returning_to_base".
     * Anything else falls back to "online".
     */
    private fun renderStatusBadge(status: String) {
        when (status) {
            "offline" -> setBadge(
                getString(R.string.status_badge_offline),
                R.drawable.bg_badge_frosted_offline,
                R.color.status_offline
            )
            "in_transit" -> setBadge(
                getString(R.string.status_badge_in_transit),
                R.drawable.bg_badge_frosted_transit,
                R.color.status_in_transit
            )
            "returning_to_base" -> setBadge(
                getString(R.string.status_badge_returning_to_base),
                R.drawable.bg_badge_frosted_returning_to_base,
                R.color.status_returning_to_base
            )
            "online" -> setBadge(
                getString(R.string.status_badge_online),
                R.drawable.bg_badge_frosted_online,
                R.color.status_online
            )
            else -> setBadge(
                getString(R.string.status_badge_online),
                R.drawable.bg_badge_frosted_online,
                R.color.status_online
            )
        }
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

        // Requirements section â€” always show. Defaults to all unchecked (false).
        // Firestore callback (loadRequirements) will merge saved values on top.
        binding.requirementsSection.visibility = View.VISIBLE
        binding.rvRequirements.layoutManager = LinearLayoutManager(this)
        if (session.getDriverId() != null) {
            binding.rvRequirements.visibility = View.GONE
            binding.requirementsSkeleton.visibility = View.VISIBLE
            binding.btnEditRequirements.isEnabled = false
        }
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

        // History button â€” opens a dialog showing completed trips from the
        // driver_history collection, falling back to the latest completed
        // trip on the driver document if no history records exist.
        val historyButton = MaterialButton(this@ProfileActivity)
        historyButton.text = "View History"
        historyButton.setBackgroundTintList(ContextCompat.getColorStateList(this@ProfileActivity, R.color.sysu_blue))
        historyButton.setTextColor(ContextCompat.getColor(this@ProfileActivity, R.color.white))

        val requirementsParent = binding.requirementsSection.parent as? ViewGroup
        if (requirementsParent != null) {
            val index = requirementsParent.indexOfChild(binding.requirementsSection)
            requirementsParent.addView(historyButton, index)
            historyButton.setOnClickListener { showHistoryDialog() }
        }

        // Assigned-count subtitle â€” one-shot fetch on screen open
        refreshAssignedCount()
    }

    /**
     * Reads the assigned-shipment count from drivers/{uid}.assignedShipments
     * (mirrored from sap_assignments by [DriverShipmentSync]).
     *
     * Replaces the previous approach of querying sap_assignments directly with
     * `whereEqualTo("driverId", queueDocIdOrUid).whereNotEqualTo("tripStatus", "completed")`.
     * The mirrored field is one less Firestore read per screen open and is
     * kept up-to-date in real-time by the listener in GoApplication.
     */
    private fun refreshAssignedCount() {
        val session = SessionManager(this)
        val uid = session.getDriverId() ?: return
        binding.assignedCountSkeleton.visibility = View.VISIBLE
        binding.tvAssignedCount.visibility = View.GONE

        CoroutineScope(Dispatchers.Main).launch {
            try {
                val doc = db.collection("drivers").document(uid).get().await()
                val assigned = doc.get("assignedShipments") as? List<String> ?: emptyList()
                val count = assigned.size
                binding.tvAssignedCount.visibility = if (count > 0) View.VISIBLE else View.GONE
                if (count > 0) {
                    binding.tvAssignedCount.text =
                        if (count == 1) "Assigned: 1 shipment" else "Assigned: $count shipments"
                }
                Log.d("ProfileActivity", "refreshAssignedCount: $count (from drivers.assignedShipments)")
            } catch (e: Exception) {
                Log.e("ProfileActivity", "refreshAssignedCount failed", e)
                binding.tvAssignedCount.visibility = View.GONE
            } finally {
                binding.assignedCountSkeleton.visibility = View.GONE
            }
        }
    }

    private fun setBadge(text: String, bgRes: Int, textColorRes: Int) {
        binding.tvStatusBadgeText.text = text
        binding.statusBadge.setBackgroundResource(bgRes)
        binding.tvStatusBadgeText.setTextColor(ContextCompat.getColor(this, textColorRes))
    }

    private fun loadDriverInfo() {
        val session = SessionManager(this)
        val uid = session.getDriverId() ?: return
        driverId = uid

        CoroutineScope(Dispatchers.Main).launch {
            try {
                val doc = db.collection("drivers").document(uid).get().await()
                if (doc.exists()) {
                    val company = doc.getString("truckingCompany")?.takeIf { it.isNotBlank() }
                        ?: doc.getString("company")?.takeIf { it.isNotBlank() }
                        ?: session.getDriverCompany()
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
            } finally {
                binding.requirementsSkeleton.visibility = View.GONE
                binding.rvRequirements.visibility = View.VISIBLE
                binding.btnEditRequirements.isEnabled = true
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

    private fun showHistoryDialog() {
        val session = SessionManager(this)
        val uid = session.getDriverId() ?: return
        val driverName = session.getDriverName()

        // Build candidate driverIds in priority order:
        //   1. Current queue doc ID â€” kept while the driver is actively in queue.
        //   2. Last known queue doc ID â€” survives a trip's full lifecycle, including
        //      after ReturnToBase clears the current queue key and deletes
        //      `queue.{docId}.driverId` server-side.
        //   3. Firebase UID â€” used by a minority of older sap_assignment docs.
        // We dedupe by set membership so we never run the same query twice.
        val candidateIds = linkedSetOf<String>().apply {
            session.getCurrentQueueDocId()?.takeIf { it.isNotBlank() }?.let(::add)
            session.getLastQueueDocId()?.takeIf { it.isNotBlank() }?.let(::add)
            uid.takeIf { it.isNotBlank() }?.let(::add)
        }.toList()

        val loadingDialog = AlertDialog.Builder(this@ProfileActivity)
            .setTitle("Loading trip history")
            .setView(R.layout.dialog_history_loading)
            .setCancelable(false)
            .create()
        loadingDialog.show()

        CoroutineScope(Dispatchers.Main).launch {
            try {
                // Try each candidate driverId in order. Stop on the first non-empty result.
                var completedSnaps: com.google.firebase.firestore.QuerySnapshot? = null
                for (candidateId in candidateIds) {
                    val snaps = db.collection("sap_assignments")
                        .whereEqualTo("driverId", candidateId)
                        .whereEqualTo("tripStatus", "completed")
                        .get()
                        .await()
                    if (!snaps.isEmpty) {
                        completedSnaps = snaps
                        Log.d("ProfileActivity", "History query hit using driverId='$candidateId' (${snaps.size()} docs)")
                        break
                    }
                }

                // Final fallback: by driverName (works even if driverId was rewritten).
                // Narrow the result set client-side to entries whose driverName matches
                // the current session â€” avoids showing other drivers' trips.
                if (completedSnaps == null || completedSnaps.isEmpty) {
                    val byName = db.collection("sap_assignments")
                        .whereEqualTo("driverName", driverName)
                        .whereEqualTo("tripStatus", "completed")
                        .get()
                        .await()
                    if (!byName.isEmpty) {
                        completedSnaps = byName
                        Log.d("ProfileActivity", "History query hit using driverName='$driverName' (${byName.size()} docs)")
                    }
                }

                loadingDialog.dismiss()

                if (completedSnaps == null || completedSnaps.isEmpty) {
                    Toast.makeText(this@ProfileActivity, "No completed trip found", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                // Sort by completedAt descending (client-side) and take the most recent 10
                val historyList = completedSnaps.documents
                    .sortedByDescending { snap ->
                        val ts = snap.get("completedAt") as? Timestamp
                        ts?.toDate()?.time ?: 0L
                    }
                    .take(10)
                    .mapNotNull { snap ->
                        val data = snap.data as? Map<String, Any?> ?: return@mapNotNull null
                        val completedAt = (data["completedAt"] as? Timestamp)?.toDate()
                        completedAt?.let { data to it }
                    }

                if (historyList.isEmpty()) {
                    Toast.makeText(this@ProfileActivity, "No completed trip found", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                buildHistoryDialog(historyList.map { it.first })
            } catch (e: Exception) {
                loadingDialog.dismiss()
                Log.e("ProfileActivity", "Error loading history: ${e.message}")
                com.sysu.go.diagnostics.UserErrorNotifier.showFailure(this@ProfileActivity, "trip-history", e)
            }
        }
    }

    /**
     * Renders a scrollable dialog listing the driver's recent completed trips.
     * Each trip is shown as a card with the completion timestamp, driver name,
     * truck plate / company, and a per-delivery line-items breakdown.
     */
    private fun buildHistoryDialog(historyList: List<Map<String, Any?>>) {
        val linearLayout = LinearLayout(this@ProfileActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        linearLayout.addView(TextView(this@ProfileActivity).apply {
            text = "Completed Trips"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setTypeface(Typeface.DEFAULT_BOLD)
            setPadding(0, 0, 0, 24)
        })

        val sdf = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())
        val sdfShort = SimpleDateFormat("MMM dd HH:mm", Locale.getDefault())

        historyList.forEachIndexed { tripIndex, entry ->
            val completedAt = (entry["completedAt"] as? Timestamp)?.toDate()
            val driverName = (entry["driverName"] as? String) ?: "Unknown driver"
            val truckPlate = entry["truckPlate"] as? String ?: ""
            val truckCompany = entry["truckCompany"] as? String ?: ""
            val lineItems = (entry["lineItems"] as? List<*>) ?: emptyList<Any?>()
            // Per-shipment return outcome â€” written by ReturnToBaseBottomSheet.performConfirmArrival()
            // alongside tripStatus="completed". Falls back to "â€”" for older docs that pre-date
            // the per-shipment mirror (added 2026-08-10).
            val returnStatus = entry["returnStatus"] as? String ?: ""
            val returnedToBaseAt = (entry["returnedToBaseAt"] as? Timestamp)?.toDate()

            val tripView = LinearLayout(this@ProfileActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 0, 0, 16)
            }

            // Trip header: "Trip #N â€” <date>"
            tripView.addView(TextView(this@ProfileActivity).apply {
                text = if (completedAt != null) {
                    "Trip #${tripIndex + 1} â€” ${sdf.format(completedAt)}"
                } else {
                    "Trip #${tripIndex + 1}"
                }
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setTypeface(Typeface.DEFAULT_BOLD)
                setPadding(0, 0, 0, 8)
            })

            // Trip-level details
            val tripInfo = buildString {
                append("Driver: ").append(driverName).append("\n")
                if (truckPlate.isNotBlank()) append("Truck: ").append(truckPlate).append("\n")
                if (truckCompany.isNotBlank()) append("Company: ").append(truckCompany)
            }
            tripView.addView(TextView(this@ProfileActivity).apply {
                text = tripInfo
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setPadding(0, 0, 0, 8)
            })

            // Return-to-base outcome â€” only shown when the field is populated.
            // Skipped silently for legacy docs so we don't add noise for pre-mirror trips.
            if (returnStatus.isNotBlank()) {
                val returnedLabel = buildString {
                    append("Returned to base: ")
                    when {
                        returnStatus == "completed" && returnedToBaseAt != null ->
                            sdfShort.format(returnedToBaseAt)
                        returnStatus == "completed" -> "Yes"
                        else -> returnStatus
                    }
                }
                tripView.addView(TextView(this@ProfileActivity).apply {
                    text = returnedLabel
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    setTypeface(Typeface.DEFAULT, Typeface.ITALIC)
                    setTextColor(ContextCompat.getColor(this@ProfileActivity, R.color.text_muted))
                    setPadding(0, 0, 0, 8)
                })
            }

            // Line items â€” one entry per delivery
            if (lineItems.isNotEmpty()) {
                lineItems.forEachIndexed { index, item ->
                    val itemMap = item as? Map<String, *> ?: return@forEachIndexed
                    val customer = itemMap["customerName"] as? String ?: "Unknown customer"
                    val status = itemMap["deliveryStatus"] as? String ?: ""
                    val obd = itemMap["obdNumber"] as? String ?: ""
                    val isPodDone = itemMap["isPodCompleted"] as? Boolean ?: false
                    val podAt = (itemMap["podCapturedAt"] as? Timestamp)?.toDate()

                    val itemDetails = buildString {
                        append("   â€¢ ").append(customer)
                        if (status.isNotBlank()) append(" â€” ").append(status)
                        append("\n")
                        if (obd.isNotBlank()) append("     OBD: ").append(obd).append("\n")
                        append("     POD: ").append(
                            when {
                                isPodDone && podAt != null -> "Yes (${sdfShort.format(podAt)})"
                                isPodDone -> "Yes"
                                else -> "No"
                            }
                        )
                    }

                    tripView.addView(TextView(this@ProfileActivity).apply {
                        text = itemDetails
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                        setPadding(0, 4, 0, 4)
                    })
                }
            }

            // Divider between trips (skip after the last one)
            if (tripIndex < historyList.lastIndex) {
                tripView.addView(View(this@ProfileActivity).apply {
                    setBackgroundColor(ContextCompat.getColor(this@ProfileActivity, R.color.divider))
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        1
                    )
                })
            }

            linearLayout.addView(tripView)
        }

        val closeButton = MaterialButton(this@ProfileActivity).apply {
            text = "Close"
            setPadding(24, 12, 24, 12)
        }
        val buttonLayout = LinearLayout(this@ProfileActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 24, 0, 0)
        }
        buttonLayout.addView(closeButton)
        linearLayout.addView(buttonLayout)

        val dialog = AlertDialog.Builder(this@ProfileActivity)
            .setView(linearLayout)
            .create()
        dialog.show()
        closeButton.setOnClickListener { dialog.dismiss() }
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
