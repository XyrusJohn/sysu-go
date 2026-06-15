package com.sysu.go.firebase

import android.content.Context
import android.util.Log
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.tasks.Task
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

object FirebaseAuthManager {
    private const val TAG = "FirebaseAuthManager"
    private const val RC_GOOGLE_SIGN_IN = 9001

    private var auth: FirebaseAuth? = null
    private var googleSignInClient: GoogleSignInClient? = null

    fun initialize(context: Context) {
        auth = FirebaseAuth.getInstance()
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(context.getString(context.applicationInfo.labelRes))
            .requestEmail()
            .build()
        googleSignInClient = GoogleSignIn.getClient(context, gso)
    }

    private fun getAuth(): FirebaseAuth = auth
        ?: throw IllegalStateException("FirebaseAuthManager not initialized. Call initialize() first.")

    private fun getGoogleClient(): GoogleSignInClient = googleSignInClient
        ?: throw IllegalStateException("GoogleSignInClient not initialized. Call initialize() first.")

    fun getCurrentUser(): FirebaseUser? = getAuth().currentUser

    suspend fun getIdToken(): String? {
        return try {
            getCurrentUser()?.getIdToken(false)?.await()?.token
        } catch (e: Exception) {
            Log.e(TAG, "Error getting ID token", e)
            null
        }
    }

    fun isLoggedIn(): Boolean = getCurrentUser() != null

    fun signOut(context: Context) {
        getAuth().signOut()
        googleSignInClient?.signOut()
    }

    suspend fun signInWithEmailPassword(email: String, password: String): AuthResult {
        return try {
            val result = getAuth().signInWithEmailAndPassword(email, password).await()
            AuthResult.Success(result.user!!)
        } catch (e: Exception) {
            AuthResult.Error(e.message ?: "Login failed")
        }
    }

    suspend fun signInWithGoogle(idToken: String): AuthResult {
        return try {
            val credential = GoogleAuthProvider.getCredential(idToken, null)
            val result = getAuth().signInWithCredential(credential).await()
            AuthResult.Success(result.user!!)
        } catch (e: ApiException) {
            AuthResult.Error(e.message ?: "Google sign-in failed")
        } catch (e: Exception) {
            AuthResult.Error(e.message ?: "Google sign-in failed")
        }
    }

    suspend fun registerDriver(
        fn: String,
        mi: String,
        ln: String,
        displayName: String,
        email: String,
        password: String,
        phone: String
    ): AuthResult {
        return try {
            // Create Firebase Auth user
            val result = getAuth().createUserWithEmailAndPassword(email, password).await()
            val user = result.user
                ?: return AuthResult.Error("Registration failed: no user returned")

            // Update Firebase Auth display name
            val profileUpdates = com.google.firebase.auth.UserProfileChangeRequest.Builder()
                .setDisplayName(displayName)
                .build()
            user.updateProfile(profileUpdates).await()

            // Write users/{uid} Firestore document
            val db = FirebaseFirestore.getInstance()
            val userDoc = mapOf(
                "uid" to user.uid,
                "email" to email,
                "fn" to fn,
                "mi" to mi,
                "ln" to ln,
                "name" to displayName,
                "phone" to phone,
                "role" to "driver",
                "status" to "active",
                "position" to "Driver",
                "createdAt" to FieldValue.serverTimestamp()
            )
            db.collection("users").document(user.uid).set(userDoc).await()

            // Write drivers/{uid} document with split name and phone
            val driverDoc = mapOf(
                "uid" to user.uid,
                "fn" to fn,
                "mi" to mi,
                "ln" to ln,
                "name" to displayName,
                "email" to email,
                "phone" to phone,
                "plateNumber" to "",
                "truckType" to "",
                "truckingCompany" to "",
                "helper" to "",
                "status" to "offline"
            )
            db.collection("drivers").document(user.uid).set(driverDoc).await()

            AuthResult.Success(user)
        } catch (e: Exception) {
            AuthResult.Error(e.message ?: "Registration failed")
        }
    }

    suspend fun checkUsersDocument(uid: String): Boolean {
        return try {
            val db = FirebaseFirestore.getInstance()
            val doc = db.collection("users").document(uid).get().await()
            doc.exists()
        } catch (e: Exception) {
            false
        }
    }

    fun signInWithGoogleIntent(context: android.content.Context) {
        googleSignInClient?.signOut()
        val signInIntent = googleSignInClient!!.signInIntent
        (context as? android.app.Activity)?.startActivityForResult(signInIntent, RC_GOOGLE_SIGN_IN)
    }

    fun handleGoogleSignInResult(data: android.content.Intent?, onResult: (AuthResult) -> Unit) {
        val task = GoogleSignIn.getSignedInAccountFromIntent(data)
        task.addOnSuccessListener { account ->
            val idToken = account.idToken
            if (idToken != null) {
                // Use synchronous-style coroutine call via task
                val credential = GoogleAuthProvider.getCredential(idToken, null)
                getAuth().signInWithCredential(credential)
                    .addOnSuccessListener { result ->
                        onResult(AuthResult.Success(result.user!!))
                    }
                    .addOnFailureListener { e ->
                        onResult(AuthResult.Error(e.message ?: "Google sign-in failed"))
                    }
            } else {
                onResult(AuthResult.Error("No ID token from Google sign-in"))
            }
        }.addOnFailureListener { e ->
            onResult(AuthResult.Error(e.message ?: "Google sign-in failed"))
        }
    }

    suspend fun getUsersDocument(uid: String): Map<String, Any>? {
        return try {
            val db = FirebaseFirestore.getInstance()
            val doc = db.collection("users").document(uid).get().await()
            if (doc.exists()) doc.data else null
        } catch (e: Exception) {
            null
        }
    }

    suspend fun getDriverProfile(uid: String): Map<String, Any>? {
        return try {
            val db = FirebaseFirestore.getInstance()
            val doc = db.collection("drivers").document(uid).get().await()
            if (doc.exists()) doc.data else null
        } catch (e: Exception) {
            null
        }
    }

    sealed class AuthResult {
        data class Success(val user: FirebaseUser) : AuthResult()
        data class Error(val message: String) : AuthResult()
    }
}