package com.showerideas.aura.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.showerideas.aura.data.SharePresetDao
import com.showerideas.aura.model.BlockedEndpoint
import com.showerideas.aura.model.Contact
import com.showerideas.aura.model.ExchangeAuditEntry
import com.showerideas.aura.model.KnownPeer
import com.showerideas.aura.model.Profile
import com.showerideas.aura.model.SharePreset

@Database(
    entities = [Contact::class, Profile::class, BlockedEndpoint::class, KnownPeer::class,
                ExchangeAuditEntry::class, SharePreset::class],
    version = 9,
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
}
