package com.example.nightlibrary.database

import android.util.Log
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * All Room migrations for VaultDatabase.
 * Each migration safely adds columns with try-catch to prevent
 * crashes on re-install or migration re-run.
 */
object VaultMigrations {

    private const val TAG = "VaultMigrations"

    /**
     * Migration 7→8: Add currentSpeed column.
     * (Already existed in your codebase)
     */
    val MIGRATION_ADD_SPEED = object : Migration(7, 8) {
        override fun migrate(db: SupportSQLiteDatabase) {
            safeAddColumn(db, "media", "currentSpeed", "REAL NOT NULL DEFAULT 0.0")
        }
    }

    /**
     * Migration 8→9: (Your existing migration if any — placeholder)
     * If version 9 was already deployed, this is a no-op safety net.
     */
    val MIGRATION_8_9 = object : Migration(8, 9) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Safety: add any columns that might have been added between 8 and 9
            safeAddColumn(db, "media", "currentSpeed", "REAL NOT NULL DEFAULT 0.0")
        }
    }

    /**
     * ✅ NEW: Migration 9→10: Add duration column.
     * Problem 6: Duration not stored in DB → player can't show total time.
     */
    val MIGRATION_ADD_DURATION = object : Migration(9, 10) {
        override fun migrate(db: SupportSQLiteDatabase) {
            safeAddColumn(db, "media", "duration", "INTEGER NOT NULL DEFAULT 0")
            Log.d(TAG, "Migration 9→10: Added duration column")
        }
    }

    /**
     * Migration 10→11: Add unique index to contacts table.
     * Handles existing duplicates by keeping the earliest entry for each phone number.
     */
    val MIGRATION_10_11 = object : Migration(10, 11) {
        override fun migrate(db: SupportSQLiteDatabase) {
            try {
                // 1. Remove duplicates before applying unique index
                // 🔥 SQLCipher fix: Use query() and moveToFirst() for statements that might be misidentified as queries
                // or that return values. DELETE with sub-select can sometimes be misidentified.
                db.query("""
                    DELETE FROM contacts 
                    WHERE id NOT IN (
                        SELECT MIN(id) 
                        FROM contacts 
                        GROUP BY phone
                    )
                """.trimIndent()).use { it.moveToFirst() }
                
                // 2. Create the unique index
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_contacts_phone` ON `contacts` (`phone`)")
                Log.d(TAG, "Migration 10→11: Cleaned duplicates and created unique index on contacts(phone)")
            } catch (e: Exception) {
                Log.e(TAG, "Migration 10→11 Critical Failure: ${e.message}")
                throw e // Rethrow so Room knows migration failed if it truly can't apply
            }
        }
    }

    val MIGRATION_11_12 = object : Migration(11, 12) {
        override fun migrate(db: SupportSQLiteDatabase) {
            safeAddColumn(db, "media", "streamUrl", "TEXT DEFAULT NULL")
            Log.d(TAG, "Migration 11→12: Added streamUrl column")
        }
    }

    /**
     * Migration 12→13: Add isPrivate column for Private Import Mode persistence.
     */
    val MIGRATION_12_13 = object : Migration(12, 13) {
        override fun migrate(db: SupportSQLiteDatabase) {
            safeAddColumn(db, "media", "isPrivate", "INTEGER NOT NULL DEFAULT 0")
            Log.d(TAG, "Migration 12→13: Added isPrivate column")
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
            Log.d(TAG, "Added column $table.$column")
        } catch (e: Exception) {
            Log.d(TAG, "Column $table.$column already exists: ${e.message}")
        }
    }
}