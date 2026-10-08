package com.sysu.go.firebase
/** Canonical approval is server-owned; clients can only create pending accounts. */
object AccountPolicy {
 fun approved(profile: Map<String,Any?>): Boolean = profile["role"] == "driver" && profile["status"] == "active"
 fun details(fn:String,mi:String,ln:String,name:String,phone:String): Map<String,Any> {
  require(fn.length<=50 && mi.length<=8 && ln.length<=50 && name.length<=100 && phone.length<=30) {"Profile details exceed allowed length"}
  return mapOf("fn" to fn,"mi" to mi,"ln" to ln,"name" to name,"phone" to phone)
 }
 fun pending(uid:String,email:String,fn:String,mi:String,ln:String,name:String,phone:String,createdAt:Any): Map<String,Any> {
  require(uid.isNotBlank() && uid.length<=128 && email.length<=254) {"Invalid account identity"}
  return details(fn,mi,ln,name,phone)+mapOf("uid" to uid,"email" to email,"role" to "pending","status" to "inactive","position" to "Driver","createdAt" to createdAt)
 }
}
