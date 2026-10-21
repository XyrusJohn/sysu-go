package com.sysu.go

import android.content.Intent
import android.location.Location
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.location.LocationServices
import com.sysu.go.activities.GeneralTrackingActivity
import com.sysu.go.activities.MainActivity
import com.sysu.go.network.SocketManager
import com.sysu.go.utils.SessionManager
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class AndroidTrackingBridgeTest {
    private val support = NativeWorkflowTest()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val fused = LocationServices.getFusedLocationProviderClient(context)
    private var scenario: ActivityScenario<MainActivity>? = null
    private var trackingScenario: ActivityScenario<GeneralTrackingActivity>? = null
    private var lastFixAt = 0L

    @Before fun isolate() { support.isolate() }
    @After fun cleanup() {
        trackingScenario?.close()
        scenario?.close()
        trackingScenario = null
        Tasks.await(fused.setMockMode(false), 10, TimeUnit.SECONDS)
        support.cleanup()
    }

    private fun fix(latitude: Double) {
        support.waitUntil("Wait for the application five-second moving cadence") {
            SystemClock.elapsedRealtime()-lastFixAt >= 5000
        }
        val location = Location("fused").apply {
            this.latitude = latitude
            longitude = 121.02
            accuracy = 5f
            time = System.currentTimeMillis()
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        }
        lastFixAt = SystemClock.elapsedRealtime()
        Tasks.await(fused.setMockLocation(location), 10, TimeUnit.SECONDS)
    }

    private fun stage(value: String) {
        support.fixture("/phase", JSONObject().put("value", value))
        support.waitUntil("Website must acknowledge $value") {
            support.fixture("/phase").optString("ack") == value
        }
        // Provider delivery and service publication happen after mock injection completes.
        lastFixAt = SystemClock.elapsedRealtime()
    }

    @Test fun enteringActiveTrackingScreenStartsServiceAfterIdleHomeStartup() {
        support.fixture("/reset", JSONObject().put("assignment", true).put("tripStatus", "in_transit"))
        support.signIn()
        SessionManager(context).apply {
            saveCurrentSapDocId("native-shipment")
            saveActiveSapDocId("native-shipment")
            setTripActive(true)
            setTrackingActive(false)
        }
        Tasks.await(fused.setMockMode(true), 10, TimeUnit.SECONDS)
        scenario = ActivityScenario.launch(Intent(context, MainActivity::class.java))
        support.waitUntil("Home socket must authenticate") { SocketManager.isConnected() }

        // This is the tracking flag transition performed when the driver confirms a shipment.
        SessionManager(context).setTrackingActive(true)
        trackingScenario = ActivityScenario.launch(Intent(context, GeneralTrackingActivity::class.java))
        support.waitUntil("Tracking screen must preserve authenticated socket") { SocketManager.isConnected() }
        fix(14.551)
        support.waitUntil("Resumed tracking screen must start the foreground location service") {
            support.fixture("/state").optJSONObject("location")?.optDouble("lat") == 14.551
        }
    }

    @Test fun nativeServiceUpdatesWebsiteAcrossBackgroundReconnectReturnAndClosure() {
        support.fixture("/reset", JSONObject().put("assignment",true).put("tripStatus","in_transit"))
        support.signIn()
        SessionManager(context).apply {
            saveCurrentSapDocId("native-shipment")
            saveActiveSapDocId("native-shipment")
            setTripActive(true)
            setTrackingActive(true)
        }
        Tasks.await(fused.setMockMode(true), 10, TimeUnit.SECONDS)
        scenario = ActivityScenario.launch(Intent(context, MainActivity::class.java))
        support.waitUntil("Native socket must authenticate") { SocketManager.isConnected() }
        fix(14.55)
        support.waitUntil("Native transaction must persist the captured position") {
            support.fixture("/state").optJSONObject("location")?.optDouble("lat") == 14.55
        }
        stage("first")

        instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_HOME").close()
        fix(14.56)
        stage("background")
        SocketManager.disconnect()
        stage("offline")

        SocketManager.connect(context)
        support.waitUntil("Native socket must recover its authenticated connection") { SocketManager.isConnected() }
        // Reconnect immediately republishes the last fresh fix and restarts the send budget.
        lastFixAt = SystemClock.elapsedRealtime()
        fix(14.57)
        stage("recovered")

        support.fixture("/transition",JSONObject().put("tripStatus","returning_to_base"))
        fix(14.58)
        stage("returning")

        support.fixture("/transition",JSONObject().put("tripStatus","completed"))
        val closedAt = System.currentTimeMillis()
        SocketManager.emitCompletedStatus("native-driver","Native Driver")
        stage("closed")
        SocketManager.emitDriverMoving(14.59,121.02,"native-shipment","online",closedAt-1,5f)
        stage("late")
    }
}
