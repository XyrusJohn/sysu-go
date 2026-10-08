package com.sysu.go.config
import com.sysu.go.BuildConfig
import org.junit.Assert.*
import org.junit.Test
class EnvironmentIsolationTest {
 @Test fun compiledVariantTargetsExpectedServices() {
  val api=if(BuildConfig.DEBUG) "http://10.0.2.2:3000" else "https://sysu-go-production.up.railway.app"
  val frontend=if(BuildConfig.DEBUG) "http://10.0.2.2:5173" else "https://sysu-web-5a25d.web.app"
  assertEquals(api,BuildConfig.API_BASE_URL);assertEquals(api,BuildConfig.SOCKET_URL);assertEquals(frontend,BuildConfig.FRONTEND_URL)
  assertEquals(if(BuildConfig.DEBUG) "com.sysu.go.debug" else "com.sysu.go",BuildConfig.APPLICATION_ID)
 }

 @Test fun policyAcceptsOnlyExactDemoDebugConfiguration() {EnvironmentPolicy.validate(true,"demo-sysu-security","http://10.0.2.2:3000","http://10.0.2.2:3000","http://10.0.2.2:5173")}
 @Test fun policyRejectsProductionFirebaseInDebug() {assertThrows(IllegalArgumentException::class.java){EnvironmentPolicy.validate(true,"sysu-web-5a25d","http://10.0.2.2:3000","http://10.0.2.2:3000","http://10.0.2.2:5173")}}
 @Test fun policyRejectsProductionEndpointsInDebug() {assertThrows(IllegalArgumentException::class.java){EnvironmentPolicy.validate(true,"demo-sysu-security","https://sysu-go-production.up.railway.app","http://10.0.2.2:3000","http://10.0.2.2:5173")}}
 @Test fun policyAcceptsExactProductionRelease() {EnvironmentPolicy.validate(false,"sysu-web-5a25d","https://sysu-go-production.up.railway.app","https://sysu-go-production.up.railway.app","https://sysu-web-5a25d.web.app")}
 @Test fun policyRejectsEmulatorProjectInRelease() {assertThrows(IllegalArgumentException::class.java){EnvironmentPolicy.validate(false,"demo-sysu-security","https://sysu-go-production.up.railway.app","https://sysu-go-production.up.railway.app","https://sysu-web-5a25d.web.app")}}
 @Test fun policyRejectsEmulatorOrDecoratedReleaseUrls() {for(url in listOf("http://10.0.2.2:3000","https://user@sysu-go-production.up.railway.app","https://sysu-go-production.up.railway.app?wrong=1")){assertThrows(IllegalArgumentException::class.java){EnvironmentPolicy.validate(false,"sysu-web-5a25d",url,"https://sysu-go-production.up.railway.app","https://sysu-web-5a25d.web.app")}}}
}
