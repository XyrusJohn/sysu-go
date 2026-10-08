package com.sysu.go.network
import org.junit.Test
import org.junit.Assert.*
import com.sysu.go.model.ScanRequest
import java.net.HttpURLConnection
import java.net.URL
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

class QrHttpClientTest {
    private val request = ScanRequest("SYS-TEST", "driver-a", "Driver", "Driver", "", "Test", "AUV", "Synthetic", "ABC-123", "Helper")
    private class Connection(val status: Int, val body: String): HttpURLConnection(URL("https://synthetic.invalid")) {
        val sent=ByteArrayOutputStream()
        var closed=false
        override fun connect() {}
        override fun usingProxy()=false
        override fun disconnect() {closed=true}
        override fun getResponseCode()=status
        override fun getInputStream()=ByteArrayInputStream(body.toByteArray())
        override fun getErrorStream()=ByteArrayInputStream(body.toByteArray())
        override fun getOutputStream()=sent
    }
    @Test fun scanSendsBearerAndOwnedPayload() {
        val c=Connection(200,"{\"success\":true,\"queueId\":\"queue-a\",\"queuePosition\":2}")
        val client=QrHttpClient("https://synthetic.invalid/api/qr",{"current-token"},{c})
        val result=client.scanToken(request) as QrResult.Success
        assertEquals("queue-a",result.data.queueId);assertEquals(2,result.data.queuePosition)
        assertEquals("POST",c.requestMethod);assertEquals("Bearer current-token",c.getRequestProperty("Authorization"))
        assertTrue(c.sent.toString().contains("driver-a"));assertTrue(c.closed)
    }
    @Test fun missingIdentityMakesNoRequest() {
        var calls=0;val client=QrHttpClient("https://synthetic.invalid/api/qr",{null},{calls++;Connection(200,"{}")})
        assertEquals(401,(client.scanToken(request) as QrResult.Error).code);assertEquals(0,calls)
    }
    @Test fun networkFailureCannotCreateOfflineQueueOrRetryMutation() {
        var calls=0;val client=QrHttpClient("https://synthetic.invalid/api/qr",{"token"},{calls++;throw IOException("private network details")})
        val result=client.scanToken(request) as QrResult.Error;assertEquals(-1,result.code);assertEquals(1,calls);assertFalse(result.message.contains("private"))
    }
    @Test fun serverDenialRemainsDenial() {
        val c=Connection(403,"{\"message\":\"private details\"}")
        val result=QrHttpClient("https://synthetic.invalid/api/qr",{"token"},{c}).scanToken(request) as QrResult.Error
        assertEquals(403,result.code);assertFalse(result.message.contains("private"));assertTrue(c.closed)
    }
    @Test fun malformedResponseCannotReportSuccess() {
        val c=Connection(200,"not-json");assertEquals(502,(QrHttpClient("https://synthetic.invalid/api/qr",{"token"},{c}).scanToken(request) as QrResult.Error).code);assertTrue(c.closed)
    }
    @Test fun successWithoutQueueIdentityDenied() {
        val c=Connection(200,"{\"success\":true}");assertEquals(502,(QrHttpClient("https://synthetic.invalid/api/qr",{"token"},{c}).scanToken(request) as QrResult.Error).code)
    }
    @Test fun oversizedResponseBounded() {
        val c=Connection(200,"x".repeat(65537));assertEquals(502,(QrHttpClient("https://synthetic.invalid/api/qr",{"token"},{c}).scanToken(request) as QrResult.Error).code);assertTrue(c.closed)
    }
    @Test fun verifyUsesReadOnlyGetAndDoesNotAcquireAuth() {
        val c=Connection(200,"{\"success\":true,\"token\":\"SYS-TEST\"}")
        val client=QrHttpClient("https://synthetic.invalid/api/qr",{throw AssertionError("verification acquired identity")},{c})
        assertTrue(client.verifyToken("SYS-TEST") is QrResult.Success);assertEquals("GET",c.requestMethod);assertEquals(0,c.sent.size());assertTrue(c.closed)
    }
    @Test fun expiredVerifyCannotBecomeOfflineSuccess() {
        val c=Connection(410,"{}");assertEquals(410,(QrHttpClient("https://synthetic.invalid/api/qr",{null},{c}).verifyToken("SYS-TEST") as QrResult.Error).code)
    }
    @Test fun differentVerifiedTokenDenied() {
        val c=Connection(200,"{\"success\":true,\"token\":\"OTHER\"}");assertEquals(502,(QrHttpClient("https://synthetic.invalid/api/qr",{null},{c}).verifyToken("SYS-TEST") as QrResult.Error).code)
    }
    @Test fun invalidTokenRejectedBeforeNetwork() {
        var calls=0;val client=QrHttpClient("https://synthetic.invalid/api/qr",{null},{calls++;Connection(200,"{}")})
        assertEquals(400,(client.verifyToken("../token") as QrResult.Error).code);assertEquals(0,calls)
    }
}
