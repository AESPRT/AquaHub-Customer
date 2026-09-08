package com.aesprt.aquahub_customer.data.auth

import android.content.Context
import android.content.Intent
import com.aesprt.aquahub_customer.data.FIRESTORE_DATABASE
import com.aesprt.aquahub_customer.data.local.dao.CustomerProfileDao
import com.aesprt.aquahub_customer.data.local.dao.SavedAddressDao
import com.aesprt.aquahub_customer.data.local.entity.toEntity
import com.aesprt.aquahub_customer.domain.AppResult
import com.aesprt.aquahub_customer.domain.AuthRepository
import com.aesprt.aquahub_customer.domain.CustomerProfile
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class FirebaseAuthRepository(
    private val context: Context,
    private val profileDao: CustomerProfileDao? = null,
    private val addressDao: SavedAddressDao? = null,
) : AuthRepository {
    override val isFirebaseConfigured: Boolean = FirebaseApp.getApps(context).isNotEmpty()

    private val auth: FirebaseAuth get() = FirebaseAuth.getInstance()
    private val db: FirebaseFirestore
        get() = FirebaseFirestore.getInstance(FirebaseApp.getInstance(), FIRESTORE_DATABASE)

    private val googleClient: GoogleSignInClient?
        get() {
            if (!isFirebaseConfigured) return null
            val resourceId = context.resources.getIdentifier(
                "default_web_client_id",
                "string",
                context.packageName,
            )
            if (resourceId == 0) return null
            val options = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestIdToken(context.getString(resourceId))
                .requestEmail()
                .build()
            return GoogleSignIn.getClient(context, options)
        }

    override val profile: Flow<CustomerProfile?> = if (profileDao != null) {
        flow {
            val cached = profileDao.getProfileOnce()?.toDomain()
            if (cached != null) emit(cached)
            emitAll(firebaseProfileFlow())
        }
    } else {
        firebaseProfileFlow()
    }

    override suspend fun freshGoogleSignInIntent(): AppResult<Intent> {
        val client = googleClient
            ?: return AppResult.Failure("Google sign-in is not configured for this app.")
        return runCatching {
            // Clear this app's cached Google choice so the account picker is shown every time.
            client.signOut().await()
            client.signInIntent
        }.fold(
            onSuccess = { AppResult.Success(it) },
            onFailure = { AppResult.Failure(it.message ?: "Could not open Google account selection.") },
        )
    }

    override suspend fun completeGoogleSignIn(data: Intent?): AppResult<CustomerProfile> = runCatching {
        requireNotNull(data) { "Google sign-in was cancelled." }
        val account = GoogleSignIn.getSignedInAccountFromIntent(data).await()
        val idToken = requireNotNull(account.idToken) { "Google did not return a sign-in token." }
        val credential = GoogleAuthProvider.getCredential(idToken, null)
        val user = auth.signInWithCredential(credential).await().user
            ?: error("Google sign-in returned no user.")

        val now = System.currentTimeMillis()
        val ref = db.collection("users").document(user.uid)
        val existing = ref.get().await()
        val profile = CustomerProfile(
            uid = user.uid,
            displayName = existing.getString("displayName") ?: user.displayName.orEmpty(),
            email = user.email,
            phone = existing.getString("phone").orEmpty(),
            photoUrl = user.photoUrl?.toString(),
        )
        val role = existing.getString("role") ?: "CUSTOMER"
        val isActive = existing.getBoolean("isActive") ?: true
        ref.set(
            mapOf(
                "uid" to user.uid,
                "displayName" to profile.displayName,
                "email" to profile.email,
                "phone" to profile.phone,
                "photoUrl" to profile.photoUrl,
                "role" to role,
                "isActive" to isActive,
                "createdAt" to (existing.getLong("createdAt") ?: now),
                "updatedAt" to now,
            ),
            SetOptions.merge(),
        ).await()
        profileDao?.upsertProfile(profile.toEntity())
        profile
    }.fold(
        onSuccess = { AppResult.Success(it) },
        onFailure = { AppResult.Failure(it.message ?: "Google sign-in failed.") },
    )

    override suspend fun updateProfile(name: String, phone: String): AppResult<CustomerProfile> {
        if (name.isBlank()) return AppResult.Failure("Enter your name.")
        if (phone.isBlank()) return AppResult.Failure("Enter your phone number.")
        val current = auth.currentUser
            ?: return AppResult.Failure("Sign in again to update your profile.")
        val updated = CustomerProfile(
            current.uid,
            name.trim(),
            current.email,
            phone.trim(),
            current.photoUrl?.toString(),
        )
        return runCatching {
            db.collection("users").document(updated.uid).update(
                mapOf(
                    "displayName" to updated.displayName,
                    "phone" to updated.phone,
                    "updatedAt" to System.currentTimeMillis(),
                ),
            ).await()
            profileDao?.upsertProfile(updated.toEntity())
            updated
        }.fold(
            onSuccess = { AppResult.Success(it) },
            onFailure = { AppResult.Failure(it.message ?: "Could not update profile.") },
        )
    }

    override suspend fun signOut() {
        auth.signOut()
        googleClient?.signOut()?.await()
        profileDao?.clear()
        addressDao?.clear()
    }

    private fun firebaseProfileFlow(): Flow<CustomerProfile?> = callbackFlow {
        if (!isFirebaseConfigured) {
            trySend(null)
            close()
            return@callbackFlow
        }
        var profileListener: ListenerRegistration? = null
        val authListener = FirebaseAuth.AuthStateListener { currentAuth ->
            profileListener?.remove()
            val user = currentAuth.currentUser
            if (user == null) {
                trySend(null)
            } else {
                profileListener = db.collection("users").document(user.uid)
                    .addSnapshotListener { value, _ ->
                        val prof = CustomerProfile(
                            uid = user.uid,
                            displayName = value?.getString("displayName") ?: user.displayName.orEmpty(),
                            email = user.email,
                            phone = value?.getString("phone").orEmpty(),
                            photoUrl = value?.getString("photoUrl") ?: user.photoUrl?.toString(),
                        )
                        trySend(prof)
                        profileDao?.let { dao ->
                            CoroutineScope(Dispatchers.IO).launch {
                                dao.upsertProfile(prof.toEntity())
                            }
                        }
                    }
            }
        }
        auth.addAuthStateListener(authListener)
        awaitClose {
            profileListener?.remove()
            auth.removeAuthStateListener(authListener)
        }
    }
}
