package com.aesprt.aquahub_customer.data.supabase

import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.from
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.security.MessageDigest
import java.time.Instant

/** Supabase is the token registry; Firebase Messaging remains the transport. */
object SupabaseDeviceTokenRepository {
    @Serializable
    private data class DeviceTokenRecord(
        @SerialName("profile_id") val profileId: String,
        @SerialName("token_hash") val tokenHash: String,
        val token: String,
        val platform: String,
        @SerialName("last_seen_at") val lastSeenAt: String
    )

    suspend fun upsert(token: String) {
        val profileId = SupabaseClientProvider.client.auth.currentUserOrNull()?.id ?: return
        SupabaseClientProvider.client.from("device_tokens").upsert(
            DeviceTokenRecord(profileId, sha256(token), token, "ANDROID", Instant.now().toString())
        ) { onConflict = "profile_id,token_hash" }
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
}
