package com.showerideas.aura.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Central registry for Room schema migrations.
 *
 * Naming convention: `MIGRATION_<from>_<to>` (e.g. `MIGRATION_1_2`,
 * `MIGRATION_2_3`). Each migration must be:
 *   - Idempotent on a fresh install (Room runs `CREATE TABLE` from the
 *     latest `@Database` annotation, so migrations only run on upgrade
 *     paths).
 *   - Side-effect free other than DDL/DML against the supplied
 *     [SupportSQLiteDatabase].
 *
 * After adding a new migration, append it to [ALL]. The corresponding
 * JSON schema export under `app/schemas/<dbName>/<version>.json` is
 * required to be committed so Room can verify the upgrade.
 *
 * Example (kept commented so the compiler doesn't reference unknown columns):
 *
 * ```kotlin
 * val MIGRATION_1_2 = object : Migration(1, 2) {
 *     override fun migrate(db: SupportSQLiteDatabase) {
 *         db.execSQL("ALTER TABLE contacts ADD COLUMN tags TEXT NOT NULL DEFAULT ''")
 *     }
 * }
 * ```
 */
object Migrations {

    /**
     * Ordered list of every migration the app knows about. Empty until
     * the schema version is bumped past 1 — established here as a
     * permanent extension point so we never have to re-introduce
     * `fallbackToDestructiveMigration()`.
     */
    val ALL: Array<Migration> = emptyArray()
}
