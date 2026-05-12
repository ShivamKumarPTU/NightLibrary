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
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Database(
    entities = [
        ContactEntity::class,
        PasswordEntity::class,
        MediaEntity::class
    ],
    version = 13,  // ✅ BUMPED from 12 → 13 for isPrivate field
    exportSchema = false
)
abstract class VaultDatabase : RoomDatabase() {

    abstract fun contactDao(): ContactDao
    abstract fun passwordDao(): PasswordDao
    abstract fun mediaDao(): MediaDao

    companion object {

        init {
            try {
                System.loadLibrary("sqlcipher")
            } catch (e: UnsatisfiedLinkError) {
                Log.e("VaultDatabase", "Failed to load sqlcipher library", e)
            }
        }

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
                val factory = SupportOpenHelperFactory(passphrase)

                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    VaultDatabase::class.java,
                    "vault_database_encrypted"
                )
                    .openHelperFactory(factory)
                    .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                    .addCallback(object : Callback() {
                        override fun onOpen(db: SupportSQLiteDatabase) {
                            super.onOpen(db)
                            // 🔥 10/10 Performance: Set busy timeout to prevent monitor contention
                            // SQLCipher fix: Use query() and moveToFirst() instead of execSQL() for PRAGMA 
                            // as it may return a value and be interpreted as a query.
                            try {
                                db.query("PRAGMA busy_timeout = 5000").use { it.moveToFirst() }
                                Log.d("VaultDatabase", "PRAGMA busy_timeout set to 5000ms")
                            } catch (e: Exception) {
                                Log.e("VaultDatabase", "Failed to set PRAGMA busy_timeout: ${e.message}")
                            }
                        }
                    })
                    .addMigrations(
                        MIGRATION_6_7,
                        VaultMigrations.MIGRATION_ADD_SPEED,
                        VaultMigrations.MIGRATION_8_9,
                        VaultMigrations.MIGRATION_ADD_DURATION,
                        VaultMigrations.MIGRATION_10_11,
                        VaultMigrations.MIGRATION_11_12,
                        VaultMigrations.MIGRATION_12_13
                    )
                    .build()

                INSTANCE = instance
                instance
            }
        }
    }
}