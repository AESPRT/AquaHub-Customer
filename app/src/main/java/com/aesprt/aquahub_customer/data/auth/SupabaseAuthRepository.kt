package com.aesprt.aquahub_customer.data.auth

import android.content.Context
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import com.aesprt.aquahub_customer.BuildConfig
import com.aesprt.aquahub_customer.data.local.dao.CustomerProfileDao
import com.aesprt.aquahub_customer.data.local.dao.SavedAddressDao
import com.aesprt.aquahub_customer.data.preferences.ThemePreferences
import com.aesprt.aquahub_customer.data.supabase.SupabaseClientProvider
import com.aesprt.aquahub_customer.data.local.entity.toEntity
import com.aesprt.aquahub_customer.domain.*
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.Google
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.providers.builtin.IDToken
import io.github.jan.supabase.functions.functions
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class SupabaseAuthRepository(
    private val context: Context,
    private val profileDao: CustomerProfileDao? = null,
    private val addressDao: SavedAddressDao? = null,
    private val themePreferences: ThemePreferences? = null,
) : AuthRepository {
    private val supabase get() = SupabaseClientProvider.client
    private val credentials = CredentialManager.create(context)

    override val isSupabaseConfigured: Boolean get() = BuildConfig.SUPABASE_URL.isNotBlank()

    override val profile: Flow<CustomerProfile?> = flow {
        while (true) {
            val value = runCatching { loadProfile() }.getOrNull()
            if (value != null) {
                profileDao?.upsertProfile(value.toEntity())
                emit(value)
            } else emit(null)
            delay(5_000)
        }
    }

    override suspend fun signInWithGoogle(): AppResult<CustomerProfile> = runCatching {
        val resourceId = context.resources.getIdentifier("default_web_client_id", "string", context.packageName)
        require(resourceId != 0) { "Google sign-in is not configured for this app." }
        val option = GetGoogleIdOption.Builder().setServerClientId(context.getString(resourceId)).setFilterByAuthorizedAccounts(false).setAutoSelectEnabled(false).build()
        val credential = credentials.getCredential(context, GetCredentialRequest.Builder().addCredentialOption(option).build()).credential
        require(credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) { "Google returned an unsupported credential." }
        val idToken = GoogleIdTokenCredential.createFrom(credential.data).idToken
        supabase.auth.signInWith(IDToken) { this.idToken = idToken; provider = Google }
        provision()
    }.toAppResult("Google sign-in failed.")

    override suspend fun registerEmail(name: String, email: String, password: String): AppResult<CustomerProfile> = runCatching {
        require(name.isNotBlank()) { "Enter your name." }
        require(email.contains('@')) { "Enter a valid email address." }
        require(password.length >= 8) { "Password must be at least 8 characters." }
        supabase.auth.signUpWith(Email) { this.email = email.trim(); this.password = password }
        supabase.auth.updateUser { data { put("display_name", name.trim()) } }
        loadProfile() ?: pendingProfile(name.trim(), email.trim())
    }.toAppResult("Could not create your account.")

    override suspend fun signInEmail(email: String, password: String): AppResult<CustomerProfile> = runCatching {
        supabase.auth.signInWith(Email) { this.email = email.trim(); this.password = password }
        provision()
    }.toAppResult("Email sign-in failed.")

    override suspend fun sendPasswordReset(email: String): AppResult<Unit> = runCatching {
        supabase.auth.resetPasswordForEmail(email.trim(), redirectUrl = "aquahub://auth/recovery")
    }.toAppResult("Could not send the password reset email.")

    override suspend fun resendEmailVerification(): AppResult<Unit> = AppResult.Failure("Open the latest confirmation email, or register again if it expired.")

    override suspend fun refreshProfile(): AppResult<CustomerProfile> = runCatching { provision() }.toAppResult("Could not refresh your profile.")

    override suspend fun reauthenticateEmail(email: String, password: String): AppResult<Unit> = runCatching {
        supabase.auth.signInWith(Email) { this.email = email.trim(); this.password = password }
    }.toAppResult("Could not verify your password.")

    override suspend fun reauthenticateGoogle(): AppResult<Unit> = when (val result = signInWithGoogle()) {
        is AppResult.Success -> AppResult.Success(Unit)
        is AppResult.Failure -> result
    }

    override suspend fun updateProfile(name: String, phone: String, address: DeliveryAddress?): AppResult<CustomerProfile> = runCatching {
        val user = requireNotNull(supabase.auth.currentUserOrNull()) { "Sign in again." }
        supabase.auth.updateUser { data { put("display_name", JsonPrimitive(name.trim())) } }
        supabase.from("profiles").update(buildJsonObject {
            put("display_name", name.trim()); put("phone", phone.trim()); put("address", address?.addressLine); put("latitude", address?.location?.latitude); put("longitude", address?.location?.longitude); put("setup_complete", name.isNotBlank() && phone.isNotBlank() && address?.location != null)
        }) { filter { eq("id", user.id) } }
        provision()
    }.toAppResult("Could not update your profile.")

    override suspend fun setPreferredStation(destination: PreferredStation): AppResult<CustomerProfile> = runCatching {
        val stationId = if (destination.stationId.isUuid()) {
            destination.stationId
        } else {
            supabase.from("stations").select { filter { eq("firebase_id", destination.stationId); eq("is_active", true) } }.decodeList<IdRow>().single().id
        }
        supabase.postgrest.rpc("set_customer_preferred_station", buildJsonObject { put("p_station_id", stationId); put("p_acquisition_source", destination.acquisitionSource.name) })
        provision()
    }.toAppResult("Could not save your preferred station.")

    override suspend fun deleteAccount(): AppResult<Unit> = runCatching {
        requireNotNull(supabase.auth.currentUserOrNull()) { "Sign in again before deleting your account." }
        supabase.functions.invoke(
            function = "delete-account",
            body = buildJsonObject { put("operation", "delete") },
        )
        signOut()
    }.toAppResult("Could not delete your account.")

    override suspend fun signOut() {
        runCatching { supabase.auth.signOut() }
        profileDao?.clear(); addressDao?.clear(); themePreferences?.clear()
        runCatching { credentials.clearCredentialState(ClearCredentialStateRequest()) }
    }

    private suspend fun provision(): CustomerProfile {
        val result = supabase.postgrest.rpc("provision_verified_profile").decodeList<SupabaseProfileRow>().single()
        return result.toDomain().also {
            profileDao?.upsertProfile(it.toEntity())
            com.aesprt.aquahub_customer.data.CustomerMessagingService.registerCurrentDeviceToken(context)
        }
    }

    private suspend fun loadProfile(): CustomerProfile? {
        val id = supabase.auth.currentUserOrNull()?.id ?: return null
        return supabase.from("profiles").select { filter { eq("id", id) } }.decodeList<SupabaseProfileRow>().firstOrNull()?.toDomain()
    }

    private fun pendingProfile(name: String, email: String) = CustomerProfile("pending", name, email, "", setupComplete = false, emailVerified = false)

    private fun String.isUuid(): Boolean = runCatching { java.util.UUID.fromString(this) }.isSuccess

    private fun <T> Result<T>.toAppResult(fallback: String): AppResult<T> = fold(
        onSuccess = { AppResult.Success(it) },
        onFailure = { AppResult.Failure(it.message ?: fallback) },
    )

    @Serializable private data class IdRow(val id: String)
    @Serializable private data class SupabaseProfileRow(
        val id: String, @SerialName("display_name") val displayName: String = "", val email: String? = null, val phone: String = "",
        @SerialName("photo_url") val photoUrl: String? = null, val address: String? = null, val latitude: Double? = null, val longitude: Double? = null,
        @SerialName("setup_complete") val setupComplete: Boolean = false, @SerialName("email_verified") val emailVerified: Boolean = false,
        @SerialName("preferred_business_id") val preferredBusinessId: String? = null, @SerialName("preferred_station_id") val preferredStationId: String? = null,
        @SerialName("acquisition_source") val acquisitionSource: String = "UNKNOWN", @SerialName("acquired_at") val acquiredAt: String? = null
    ) {
        fun toDomain() = CustomerProfile(id, displayName, email, phone, photoUrl, address?.let { DeliveryAddress("Saved address", it, latitude?.let { lat -> longitude?.let { lon -> GeoPoint(lat, lon) } }) }, setupComplete, emailVerified, preferredBusinessId, preferredStationId, CustomerAcquisitionSource.fromString(acquisitionSource), acquiredAt?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() })
    }
}
