package com.sysu.go

import android.content.Intent
import androidx.camera.view.PreviewView
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sysu.go.activities.QRScanActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeCameraTest {
    private val support = NativeWorkflowTest()
    private var scenario: ActivityScenario<QRScanActivity>? = null

    @Before fun isolate() { support.isolate() }
    @After fun cleanup() { scenario?.close(); support.cleanup() }

    @Test fun nativeLibrariesLoadAndCameraPreviewStreamsThenCancels() {
        for (library in listOf("image_processing_util_jni", "surface_util_jni", "barhopper_v3")) {
            System.loadLibrary(library)
        }
        support.signIn()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val activity = ActivityScenario.launch<QRScanActivity>(Intent(context, QRScanActivity::class.java))
        scenario = activity
        support.waitUntil("Real CameraX preview must stream") {
            var streaming = false
            activity.onActivity {
                streaming = it.findViewById<PreviewView>(R.id.previewView).previewStreamState.value == PreviewView.StreamState.STREAMING
            }
            streaming
        }
        onView(withId(R.id.btnZoomIn)).check(matches(isDisplayed())).perform(click())
        onView(withId(R.id.btnZoomOut)).perform(click())
        onView(withId(R.id.btnCancel)).perform(click())
        support.waitUntil("Cancelling the scanner must finish its activity") { activity.state == Lifecycle.State.DESTROYED }
        assertEquals(0, support.fixture("/state").getInt("queueCount"))
    }
}
