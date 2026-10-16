package com.sysu.go

import android.content.Intent
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.sysu.go.activities.AssignmentActivity
import com.sysu.go.activities.LoginActivity
import com.sysu.go.activities.MainActivity
import com.sysu.go.activities.QueueDetailsActivity
import com.sysu.go.activities.TripRegistrationActivity
import com.sysu.go.activities.ProfileActivity
import com.sysu.go.network.SocketManager
import com.sysu.go.services.LocationServices
import com.sysu.go.utils.PermissionHelper
import com.sysu.go.utils.SessionManager
import org.hamcrest.Matchers.containsString
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class NativeWorkflowTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private var scenario: ActivityScenario<*>? = null
    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseFirestore.getInstance()

    @Before fun isolate() {
        assertTrue("Native tests may only use the demo emulator variant", BuildConfig.USE_FIREBASE_EMULATORS)
        assertEquals("com.sysu.go.debug", context.packageName)
        auth.signOut()
        SessionManager(context).clearSession()
        context.stopService(Intent(context, LocationServices::class.java))
        context.getSharedPreferences("go_prefs", 0).edit().putBoolean("tutorials_enabled", false).commit()
        PermissionHelper.markSetupCompleted(context)
        fixture("/reset", JSONObject())
    }

    @After fun cleanup() {
        scenario?.close()
        scenario = null
        context.stopService(Intent(context, LocationServices::class.java))
        SocketManager.disconnect()
        auth.signOut()
        SessionManager(context).clearSession()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val monitor = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
            for (stage in listOf(androidx.test.runner.lifecycle.Stage.RESUMED,
                androidx.test.runner.lifecycle.Stage.STARTED, androidx.test.runner.lifecycle.Stage.PAUSED,
                androidx.test.runner.lifecycle.Stage.STOPPED)) {
                monitor.getActivitiesInStage(stage).toList().forEach { it.finish() }
            }
        }
    }

    internal fun fixture(path: String, body: JSONObject? = null): JSONObject {
        val connection = URL("http://10.0.2.2:18082$path").openConnection() as HttpURLConnection
        connection.connectTimeout = 10000
        connection.readTimeout = 10000
        if (body != null) {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(body.toString().toByteArray()) }
        }
        try {
            assertEquals("Isolated fixture request failed", 200, connection.responseCode)
            return JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } finally { connection.disconnect() }
    }

    internal fun waitUntil(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(100)
        }
        fail(message)
    }

    private fun awaitView(id: Int, text: String) = waitUntil("Expected $text in view $id") {
        try { onView(withId(id)).check(matches(withText(containsString(text)))); true }
        catch (_: AssertionError) { false }
        catch (_: androidx.test.espresso.NoMatchingViewException) { false }
    }

    internal fun signIn() {
        Tasks.await(auth.signInWithEmailAndPassword("native-driver@example.invalid", "Synthetic-Only-Password-123!"), 15, TimeUnit.SECONDS)
        SessionManager(context).saveDriverSessionWithSplit("native-driver", "Native", "", "Driver", "Native Driver",
            "native-driver@example.invalid", "09000000000", "Synthetic")
        SessionManager(context).saveCurrentQueueDocId("native-queue")
    }

    private fun loginUi(password: String = "Synthetic-Only-Password-123!") {
        scenario = ActivityScenario.launch<LoginActivity>(Intent(context, LoginActivity::class.java))
        onView(withId(R.id.etEmail)).perform(replaceText("native-driver@example.invalid"))
        onView(withId(R.id.etPassword)).perform(replaceText(password), closeSoftKeyboard())
        onView(withId(R.id.btnLogin)).perform(click())
    }

    @Test fun loginRequiresEmail() {
        scenario = ActivityScenario.launch<LoginActivity>(Intent(context, LoginActivity::class.java))
        onView(withId(R.id.btnLogin)).perform(click())
        onView(withId(R.id.etEmail)).check(matches(hasErrorText("Email is required")))
        assertNull(auth.currentUser)
    }

    @Test fun loginRejectsInvalidEmail() {
        scenario = ActivityScenario.launch<LoginActivity>(Intent(context, LoginActivity::class.java))
        onView(withId(R.id.etEmail)).perform(replaceText("invalid"), closeSoftKeyboard())
        onView(withId(R.id.btnLogin)).perform(click())
        onView(withId(R.id.etEmail)).check(matches(hasErrorText("Enter a valid email address")))
        assertNull(auth.currentUser)
    }

    @Test fun approvedEmailLoginOpensHome() {
        loginUi()
        awaitView(R.id.tvDriverName, "Native")
        assertEquals("native-driver", auth.currentUser?.uid)
        assertTrue(SessionManager(context).hasActiveSession())
    }

    @Test fun pendingDriverCannotStartSession() {
        fixture("/reset", JSONObject().put("pending", true))
        loginUi()
        awaitView(R.id.tvDialogTitle, "")
        waitUntil("Pending user must be signed out") { auth.currentUser == null }
        assertFalse(SessionManager(context).hasActiveSession())
    }

    @Test fun wrongPasswordCannotStartSession() {
        loginUi("wrong-synthetic-password")
        awaitView(R.id.tvDialogTitle, "Login Error")
        assertNull(auth.currentUser)
        assertFalse(SessionManager(context).hasActiveSession())
    }

    @Test fun profileLogoutClearsAuthenticatedSession() {
        signIn()
        scenario = ActivityScenario.launch<ProfileActivity>(Intent(context, ProfileActivity::class.java))
        onView(withId(R.id.btnLogout)).perform(scrollTo(), click())
        onView(withId(R.id.btnConfirm)).perform(click())
        waitUntil("Logout must revoke local identity and session") {
            auth.currentUser == null && !SessionManager(context).hasActiveSession()
        }
        onView(withId(R.id.btnLogin)).check(matches(isDisplayed()))
    }

    @Test fun scannedQrRegistersOnceThroughNativeForm() {
        fixture("/reset", JSONObject().put("qr", true))
        signIn()
        SessionManager(context).saveCurrentQueueDocId(null)
        val connection = URL("http://10.0.2.2:18082/qr.png").openConnection() as HttpURLConnection
        val bitmap = try { connection.inputStream.use { checkNotNull(android.graphics.BitmapFactory.decodeStream(it)) } }
            finally { connection.disconnect() }
        val scanner = com.google.mlkit.vision.barcode.BarcodeScanning.getClient()
        val decoded = try { Tasks.await(scanner.process(com.google.mlkit.vision.common.InputImage.fromBitmap(bitmap,0)),15,TimeUnit.SECONDS) }
            finally { scanner.close(); bitmap.recycle() }
        val token = decoded.single().rawValue
        assertEquals("SYS-NATIVE-AUTOMATION-QR",token)
        scenario = ActivityScenario.launch<TripRegistrationActivity>(Intent(context, TripRegistrationActivity::class.java).putExtra("token",token))
        awaitView(R.id.etCompany,"Synthetic")
        onView(withId(R.id.etHelperFN)).perform(scrollTo(),replaceText("Synthetic"))
        onView(withId(R.id.etHelperLN)).perform(scrollTo(),replaceText("Helper"))
        onView(withId(R.id.etPlatePrefix)).perform(scrollTo(),replaceText("ABC"))
        onView(withId(R.id.etPlateSuffix)).perform(scrollTo(),replaceText("123"),closeSoftKeyboard())
        onView(withId(R.id.btnSubmit)).perform(click())
        waitUntil("Native admission and registration must persist once") {
            fixture("/state").optJSONObject("queue")?.optBoolean("registrationComplete") == true
        }
        awaitView(R.id.tvQueueStatus,"1")
        assertEquals(1,fixture("/state").getInt("queueCount"))
        assertEquals(fixture("/state").getString("queueId"),SessionManager(context).getCurrentQueueDocId())
    }

    @Test fun missingCompanyCanBeEnteredBeforeQrAdmissionAndAppearsInProfile() {
        fixture("/reset", JSONObject().put("qr", true).put("blankCompany", true))
        signIn()
        SessionManager(context).saveDriverSessionWithSplit("native-driver", "Native", "", "Driver", "Native Driver",
            "native-driver@example.invalid", "09000000000", "")
        scenario = ActivityScenario.launch<TripRegistrationActivity>(Intent(context, TripRegistrationActivity::class.java)
            .putExtra("token", "SYS-NATIVE-AUTOMATION-QR"))
        awaitView(R.id.etHelperFN, "")
        onView(withId(R.id.etCompany)).check(matches(withText("")))
        onView(withId(R.id.etHelperFN)).perform(scrollTo(), replaceText("Synthetic"))
        onView(withId(R.id.etHelperLN)).perform(scrollTo(), replaceText("Helper"))
        onView(withId(R.id.etPlatePrefix)).perform(scrollTo(), replaceText("ABC"))
        onView(withId(R.id.etPlateSuffix)).perform(scrollTo(), replaceText("123"), closeSoftKeyboard())
        onView(withId(R.id.btnSubmit)).perform(click())
        onView(withId(R.id.etCompany)).check(matches(hasErrorText("Trucking company is required")))
        assertEquals(0, fixture("/state").getInt("queueCount"))
        onView(withId(R.id.etCompany)).perform(scrollTo(), replaceText("Recovered Logistics"), closeSoftKeyboard())
        onView(withId(R.id.btnSubmit)).perform(click())
        waitUntil("Completed registration must persist company") {
            fixture("/state").optJSONObject("queue")?.optBoolean("registrationComplete") == true
        }
        assertEquals("Recovered Logistics", Tasks.await(db.collection("drivers").document("native-driver")
            .get(com.google.firebase.firestore.Source.SERVER), 15, TimeUnit.SECONDS).getString("truckingCompany"))
        scenario?.close()
        scenario = ActivityScenario.launch<ProfileActivity>(Intent(context, ProfileActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        waitUntil("Confirmed company must appear on Profile") {
            var shown = ""
            scenario?.onActivity { activity ->
                shown = activity.findViewById<android.widget.TextView>(R.id.tvCompany).text.toString()
            }
            shown == "RECOVERED LOGISTICS"
        }
    }

    @Test fun registeredHomeQueueMovesFromThreeToOne() {
        fixture("/reset", JSONObject().put("queue", true).put("ahead", 2))
        signIn()
        scenario = ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java))
        awaitView(R.id.tvQueueStatus, "3")
        fixture("/advance", JSONObject().put("number", 1))
        awaitView(R.id.tvQueueStatus, "2")
        fixture("/advance", JSONObject().put("number", 2))
        awaitView(R.id.tvQueueStatus, "1")
    }

    @Test fun registeredDetailsQueueMovesWithoutReopening() {
        fixture("/reset", JSONObject().put("queue", true).put("ahead", 2))
        signIn()
        scenario = ActivityScenario.launch<QueueDetailsActivity>(Intent(context, QueueDetailsActivity::class.java))
        awaitView(R.id.tvQueueNumber, "3")
        fixture("/advance", JSONObject().put("number", 1))
        awaitView(R.id.tvQueueNumber, "2")
        fixture("/advance", JSONObject().put("number", 2))
        awaitView(R.id.tvQueueNumber, "1")
    }

    @Test fun recreatedHomeKeepsItsRegisteredQueue() {
        fixture("/reset", JSONObject().put("queue", true).put("ahead", 1))
        signIn()
        val home = ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java))
        scenario = home
        awaitView(R.id.tvQueueStatus,"2")
        home.recreate()
        awaitView(R.id.tvQueueStatus,"2")
        fixture("/advance",JSONObject().put("number",1))
        awaitView(R.id.tvQueueStatus,"1")
        assertEquals("native-queue",SessionManager(context).getCurrentQueueDocId())
    }

    @Test fun completedHistoricalShipmentDoesNotReplaceWaitingQueue() {
        fixture("/reset", JSONObject().put("queue", true).put("history", true))
        signIn()
        scenario = ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java))
        awaitView(R.id.tvQueueStatus, "1")
        onView(withText("native-history")).check(androidx.test.espresso.assertion.ViewAssertions.doesNotExist())
        assertEquals("waiting", fixture("/state").getJSONObject("queue").getString("status"))
    }

    @Test fun ownAssignmentShowsBothObdsAndPersistsLoading() {
        fixture("/reset", JSONObject().put("assignment", true))
        signIn()
        scenario = ActivityScenario.launch<AssignmentActivity>(Intent(context, AssignmentActivity::class.java))
        awaitView(R.id.tvShipmentNumber, "native-shipment")
        awaitView(R.id.tvObdCount, "2")
        onView(withId(R.id.btnTripAction)).perform(scrollTo(), click())
        awaitView(R.id.tvDialogTitle, "Start Loading?")
        onView(withId(R.id.btnConfirm)).perform(click())
        waitUntil("Loading must persist, not only update optimistically") {
            fixture("/state").getJSONObject("shipment").getString("tripStatus") == "loading"
        }
    }

    @Test fun ownLoadingCanPersistFullyLoaded() {
        fixture("/reset", JSONObject().put("assignment", true).put("tripStatus", "loading"))
        signIn()
        scenario = ActivityScenario.launch<AssignmentActivity>(Intent(context, AssignmentActivity::class.java))
        awaitView(R.id.tvShipmentNumber, "native-shipment")
        onView(withId(R.id.btnTripAction)).perform(scrollTo(), click())
        onView(withId(R.id.btnConfirm)).perform(click())
        waitUntil("Fully loaded must persist") {
            fixture("/state").getJSONObject("shipment").getString("tripStatus") == "fully_loaded"
        }
    }

    @Test fun ownProfileCannotPromoteRole() {
        signIn()
        val task = db.collection("users").document("native-driver").update("role", "super_admin")
        try { Tasks.await(task, 15, TimeUnit.SECONDS); fail("Self-promotion must be denied") }
        catch (error: java.util.concurrent.ExecutionException) {
            assertEquals(com.google.firebase.firestore.FirebaseFirestoreException.Code.PERMISSION_DENIED,
                (error.cause as com.google.firebase.firestore.FirebaseFirestoreException).code)
        }
    }

    @Test fun driverCannotReadAnotherDriversQueue() {
        fixture("/reset", JSONObject().put("queue", true).put("ahead", 1))
        signIn()
        try { Tasks.await(db.collection("queue").document("native-ahead-1").get(com.google.firebase.firestore.Source.SERVER), 15, TimeUnit.SECONDS)
            fail("Foreign queue details must be denied") }
        catch (error: java.util.concurrent.ExecutionException) {
            assertEquals(com.google.firebase.firestore.FirebaseFirestoreException.Code.PERMISSION_DENIED,
                (error.cause as com.google.firebase.firestore.FirebaseFirestoreException).code)
        }
    }
}
