package com.aesprt.aquahub_customer.data.auth

import android.content.Context
import com.aesprt.aquahub_customer.data.FIRESTORE_DATABASE
import com.aesprt.aquahub_customer.data.CustomerMessagingService
import com.aesprt.aquahub_customer.data.local.dao.CustomerProfileDao
import com.aesprt.aquahub_customer.data.local.dao.SavedAddressDao
import com.aesprt.aquahub_customer.data.local.entity.toEntity
import com.aesprt.aquahub_customer.data.preferences.ThemePreferences
import com.aesprt.aquahub_customer.domain.AppResult
import com.aesprt.aquahub_customer.domain.AuthRepository
import com.aesprt.aquahub_customer.domain.CustomerProfile
import com.aesprt.aquahub_customer.domain.DeliveryAddress
import com.aesprt.aquahub_customer.domain.CustomerAcquisitionSource
import com.aesprt.aquahub_customer.domain.PreferredStation
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.functions.FirebaseFunctionsException
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.HttpsCallableOptions
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
    private val themePreferences: ThemePreferences? = null,
) : AuthRepository {
    override val isFirebaseConfigured: Boolean = FirebaseApp.getApps(context).isNotEmpty()

    private val auth: FirebaseAuth get() = FirebaseAuth.getInstance()
    private val db: FirebaseFirestore
        get() = FirebaseFirestore.getInstance(FirebaseApp.getInstance(), FIRESTORE_DATABASE)

    private val credentialManager = CredentialManager.create(context)

    private suspend fun getGoogleIdToken(filterByAuthorizedAccounts: Boolean): String {
        val resourceId = context.resources.getIdentifier(
            "default_web_client_id",
            "string",
            context.packageName,
        )
        require(resourceId != 0) { "Google sign-in is not configured for this app." }
        val googleIdOption = GetGoogleIdOption.Builder()
            .setServerClientId(context.getString(resourceId))
            .setFilterByAuthorizedAccounts(filterByAuthorizedAccounts)
            .setAutoSelectEnabled(false)
            .build()
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(googleIdOption)
            .build()
        val credential = credentialManager.getCredential(context, request).credential
        require(credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            "Google returned an unsupported credential."
        }
        return GoogleIdTokenCredential.createFrom(credential.data).idToken
    }

    private suspend fun clearGoogleCredentialState() {
        runCatching {
            credentialManager.clearCredentialState(ClearCredentialStateRequest())
        }
    }

    override val profile: Flow<CustomerProfile?> = if (profileDao != null) {
        flow {
            val cached = profileDao.getProfileOnce()?.toDomain()
            if (cached != null && auth.currentUser?.uid == cached.uid) emit(cached)
            emitAll(firebaseProfileFlow())
        }
    } else {
        firebaseProfileFlow()
    }

    override suspend fun signInWithGoogle(): AppResult<CustomerProfile> =
        runCatching {
            val idToken = getGoogleIdToken(filterByAuthorizedAccounts = false)
            val credential = GoogleAuthProvider.getCredential(idToken, null)
            val previousUid = auth.currentUser?.uid
            val user = auth.signInWithCredential(credential).await().user
                ?: error("Google sign-in returned no user.")
            if (previousUid != null && previousUid != user.uid) {
                profileDao?.clear()
                addressDao?.clear()
            }

            val now = System.currentTimeMillis()
            val ref = db.collection("users").document(user.uid)
            val existing = ref.get().await()
            requireCustomerRole(existing)
            val profile = profileFrom(user, existing)
            val role = existing.getString("role") ?: "CUSTOMER"
            val isActive = existing.getBoolean("isActive") ?: true
            ref.set(
                mapOf(
                    "uid" to user.uid,
                    "displayName" to profile.displayName,
                    "email" to profile.email,
                    "phone" to profile.phone,
                    "photoUrl" to profile.photoUrl,
                    "address" to profile.address?.addressLine,
                    "latitude" to profile.address?.location?.latitude,
                    "longitude" to profile.address?.location?.longitude,
                    "placeId" to profile.address?.placeId,
                    "setupComplete" to profile.setupComplete,
                    "emailVerified" to profile.emailVerified,
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

    override suspend fun registerEmail(
        name: String,
        email: String,
        password: String
    ): AppResult<CustomerProfile> = runCatching {
        require(name.trim().length >= 2) { "Enter your full name." }
        require(email.trim().contains("@")) { "Enter a valid email address." }
        require(password.length >= 6) { "Use a password with at least 6 characters." }
        val user = auth.createUserWithEmailAndPassword(email.trim(), password).await().user
            ?: error("Firebase did not return a user.")
        user.sendEmailVerification().await()
        persistProfile(user, name.trim(), null, setupComplete = false)
    }.toAppResult("Could not create your account.")

    override suspend fun signInEmail(email: String, password: String): AppResult<CustomerProfile> =
        runCatching {
            require(email.trim().contains("@")) { "Enter a valid email address." }
            require(password.isNotEmpty()) { "Enter your password." }
            val previousUid = auth.currentUser?.uid
            val user = auth.signInWithEmailAndPassword(email.trim(), password).await().user
                ?: error("Firebase did not return a user.")
            if (previousUid != null && previousUid != user.uid) {
                profileDao?.clear()
                addressDao?.clear()
            }
            user.reload().await()
            val profileDocument = db.collection("users").document(user.uid).get().await()
            requireCustomerRole(profileDocument)
            profileFrom(user, profileDocument)
        }.toAppResult("Could not sign in.")

    override suspend fun sendPasswordReset(email: String): AppResult<Unit> = runCatching {
        require(email.trim().contains("@")) { "Enter a valid email address." }
        auth.sendPasswordResetEmail(email.trim()).await(); Unit
    }.toAppResult("Could not send the password reset email.")

    override suspend fun resendEmailVerification(): AppResult<Unit> = runCatching {
        val current = auth.currentUser ?: error("Sign in again to verify your email.")
        current.sendEmailVerification().await()
        Unit
    }.toAppResult("Could not resend the verification email.")

    override suspend fun reauthenticateEmail(email: String, password: String): AppResult<Unit> =
        runCatching {
            require(email.trim().isNotEmpty()) { "Enter your email address." }
            require(password.isNotEmpty()) { "Enter your password." }
            val current = auth.currentUser ?: error("Sign in again before deleting your account.")
            current.reauthenticate(EmailAuthProvider.getCredential(email.trim(), password)).await()
            Unit
        }.toAppResult("Could not verify your identity.")

    override suspend fun reauthenticateGoogle(): AppResult<Unit> = runCatching {
        val token = getGoogleIdToken(filterByAuthorizedAccounts = true)
        val current = auth.currentUser ?: error("Sign in again before deleting your account.")
        current.reauthenticate(GoogleAuthProvider.getCredential(token, null)).await()
        Unit
    }.toAppResult("Could not verify your identity with Google.")

    override suspend fun updateProfile(
        name: String,
        phone: String,
        address: DeliveryAddress?
    ): AppResult<CustomerProfile> = runCatching {
        require(name.trim().length >= 2) { "Enter your full name." }
        require(phone.filter(Char::isDigit).length >= 10) { "Enter a valid phone number." }
        val current = auth.currentUser ?: error("Sign in again to update your profile.")
        val existing = db.collection("users").document(current.uid).get().await()
        val saved = address ?: profileFrom(current, existing).address
        val complete =
            name.isNotBlank() && phone.isNotBlank() && saved?.addressLine?.isNotBlank() == true && saved.location != null
        persistProfile(current, name.trim(), saved, complete, phone)
        }.toAppResult("Could not update your profile.")

    override suspend fun setPreferredStation(destination: PreferredStation): AppResult<CustomerProfile> =
        runCatching {
            val current = auth.currentUser ?: error("Sign in before choosing a water station.")
            val payload = mapOf(
                "businessId" to destination.businessId,
                "stationId" to destination.stationId,
                "acquisitionSource" to destination.acquisitionSource.name,
            )
            // Validate both credentials before invoking the callable. Cloud Functions
            // reports rejected App Check tokens as UNAUTHENTICATED too, so checking App
            // Check here prevents an attestation failure from being shown as an expired
            // Firebase Auth session.
            current.getIdToken(false).await()
            FirebaseAppCheck.getInstance().getAppCheckToken(false).await()
            try {
                callSetPreferredStation(payload)
            } catch (error: FirebaseFunctionsException) {
                if (error.code != FirebaseFunctionsException.Code.UNAUTHENTICATED) throw error
                FirebaseAppCheck.getInstance().getAppCheckToken(true).await()
                current.getIdToken(true).await()
                callSetPreferredStation(payload)
            }
            profileFrom(current, db.collection("users").document(current.uid).get().await())
        }.toAppResult("Could not save your preferred station.")

    private suspend fun callSetPreferredStation(payload: Map<String, String>) {
        FirebaseFunctions.getInstance("asia-southeast1")
            .getHttpsCallable(
                "setCustomerPreferredStation",
                HttpsCallableOptions.Builder().setLimitedUseAppCheckTokens(true).build(),
            )
            .call(payload)
            .await()
    }

    override suspend fun deleteAccount(): AppResult<Unit> = runCatching {
        FirebaseFunctions.getInstance("asia-southeast1")
            .getHttpsCallable(
                "deleteAquaHubAccount",
                HttpsCallableOptions.Builder().setLimitedUseAppCheckTokens(true).build()
            )
            .call()
            .await()
        profileDao?.clear()
        addressDao?.clear()
        themePreferences?.clear()
        auth.signOut()
        clearGoogleCredentialState(); Unit
    }.toAppResult("Could not delete your account. Please retry.")

    override suspend fun signOut() {
        auth.signOut()
        clearGoogleCredentialState();
        profileDao?.clear()
        addressDao?.clear()
    }

    override suspend fun refreshProfile(): AppResult<CustomerProfile> = runCatching {
        val current =
            auth.currentUser ?: error("Sign in again to refresh your verification status.")
        current.reload().await()
        val profileDocument = db.collection("users").document(current.uid).get().await()
        requireCustomerRole(profileDocument)
        profileFrom(current, profileDocument)
    }.toAppResult("Could not refresh your profile.")

    private fun requireCustomerRole(value: com.google.firebase.firestore.DocumentSnapshot) {
        val role = value.getString("role")
        if (role != null && role != "CUSTOMER") {
            // Do not leave an Owner identity authenticated in the Customer app
            // after a provider or account switch.
            auth.signOut()
            error("This account belongs to AquaHub Owner. Use the Owner app to sign in.")
        }
    }

    private fun profileFrom(
        user: com.google.firebase.auth.FirebaseUser,
        value: com.google.firebase.firestore.DocumentSnapshot?
    ): CustomerProfile {
        val addressLine = value?.getString("address")
        val latitude = value?.getDouble("latitude")
        val longitude = value?.getDouble("longitude")
        val address =
            if (addressLine != null && latitude != null && longitude != null) DeliveryAddress(
                "Primary",
                addressLine,
                com.aesprt.aquahub_customer.domain.GeoPoint(latitude, longitude),
                value.getString("placeId")
            ) else null
        return CustomerProfile(
            user.uid,
            value?.getString("displayName") ?: user.displayName.orEmpty(),
            user.email,
            value?.getString("phone").orEmpty(),
            value?.getString("photoUrl") ?: user.photoUrl?.toString(),
            address,
            value?.getBoolean("setupComplete") == true,
            user.isEmailVerified || user.providerData.any { it.providerId == GoogleAuthProvider.PROVIDER_ID },
            value?.getString("preferredBusinessId"),
            value?.getString("preferredStationId"),
            CustomerAcquisitionSource.fromString(value?.getString("acquisitionSource")),
            value?.getLong("acquiredAt"),
        )
    }

    private suspend fun persistProfile(
        user: com.google.firebase.auth.FirebaseUser,
        name: String,
        address: DeliveryAddress?,
        setupComplete: Boolean,
        phone: String? = null
    ): CustomerProfile {
        val now = System.currentTimeMillis()
        val ref = db.collection("users").document(user.uid)
        val existing = ref.get().await()
        val profile = CustomerProfile(
            user.uid,
            name,
            user.email,
            phone ?: existing.getString("phone").orEmpty(),
            user.photoUrl?.toString(),
            address,
            setupComplete,
            user.isEmailVerified || user.providerData.any { it.providerId == GoogleAuthProvider.PROVIDER_ID },
            existing.getString("preferredBusinessId"),
            existing.getString("preferredStationId"),
            CustomerAcquisitionSource.fromString(existing.getString("acquisitionSource")),
            existing.getLong("acquiredAt"),
        )
        ref.set(
            mapOf(
                "uid" to user.uid,
                "displayName" to profile.displayName,
                "email" to profile.email,
                "phone" to profile.phone,
                "photoUrl" to profile.photoUrl,
                "address" to address?.addressLine,
                "latitude" to address?.location?.latitude,
                "longitude" to address?.location?.longitude,
                "placeId" to address?.placeId,
                "setupComplete" to setupComplete,
                "emailVerified" to profile.emailVerified,
                "role" to "CUSTOMER",
                "isActive" to true,
                "createdAt" to (existing.getLong("createdAt") ?: now),
                "updatedAt" to now
            ), SetOptions.merge()
        ).await()
        profileDao?.upsertProfile(profile.toEntity())
        return profile
    }

    private fun <T> Result<T>.toAppResult(fallback: String): AppResult<T> = fold(
        { AppResult.Success(it) },
        { AppResult.Failure(humanError(it, fallback)) },
    )

    private fun humanError(error: Throwable, fallback: String): String {
        if (error is IllegalArgumentException || error is IllegalStateException) return error.message
            ?: fallback
        val rawMessage = error.message.orEmpty()
        if (rawMessage.contains("app check", ignoreCase = true) ||
            rawMessage.contains("attestation", ignoreCase = true) ||
            rawMessage.contains("debug token", ignoreCase = true)
        ) {
            return "This build is not authorized by Firebase App Check yet. Register the current debug token, then try linking the station again."
        }
        val code = when (error) {
            is FirebaseAuthException -> error.errorCode.uppercase()
            is FirebaseFunctionsException -> error.code.name.uppercase()
            else -> error::class.simpleName.orEmpty().uppercase()
        }
        return when {
            code.contains("UNAUTHENTICATED") -> "Your sign-in session expired. Sign in again before linking a station."
            code.contains("PERMISSION_DENIED") -> "AquaHub could not authorize this station link. Sign in again and try once more."
            code.contains("EMAIL_ALREADY_IN_USE") -> "An account already exists with this email."
            code.contains("ACCOUNT_EXISTS_WITH_DIFFERENT_CREDENTIAL") -> "This email already uses another sign-in method. Sign in with that method first."
            code.contains("INVALID_EMAIL") -> "Enter a valid email address."
            code.contains("WRONG_PASSWORD") || code.contains("INVALID_CREDENTIAL") || code.contains(
                "USER_NOT_FOUND"
            ) -> "Incorrect email or password."

            code.contains("WEAK_PASSWORD") -> "Use a stronger password with at least 6 characters."
            code.contains("NETWORK") -> "Check your internet connection and try again."
            code.contains("TOO_MANY_REQUESTS") -> "Too many attempts. Please wait and try again."
            code.contains("CANCEL") || error.message?.contains(
                "cancel",
                ignoreCase = true
            ) == true -> "Google sign-in was cancelled."

            code.contains("RECENT_LOGIN") || error.message?.contains(
                "recent",
                ignoreCase = true
            ) == true -> "For your security, please sign in again before continuing."

            else -> fallback
        }
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
                // Re-register the current token after every restored or new session.
                // FCM can rotate while the app is backgrounded or before sign-in.
                CustomerMessagingService.registerCurrentDeviceToken(context, user.uid)
                profileListener = db.collection("users").document(user.uid)
                    .addSnapshotListener { value, error ->
                        if (error != null) {
                            CoroutineScope(Dispatchers.IO).launch {
                                profileDao?.getProfileOnce()?.takeIf { it.uid == user.uid }
                                    ?.let { trySend(it.toDomain()) }
                                    ?: trySend(profileFrom(user, null))
                            }
                        } else {
                            if (value?.getString("role")?.let { it != "CUSTOMER" } == true) {
                                auth.signOut()
                                trySend(null)
                                return@addSnapshotListener
                            }
                            val prof = profileFrom(user, value)
                            trySend(prof)
                            profileDao?.let { dao ->
                                CoroutineScope(Dispatchers.IO).launch {
                                    dao.upsertProfile(prof.toEntity())
                                }
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
