package com.sysu.go.firebase
import org.junit.Assert.*
import org.junit.Test
class AccountPolicyTest {
 @Test fun explicitActiveDriverAccepted(){assertTrue(AccountPolicy.approved(mapOf("role" to "driver","status" to "active")))}
 @Test fun missingStatusDenied(){assertFalse(AccountPolicy.approved(mapOf("role" to "driver")))}
 @Test fun pendingAndDisabledDenied(){for(status in listOf("inactive","disabled","pending")){assertFalse(AccountPolicy.approved(mapOf("role" to "driver","status" to status)))}}
 @Test fun staffUnknownAndTypedRolesDenied(){for(role in listOf("admin","super_admin","pending","unknown",42,null)){assertFalse(AccountPolicy.approved(mapOf("role" to role,"status" to "active")))}}
 @Test fun missingRoleDenied(){assertFalse(AccountPolicy.approved(mapOf("status" to "active")))}
 @Test fun signupIsPendingAndInactive(){val fields=AccountPolicy.pending("u","synthetic@example.invalid","First","M","Last","First M. Last","09123456789","timestamp");assertEquals("pending",fields["role"]);assertEquals("inactive",fields["status"]);assertEquals("u",fields["uid"]);assertEquals("First",fields["fn"])}
 @Test fun completionContainsNoAuthorizationOrDriverFields(){val fields=AccountPolicy.details("First","","Last","First Last","09123456789");assertEquals(setOf("fn","mi","ln","name","phone"),fields.keys)}
 @Test fun boundedFieldsRejectOversize(){assertThrows(IllegalArgumentException::class.java){AccountPolicy.details("x".repeat(51),"","Last","First Last","09")};assertThrows(IllegalArgumentException::class.java){AccountPolicy.details("First","","Last","x".repeat(101),"09")}}
 @Test fun signupRejectsOversizedEmail(){assertThrows(IllegalArgumentException::class.java){AccountPolicy.pending("u","x".repeat(255),"First","","Last","First Last","09","timestamp")}}
}
