package com.aesprt.aquahub_customer.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.aesprt.aquahub_customer.data.local.dao.CustomerProfileDao
import com.aesprt.aquahub_customer.data.local.dao.SavedAddressDao
import com.aesprt.aquahub_customer.data.local.entity.CustomerProfileEntity
import com.aesprt.aquahub_customer.data.local.entity.SavedAddressEntity

@Database(
    entities = [
        CustomerProfileEntity::class,
        SavedAddressEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class CustomerDatabase : RoomDatabase() {
    abstract fun profileDao(): CustomerProfileDao
    abstract fun addressDao(): SavedAddressDao

    companion object {
        private const val DB_NAME = "aquahub_customer.db"

        fun create(context: Context): CustomerDatabase {
            return Room.databaseBuilder(context, CustomerDatabase::class.java, DB_NAME)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()
        }

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE customer_profile ADD COLUMN addressLine TEXT")
                db.execSQL("ALTER TABLE customer_profile ADD COLUMN latitude REAL")
                db.execSQL("ALTER TABLE customer_profile ADD COLUMN longitude REAL")
                db.execSQL("ALTER TABLE customer_profile ADD COLUMN placeId TEXT")
                db.execSQL("ALTER TABLE customer_profile ADD COLUMN setupComplete INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE customer_profile ADD COLUMN emailVerified INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE saved_addresses ADD COLUMN ownerUid TEXT NOT NULL DEFAULT \"\"")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_saved_addresses_ownerUid ON saved_addresses(ownerUid)")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE customer_profile ADD COLUMN preferredBusinessId TEXT")
                db.execSQL("ALTER TABLE customer_profile ADD COLUMN preferredStationId TEXT")
                db.execSQL("ALTER TABLE customer_profile ADD COLUMN acquisitionSource TEXT NOT NULL DEFAULT 'UNKNOWN'")
                db.execSQL("ALTER TABLE customer_profile ADD COLUMN acquiredAt INTEGER")
            }
        }
    }
}
