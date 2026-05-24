package com.showerideas.aura.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.showerideas.aura.model.BlockedEndpoint
import com.showerideas.aura.model.Contact
import com.showerideas.aura.model.ExchangeAuditEntry
import com.showerideas.aura.model.KnownPeer
import com.showerideas.aura.model.Profile

/**
 * Single source of truth for all local persistent data.
 *
 * The database file itself is stored in the app's private directory.
 * For sensitive fields (encryption keys, gesture pattern feature vectors),
 * we rely on EncryptedSharedPreferences or the Android Keystore — not here.
 *
 * Version history:
 *  - v1: Contact + Profile (initial scaffold)
 *  - v2: Adds BlockedEndpoint for the endpoint blocklist.
 *        Migration: [Migrations.MIGRATION_1_2].
 *  - v3: Adds KnownPeer for persisted TOFU endpoint-identity registry.
 *        Migration: [Migrations.MIGRATION_2_3].
 *  - v4: Adds identityKeyHash column to blocked_endpoints and contacts.
 *        Migration: [Migrations.MIGRATION_3_4].
 *  - v5: Adds exchange_audit_log table for privacy-preserving exchange history.
 *        Migration: [Migrations.MIGRATION_4_5].
 *  - v6: Adds profile_type, is_active, custom_label columns to profile table.
 *        Migration: [Migrations.MIGRATION_5_6]. (Phase 6.4 — multiple profiles)
 *  - v7: Adds rotation_certificate column to known_peers table.
 *        Migration: [Migrations.MIGRATION_6_7]. (Phase 6.5 — key rotation)
 *  - v8: Adds version to profile, profile_version to contacts,
 *        last_seen_profile_version to known_peers.
 *        Migration: [Migrations.MIGRATION_7_8]. (Phase 6.7 — profile versioning)
 */
@Database(
    entities = [Contact::class, Profile::class, BlockedEndpoint::class, KnownPeer::class,
                ExchangeAuditEntry::class],
    version = 8,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun contactDao(): ContactDao
    abstract fun profileDao(): ProfileDao
    abstract fun blockedEndpointDao(): BlockedEndpointDao
    abstract fun knownPeerDao(): KnownPeerDao
    abstract fun exchangeAuditDao(): ExchangeAuditDao
}
