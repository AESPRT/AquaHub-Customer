package com.aesprt.aquahub_customer.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.aesprt.aquahub_customer.domain.CustomerProfile

@Entity(tableName = "customer_profile")
data class CustomerProfileEntity(
    @PrimaryKey val uid: String,
    val displayName: String,
    val email: String?,
    val phone: String,
    val photoUrl: String? = null,
    val updatedAt: Long = System.currentTimeMillis()
) {
    fun toDomain() = CustomerProfile(
        uid = uid,
        displayName = displayName,
        email = email,
        phone = phone,
        photoUrl = photoUrl
    )
}

fun CustomerProfile.toEntity() = CustomerProfileEntity(
    uid = uid,
    displayName = displayName,
    email = email,
    phone = phone,
    photoUrl = photoUrl
)
