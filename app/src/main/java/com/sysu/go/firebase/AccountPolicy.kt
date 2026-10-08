package com.sysu.go.firebase
object AccountPolicy {
 fun approved(profile: Map<String,Any?>): Boolean = TODO("approval boundary")
 fun details(fn:String,mi:String,ln:String,name:String,phone:String): Map<String,Any> = TODO("profile boundary")
 fun pending(uid:String,email:String,fn:String,mi:String,ln:String,name:String,phone:String,createdAt:Any): Map<String,Any> = TODO("signup boundary")
}
