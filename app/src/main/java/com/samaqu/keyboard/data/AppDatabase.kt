package com.samaqu.keyboard.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        CachedCategory::class,
        CachedTemplate::class,
        CachedProduct::class,
        CachedVariant::class
    ],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun templateDao(): TemplateDao

    abstract fun productDao(): ProductDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /**
         * v2 adds the product catalogue cache used by the invoice's product picker.
         *
         * Written as a real migration rather than relying on the destructive fallback, so a
         * phone upgrading from v1 keeps its cached chat templates (the fallback would drop
         * them and Auto Text would be back to the offline seed until the next sync).
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `cached_products` (" +
                        "`id` TEXT NOT NULL, " +
                        "`name` TEXT NOT NULL, " +
                        "`category` TEXT NOT NULL, " +
                        "`series` TEXT NOT NULL, " +
                        "`kain` TEXT NOT NULL, " +
                        "`price` INTEGER NOT NULL, " +
                        "`colors` TEXT NOT NULL, " +
                        "PRIMARY KEY(`id`))"
                )
            }
        }

        /**
         * v3 adds the product variant (size/colour/stock) cache used by the picker's second
         * step. Migrated rather than recreated for the same reason as v2: the template cache
         * a phone already holds must survive the upgrade.
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `cached_variants` (" +
                        "`id` TEXT NOT NULL, " +
                        "`productId` TEXT NOT NULL, " +
                        "`color` TEXT NOT NULL, " +
                        "`size` TEXT NOT NULL, " +
                        "`stock` INTEGER NOT NULL, " +
                        "`priceOverride` INTEGER, " +
                        "`displayOrder` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`))"
                )
            }
        }

        fun get(ctx: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    ctx.applicationContext,
                    AppDatabase::class.java,
                    "samaqu.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
