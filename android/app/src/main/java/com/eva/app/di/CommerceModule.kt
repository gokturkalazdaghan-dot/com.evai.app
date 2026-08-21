// android/app/src/main/java/com/eva/app/di/CommerceModule.kt
package com.eva.app.di

import android.content.Context
import com.eva.app.commerce.RevenueCatManager
import com.eva.app.commerce.SubscriptionRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object CommerceModule {

    /**
     * RevenueCatManager burada singleton olarak sağlanıyor, AMA configure()
     * metodu burada ÇAĞRILMIYOR — bu, EvaApplication.onCreate() içinde en
     * erken noktada, uygulamanın tüm yaşam döngüsü boyunca bir kez
     * yapılmalı. Hilt'in bu provider'ı çağırdığı an ile Application.onCreate()
     * arasındaki sıralamayı garanti altına almak için configure() çağrısı
     * kasıtlı olarak burada değil, Application sınıfında tutuluyor.
     */
    @Provides
    @Singleton
    fun provideRevenueCatManager(@ApplicationContext context: Context): RevenueCatManager {
        return RevenueCatManager(context)
    }

    @Provides
    @Singleton
    fun provideSubscriptionRepository(revenueCatManager: RevenueCatManager): SubscriptionRepository {
        return SubscriptionRepository(revenueCatManager)
    }
}
