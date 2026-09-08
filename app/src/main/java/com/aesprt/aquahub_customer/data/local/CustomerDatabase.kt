package com.aesprt.aquahub_customer.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.aesprt.aquahub_customer.data.local.dao.CustomerProfileDao
import com.aesprt.aquahub_customer.data.local.dao.SavedAddressDao
import com.aesprt.aquahub_customer.data.local.entity.CustomerProfileEntity
import com.aesprt.aquahub_customer.data.local.entity.SavedAddressEntity

@Database(
    entities = [
        CustomerProfileEntity::class,
        SavedAddressEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class CustomerDatabase : RoomDatabase() {
    abstract fun profileDao(): CustomerProfileDao
    abstract fun addressDao(): SavedAddressDao

    companion object {
        private const val DB_NAME = "aquahub_customer.db"

        fun create(context: Context): CustomerDatabase {
            return Room.databaseBuilder(context, CustomerDatabase::class.java, DB_NAME)
                .fallbackToDestructiveMigration()
                .build()
        }
    }
}
