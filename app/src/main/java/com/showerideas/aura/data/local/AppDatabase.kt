package com.showerideas.aura.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.showerideas.aura.model.BlockedEndpoint
import com.showerideas.aura.model.Contact
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
 *  - v2: Adds BlockedEndpoint for the endpoint blocklist (PR-14).
 *        Migration: [Migrations.MIGRATION_1_2].
 */
@Database(
    entities = [Contact::class, Profile::class, BlockedEndpoint::class],
    version = 2,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun contactDao(): ContactDao
    abstract fun profileDao(): ProfileDao
    abstract fun blockedEndpointDao(): BlockedEndpointDao
}
