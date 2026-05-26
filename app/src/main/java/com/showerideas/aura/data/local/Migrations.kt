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
 *
 * Version history:
 *  v1 → v2: blocked_endpoints table
 *  v2 → v3: known_peers table
 *  v3 → v4: identityKeyHash columns on blocked_endpoints + contacts
 *  v4 → v5: exchange_audit_log table
 *  v5 → v6: profile_type, is_active, custom_label columns on profile
 *  v6 → v7: rotation_certificate column on known_peers (Phase 6.5)
 *  v7 → v8: version column on profile, profile_version on contacts,
 *           last_seen_profile_version on known_peers (Phase 6.7)
 */
object Migrations {

    /**
     * v2: Add the `blocked_endpoints` table. Schema mirrors the
     * [com.showerideas.aura.model.BlockedEndpoint] entity exactly.
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
     * v3: Add the `known_peers` table for the persisted TOFU endpoint-identity
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
     * v4: Add stable identity-key hash column to both `blocked_endpoints`
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

    /**
     * v6: Multiple profiles — add profile_type, is_active, and custom_label
     * columns to the `profile` table.
     *
     * - `profile_type TEXT NOT NULL DEFAULT 'PERSONAL'` — preserves the existing
     *   single-profile as Personal; new profiles default to PERSONAL.
     * - `is_active INTEGER NOT NULL DEFAULT 1` — the first (and previously only)
     *   profile row is active by default.
     * - `custom_label TEXT NOT NULL DEFAULT ''` — user-supplied label for
     *   ProfileType.CUSTOM (unused until Phase 6.4.3).
     */
    val MIGRATION_5_6: Migration = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE profile ADD COLUMN profile_type TEXT NOT NULL DEFAULT 'PERSONAL'"
            )
            db.execSQL(
                "ALTER TABLE profile ADD COLUMN is_active INTEGER NOT NULL DEFAULT 1"
            )
            db.execSQL(
                "ALTER TABLE profile ADD COLUMN custom_label TEXT NOT NULL DEFAULT ''"
            )
        }
    }

    /**
     * v7: Key rotation — add rotation_certificate column to `known_peers`.
     *
     * Stores the raw DER bytes of a key-rotation certificate: the new public
     * key signed by the old private key. Null for peers that have never
     * rotated their identity key.
     *
     * Room maps `ByteArray?` to BLOB natively; no TypeConverter needed.
     * DEFAULT NULL so existing rows are unaffected.
     */
    val MIGRATION_6_7: Migration = object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE known_peers ADD COLUMN rotation_certificate BLOB DEFAULT NULL"
            )
        }
    }

    /**
     * v8: Profile versioning (Phase 6.7) and contact deduplication improvements (Phase 6.3).
     *
     * - `profile.version INTEGER NOT NULL DEFAULT 1` — auto-incremented on every profile save.
     *   Sent to peers so they can detect when a returning contact updated their card.
     * - `contacts.profile_version INTEGER NOT NULL DEFAULT 0` — the version received at
     *   exchange time; compared against [known_peers.last_seen_profile_version] to detect updates.
     * - `known_peers.last_seen_profile_version INTEGER NOT NULL DEFAULT 0` — the most recent
     *   profile version we have seen from this peer. Updated after every successful exchange.
     */
    val MIGRATION_7_8: Migration = object : Migration(7, 8) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE profile ADD COLUMN version INTEGER NOT NULL DEFAULT 1"
            )
            db.execSQL(
                "ALTER TABLE contacts ADD COLUMN profile_version INTEGER NOT NULL DEFAULT 0"
            )
            db.execSQL(
                "ALTER TABLE known_peers ADD COLUMN last_seen_profile_version INTEGER NOT NULL DEFAULT 0"
            )
        }
    }


    val MIGRATION_8_9 = object : Migration(8, 9) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS share_presets (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    name TEXT NOT NULL,
                    fieldSet TEXT NOT NULL,
                    lastUsedAt INTEGER NOT NULL DEFAULT 0,
                    isDefault INTEGER NOT NULL DEFAULT 0
                )"""
            )
        }
    }

    /** Ordered list of every migration — passed to Room.databaseBuilder. */
    val ALL: Array<Migration> = arrayOf(
        MIGRATION_1_2,
        MIGRATION_2_3,
        MIGRATION_3_4,
        MIGRATION_4_5,
        MIGRATION_5_6,
        MIGRATION_6_7,
        MIGRATION_7_8
    )
}
