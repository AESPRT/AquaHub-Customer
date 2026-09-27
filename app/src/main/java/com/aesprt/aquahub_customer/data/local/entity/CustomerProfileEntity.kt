package com.aesprt.aquahub_customer.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.aesprt.aquahub_customer.domain.CustomerProfile
import com.aesprt.aquahub_customer.domain.DeliveryAddress
import com.aesprt.aquahub_customer.domain.GeoPoint
import com.aesprt.aquahub_customer.domain.CustomerAcquisitionSource

@Entity(tableName = "customer_profile")
data class CustomerProfileEntity(
    @PrimaryKey val uid: String,
    val displayName: String,
    val email: String?,
    val phone: String,
    val photoUrl: String? = null,
    val addressLine: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val placeId: String? = null,
    val setupComplete: Boolean = false,
    val emailVerified: Boolean = false,
    val preferredBusinessId: String? = null,
    val preferredStationId: String? = null,
    val acquisitionSource: String = CustomerAcquisitionSource.UNKNOWN.name,
    val acquiredAt: Long? = null,
    val updatedAt: Long = System.currentTimeMillis()
) {
    fun toDomain() = CustomerProfile(
        uid = uid,
        displayName = displayName,
        email = email,
        phone = phone,
        photoUrl = photoUrl,
        address = if (addressLine != null && latitude != null && longitude != null) DeliveryAddress("Primary", addressLine, GeoPoint(latitude, longitude), placeId) else null,
        setupComplete = setupComplete,
        emailVerified = emailVerified,
        preferredBusinessId = preferredBusinessId,
        preferredStationId = preferredStationId,
        acquisitionSource = CustomerAcquisitionSource.fromString(acquisitionSource),
        acquiredAt = acquiredAt,
    )
}

fun CustomerProfile.toEntity() = CustomerProfileEntity(
    uid = uid,
    displayName = displayName,
    email = email,
    phone = phone,
    photoUrl = photoUrl,
    addressLine = address?.addressLine,
    latitude = address?.location?.latitude,
    longitude = address?.location?.longitude,
    placeId = address?.placeId,
    setupComplete = setupComplete,
    emailVerified = emailVerified,
    preferredBusinessId = preferredBusinessId,
    preferredStationId = preferredStationId,
    acquisitionSource = acquisitionSource.name,
    acquiredAt = acquiredAt,
)
