package com.showerideas.aura.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
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
 *  - v3: Adds KnownPeer for persisted TOFU endpoint-identity registry ().
 *        Migration: [Migrations.MIGRATION_2_3].
 *  - v4: Adds identityKeyHash column to blocked_endpoints and contacts ().
 *        Migration: [Migrations.MIGRATION_3_4].
 *  - v5: Adds exchange_audit_log table for privacy-preserving exchange history.
 *        Migration: [Migrations.MIGRATION_4_5].
 */
@Database(
    entities = [Contact::class, Profile::class, BlockedEndpoint::class, KnownPeer::class,
                ExchangeAuditEntry::class],
    version = 5,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun contactDao(): ContactDao
    abstract fun profileDao(): ProfileDao
    abstract fun blockedEndpointDao(): BlockedEndpointDao
    abstract fun knownPeerDao(): KnownPeerDao
    abstract fun exchangeAuditDao(): ExchangeAuditDao
}
