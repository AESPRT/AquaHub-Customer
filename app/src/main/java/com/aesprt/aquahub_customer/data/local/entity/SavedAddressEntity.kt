package com.aesprt.aquahub_customer.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.aesprt.aquahub_customer.domain.DeliveryAddress
import com.aesprt.aquahub_customer.domain.GeoPoint

@Entity(
    tableName = "saved_addresses",
    indices = [Index(value = ["ownerUid"])],
)
data class SavedAddressEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ownerUid: String,
    val label: String,
    val addressLine: String,
    val latitude: Double?,
    val longitude: Double?,
    val placeId: String? = null,
    val createdAt: Long = System.currentTimeMillis()
) {
    fun toDomain() = DeliveryAddress(
        label = label,
        addressLine = addressLine,
        location = if (latitude != null && longitude != null) GeoPoint(latitude, longitude) else null,
        placeId = placeId
    )
}

fun DeliveryAddress.toEntity(ownerUid: String) = SavedAddressEntity(
    ownerUid = ownerUid,
    label = label,
    addressLine = addressLine,
    latitude = location?.latitude,
    longitude = location?.longitude,
    placeId = placeId
)
