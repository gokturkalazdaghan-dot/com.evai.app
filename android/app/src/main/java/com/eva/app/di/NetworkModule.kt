// android/app/src/main/java/com/eva/app/di/NetworkModule.kt
package com.eva.app.di

import com.eva.app.core.AppConfig
import com.eva.app.network.APIClient
import com.eva.app.privacy.DataDeletionRepository
import com.eva.app.security.DeviceRegistrationGate
import com.eva.app.security.DeviceRegistrationRepository
import com.eva.app.security.PlayIntegrityManager
import com.eva.app.security.RequestSigner
import com.eva.app.security.SecureTokenStore
import com.eva.app.route.RouteRepository
import android.content.Context
import com.eva.app.ui.stations.StationsCache
import com.eva.app.ui.stations.StationsRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideRequestSigner(): RequestSigner {
        return RequestSigner()
    }

    @Provides
    @Singleton
    fun provideApiClient(
        playIntegrityManager: PlayIntegrityManager,
        secureTokenStore: SecureTokenStore,
        requestSigner: RequestSigner,
        registrationGate: DeviceRegistrationGate,
    ): APIClient {
        return APIClient(
            baseUrl = AppConfig.gatewayBaseUrl,
            playIntegrityManager = playIntegrityManager,
            secureTokenStore = secureTokenStore,
            requestSigner = requestSigner,
            registrationGate = registrationGate,
            certificatePins = AppConfig.gatewayCertificatePins,
        )
    }

    @Provides
    @Singleton
    fun provideDeviceRegistrationRepository(
        apiClient: APIClient,
        secureTokenStore: SecureTokenStore,
        requestSigner: RequestSigner,
        registrationGate: DeviceRegistrationGate,
    ): DeviceRegistrationRepository {
        return DeviceRegistrationRepository(
            apiClient,
            secureTokenStore,
            requestSigner,
            registrationGate,
        )
    }

    @Provides
    @Singleton
    fun provideDataDeletionRepository(
        @ApplicationContext context: Context,
        apiClient: APIClient,
        secureTokenStore: SecureTokenStore,
        requestSigner: RequestSigner,
    ): DataDeletionRepository {
        return DataDeletionRepository(context, apiClient, secureTokenStore, requestSigner)
    }

    @Provides
    @Singleton
    fun provideStationsRepository(
        apiClient: APIClient,
        @ApplicationContext context: Context,
    ): StationsRepository {
        return StationsRepository(apiClient, StationsCache(context))
    }

    @Provides
    @Singleton
    fun provideRouteRepository(apiClient: APIClient): RouteRepository {
        return RouteRepository(apiClient)
    }
}
