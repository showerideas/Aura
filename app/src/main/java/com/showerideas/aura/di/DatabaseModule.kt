package com.showerideas.aura.di

import android.content.Context
import androidx.room.Room
import com.showerideas.aura.data.local.AppDatabase
import com.showerideas.aura.data.local.BlockedEndpointDao
import com.showerideas.aura.data.local.ContactDao
import com.showerideas.aura.data.local.ExchangeAuditDao
import com.showerideas.aura.data.local.KnownPeerDao
import com.showerideas.aura.data.local.Migrations
import com.showerideas.aura.data.local.SharePresetDao
import com.showerideas.aura.data.local.ProfileDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "aura.db")
            // explicit migration framework. `fallbackToDestructiveMigration`
            // is intentionally NOT called — any future schema bump must add a
            // matching Migration object to [Migrations.ALL].
            .addMigrations(*Migrations.ALL)
            .build()

    @Provides
    fun provideContactDao(db: AppDatabase): ContactDao = db.contactDao()

    @Provides
    fun provideProfileDao(db: AppDatabase): ProfileDao = db.profileDao()

    /** blocklist DAO. */
    @Provides
    fun provideBlockedEndpointDao(db: AppDatabase): BlockedEndpointDao =
        db.blockedEndpointDao()

    /** TOFU peer-identity DAO. */
    @Provides
    fun provideKnownPeerDao(db: AppDatabase): KnownPeerDao =
        db.knownPeerDao()

    /** v5: Exchange audit log DAO. */
    @Provides
    fun provideExchangeAuditDao(db: AppDatabase): ExchangeAuditDao =
        db.exchangeAuditDao()

    /** v9: Share preset DAO (Phase 9.1). */
    @Provides
    fun provideSharePresetDao(db: AppDatabase): SharePresetDao =
        db.sharePresetDao()
}
