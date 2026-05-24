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

    /**
     * FIX-2: add the `known_peers` table for the persisted TOFU endpoint-identity
     * registry. Schema mirrors [com.showerideas.aura.model.KnownPeer] exactly.
     */
    val MIGRATION_2_3: Migration = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS known_peers (
                    endpointId TEXT NOT NULL PRIMARY KEY,
                    identityPublicKeyBase64 TEXT NOT NULL,
                    firstSeenAt INTEGER NOT NULL,
                    lastSeenAt INTEGER NOT NULL
                )
                """.trimIndent()
            )
        }
    }

    /**
     * FIX-5: add stable identity-key hash column to both `blocked_endpoints`
     * and `contacts`. Both columns are nullable (DEFAULT NULL) so existing
     * rows are unaffected and no backfill is required.
     */
    val MIGRATION_3_4: Migration = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE blocked_endpoints ADD COLUMN identityKeyHash TEXT DEFAULT NULL"
            )
            db.execSQL(
                "ALTER TABLE contacts ADD COLUMN identityKeyHash TEXT DEFAULT NULL"
            )
        }
    }

    /**
     * v5: Add exchange_audit_log table for privacy-preserving exchange history.
     *
     * Stores only: id, timestampMs, peerIdentityKeyHash (nullable), direction,
     * outcome, errorCode (nullable), channel. No plaintext PII.
     */
    val MIGRATION_4_5: Migration = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS exchange_audit_log (
                    id TEXT NOT NULL PRIMARY KEY,
                    timestampMs INTEGER NOT NULL,
                    peerIdentityKeyHash TEXT DEFAULT NULL,
                    direction TEXT NOT NULL DEFAULT 'BOTH',
                    outcome TEXT NOT NULL,
                    errorCode TEXT DEFAULT NULL,
                    channel TEXT NOT NULL DEFAULT 'NEARBY'
                )
                """.trimIndent()
            )
        }
    }

    /** Ordered list of every migration the app knows about. */
    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
}
