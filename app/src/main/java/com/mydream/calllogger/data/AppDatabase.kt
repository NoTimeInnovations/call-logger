package com.mydream.calllogger.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [CallEntity::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {

    abstract fun callDao(): CallDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /**
         * Adds the upload-tracking columns without touching existing rows. Old calls
         * default to synced = 0, so they get backfilled to the backend on first upload.
         * A real migration (never destructive) protects the historical call data.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE calls ADD COLUMN eventKey TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE calls ADD COLUMN e164 TEXT")
                db.execSQL("ALTER TABLE calls ADD COLUMN tzIana TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE calls ADD COLUMN synced INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_calls_synced ON calls(synced)")
            }
        }

        fun getInstance(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "call_logger.db"
                ).addMigrations(MIGRATION_1_2).build().also { INSTANCE = it }
            }
    }
}
