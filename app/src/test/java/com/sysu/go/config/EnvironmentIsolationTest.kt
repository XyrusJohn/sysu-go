package com.sysu.go.config
import com.sysu.go.BuildConfig
import org.junit.Assert.*
import org.junit.Test
class EnvironmentIsolationTest {
 @Test fun debugBuildTargetsEmulatorOnly() {assertEquals("http://10.0.2.2:3000",BuildConfig.API_BASE_URL);assertEquals("http://10.0.2.2:3000",BuildConfig.SOCKET_URL);assertEquals("http://10.0.2.2:5173",BuildConfig.FRONTEND_URL);assertEquals("com.sysu.go.debug",BuildConfig.APPLICATION_ID)}
}
