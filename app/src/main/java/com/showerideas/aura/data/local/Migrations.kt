package com.showerideas.aura.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Central registry for Room schema migrations.
 *
 * Naming convention: `MIGRATION_<from>_<to>` (e.g. `MIGRATION_1_2`).
 * Each migration must be:
 *   - Idempotent on a fresh install (Room runs `CREATE TABLE` from the
 *     latest `@Database` annotation, so migrations only run on upgrade
 *     paths).
 *   - Side-effect free other than DDL/DML against the supplied
 *     [SupportSQLiteDatabase].
 *
 * After adding a new migration, append it to [ALL]. The corresponding
 * JSON schema export under `app/schemas/<dbName>/<version>.json` is
 * required to be committed so Room can verify the upgrade.
 */
object Migrations {

    /**
     * PR-14: add the `blocked_endpoints` table. Schema mirrors the
     * [com.showerideas.aura.model.BlockedEndpoint] entity exactly so the
     * post-migration database is bit-identical to a fresh v2 install.
     */
    val MIGRATION_1_2: Migration = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS blocked_endpoints (
                    endpointId TEXT NOT NULL PRIMARY KEY,
                    blockedAt INTEGER NOT NULL,
                    note TEXT NOT NULL DEFAULT ''
                )
                """.trimIndent()
            )
        }
    }

    /** Ordered list of every migration the app knows about. */
    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2)
}
