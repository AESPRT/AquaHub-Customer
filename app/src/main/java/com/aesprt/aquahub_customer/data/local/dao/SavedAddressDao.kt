package com.aesprt.aquahub_customer.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.aesprt.aquahub_customer.data.local.entity.SavedAddressEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SavedAddressDao {
    @Query("SELECT * FROM saved_addresses ORDER BY id ASC")
    fun getAll(): Flow<List<SavedAddressEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(address: SavedAddressEntity): Long

    @Query("DELETE FROM saved_addresses WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM saved_addresses WHERE addressLine = :addressLine")
    suspend fun deleteByAddressLine(addressLine: String)

    @Query("DELETE FROM saved_addresses")
    suspend fun clear()
}
