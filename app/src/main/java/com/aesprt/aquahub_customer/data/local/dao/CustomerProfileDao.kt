package com.aesprt.aquahub_customer.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.aesprt.aquahub_customer.data.local.entity.CustomerProfileEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CustomerProfileDao {
    @Query("SELECT * FROM customer_profile LIMIT 1")
    fun getProfile(): Flow<CustomerProfileEntity?>

    @Query("SELECT * FROM customer_profile LIMIT 1")
    suspend fun getProfileOnce(): CustomerProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProfile(profile: CustomerProfileEntity)

    @Query("DELETE FROM customer_profile")
    suspend fun clear()
}
