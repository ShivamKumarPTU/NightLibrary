package com.example.nightlibrary.database

import android.content.Context
import android.util.Log
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.nightlibrary.core.security.DatabaseKeyManager
import com.example.nightlibrary.dao.*
import com.example.nightlibrary.entity.*
import net.sqlcipher.database.SupportFactory

@Database(
    entities = [
        ContactEntity::class,
        PasswordEntity::class,
        MediaEntity::class
    ],
    version = 11,  // ✅ BUMPED from 9 → 10 for duration field
    exportSchema = false
)
abstract class VaultDatabase : RoomDatabase() {

    abstract fun contactDao(): ContactDao
    abstract fun passwordDao(): PasswordDao
    abstract fun mediaDao(): MediaDao

    companion object {

        @Volatile
        private var INSTANCE: VaultDatabase? = null

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                safeAddColumn(db, "media", "isFailed", "INTEGER NOT NULL DEFAULT 0")
                safeAddColumn(db, "media", "failReason", "TEXT DEFAULT NULL")
                safeAddColumn(db, "media", "downloadedBytes", "INTEGER NOT NULL DEFAULT 0")
                safeAddColumn(db, "media", "useYtDlp", "INTEGER NOT NULL DEFAULT 0")
                safeAddColumn(db, "media", "isHls", "INTEGER NOT NULL DEFAULT 0")
                safeAddColumn(db, "media", "isPaused", "INTEGER NOT NULL DEFAULT 0")
                safeAddColumn(db, "media", "createdAt", "INTEGER NOT NULL DEFAULT 0")
                safeAddColumn(db, "media", "isInTrash", "INTEGER NOT NULL DEFAULT 0")
                safeAddColumn(db, "media", "resumeBytes", "INTEGER NOT NULL DEFAULT 0")
            }
        }

        private fun safeAddColumn(
            db: SupportSQLiteDatabase,
            table: String,
            column: String,
            definition: String
        ) {
            try {
                db.execSQL("ALTER TABLE $table ADD COLUMN $column $definition")
            } catch (e: Exception) {
                Log.d("VaultDatabase", "Column $table.$column already exists")
            }
        }

        fun getDatabase(context: Context): VaultDatabase {
            return INSTANCE ?: synchronized(this) {
                val keyManager = DatabaseKeyManager(context)
                val passphrase = keyManager.getPassphrase()
                val factory = SupportFactory(passphrase)

                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    VaultDatabase::class.java,
                    "vault_database_encrypted"
                )
                    .openHelperFactory(factory)
                    .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING) // ✅ Enable WAL for better concurrency
                    .addMigrations(
                        MIGRATION_6_7,
                        VaultMigrations.MIGRATION_ADD_SPEED,
                        VaultMigrations.MIGRATION_8_9,
                        VaultMigrations.MIGRATION_ADD_DURATION,
                        VaultMigrations.MIGRATION_10_11
                    )
                    .build()

                INSTANCE = instance
                instance
            }
        }
    }
}