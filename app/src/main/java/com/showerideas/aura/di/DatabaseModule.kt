package com.showerideas.aura.di

import android.content.Context
import androidx.room.Room
import com.showerideas.aura.data.local.AppDatabase
import com.showerideas.aura.data.local.ContactDao
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
            // v1 → vN: add explicit Migration objects here before release.
            // fallbackToDestructiveMigration is acceptable for v1.0 dev builds only.
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    fun provideContactDao(db: AppDatabase): ContactDao = db.contactDao()

    @Provides
    fun provideProfileDao(db: AppDatabase): ProfileDao = db.profileDao()
}
