// android/app/src/main/java/com/eva/app/di/SecurityModule.kt
package com.eva.app.di

import android.content.Context
import com.eva.app.core.AppConfig
import com.eva.app.security.PlayIntegrityManager
import com.eva.app.security.SecureTokenStore
import com.eva.app.vehicle.VehicleProfileRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object SecurityModule {

    @Provides
    @Singleton
    fun provideSecureTokenStore(@ApplicationContext context: Context): SecureTokenStore {
        return SecureTokenStore(context)
    }

    @Provides
    @Singleton
    fun providePlayIntegrityManager(@ApplicationContext context: Context): PlayIntegrityManager {
        return PlayIntegrityManager(
            context = context,
            cloudProjectNumber = AppConfig.googleCloudProjectNumber,
        )
    }

    @Provides
    @Singleton
    fun provideVehicleProfileRepository(secureTokenStore: SecureTokenStore): VehicleProfileRepository {
        return VehicleProfileRepository(secureTokenStore)
    }
}
