package com.showerideas.aura.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.room.TypeConverters
import com.showerideas.aura.data.local.SharePresetDao
import com.showerideas.aura.model.BlockedEndpoint
import com.showerideas.aura.model.Contact
import com.showerideas.aura.model.ExchangeAuditEntry
import com.showerideas.aura.model.KnownPeer
import com.showerideas.aura.model.Profile
import com.showerideas.aura.model.RoomMember
import com.showerideas.aura.model.RoomSession
import com.showerideas.aura.model.SharePreset

@Database(
    entities = [Contact::class, Profile::class, BlockedEndpoint::class, KnownPeer::class,
                ExchangeAuditEntry::class, SharePreset::class,
                RoomSession::class, RoomMember::class],
    version = 11,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun contactDao(): ContactDao
    abstract fun profileDao(): ProfileDao
    abstract fun blockedEndpointDao(): BlockedEndpointDao
    abstract fun knownPeerDao(): KnownPeerDao
    abstract fun exchangeAuditDao(): ExchangeAuditDao
    abstract fun sharePresetDao(): SharePresetDao
    abstract fun roomSessionDao(): RoomSessionDao

    companion object {
        /**
         * T17 — Migration 10 → 11: add [transport] column to exchange_audit_log.
         * NULL default is safe: existing records simply don't have a transport value.
         */
        val MIGRATION_10_11 = androidx.room.migration.Migration(10, 11) { db ->
            db.execSQL(
                "ALTER TABLE exchange_audit_log ADD COLUMN transport TEXT DEFAULT NULL"
            )
        }
    }
}
