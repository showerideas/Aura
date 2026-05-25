package com.showerideas.aura.di

import android.content.Context
import com.showerideas.aura.service.NearbyTransport
import com.showerideas.aura.service.WifiDirectTransport
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module for the `foss` product flavor.
 *
 * Provides [WifiDirectTransport] as [NearbyTransport] so that
 * [com.showerideas.aura.service.NearbyExchangeService] uses Wi-Fi Direct instead
 * of Google Nearby Connections, removing the Play Services dependency entirely.
 *
 * This variant is eligible for F-Droid distribution.
 * The `gms` flavor's [TransportModule] provides [NearbyConnectionsTransport] instead.
 */
@Module
@InstallIn(SingletonComponent::class)
object TransportModule {

    @Provides
    @Singleton
    fun provideNearbyTransport(@ApplicationContext context: Context): NearbyTransport =
        WifiDirectTransport(context)
}
