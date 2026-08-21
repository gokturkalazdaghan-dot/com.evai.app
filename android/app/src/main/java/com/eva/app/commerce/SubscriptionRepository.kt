// android/app/src/main/java/com/eva/app/commerce/SubscriptionRepository.kt
package com.eva.app.commerce

import android.app.Activity
import android.util.Log
import com.revenuecat.purchases.Package
import kotlinx.coroutines.flow.StateFlow

private const val TAG = "SubscriptionRepository"

sealed class PurchaseUiEvent {
    data class Success(val state: SubscriptionState) : PurchaseUiEvent()
    data class Cancelled(val unused: Unit = Unit) : PurchaseUiEvent()
    /** `message` DOGRUDAN ekranda gosterilir -- teknik metin konmaz. */
    data class Failed(val message: String) : PurchaseUiEvent()
}

/**
 * ViewModel katmanının tek bağımlılığı — RevenueCatManager'ı sarmalar,
 * hataları RevenueCat'e özgü tiplerden UI'ın anlayacağı PurchaseUiEvent'e
 * çevirir. RevenueCatManager Hilt tarafından Application-scoped singleton
 * olarak enjekte edilir (bkz. CommerceModule.kt).
 */
class SubscriptionRepository(
    private val revenueCatManager: RevenueCatManager,
) {
    val subscriptionState: StateFlow<SubscriptionState> = revenueCatManager.subscriptionState

    suspend fun initialize(): SubscriptionState {
        return revenueCatManager.refreshCustomerInfo()
    }

    suspend fun fetchAvailablePackages(): Result<List<Package>> {
        return try {
            val offering = revenueCatManager.fetchCurrentOffering()
            Result.success(offering.availablePackages)
        } catch (e: RevenueCatError) {
            Log.e(TAG, "Paketler alınamadı.", e)
            Result.failure(e)
        }
    }

    suspend fun purchase(activity: Activity, packageToPurchase: Package): PurchaseUiEvent {
        return try {
            val state = revenueCatManager.purchase(activity, packageToPurchase)
            PurchaseUiEvent.Success(state)
        } catch (e: RevenueCatError.PurchaseFailed) {
            if (e.userCancelled) {
                PurchaseUiEvent.Cancelled()
            } else {
                Log.e(TAG, "Satın alma başarısız.", e)
                PurchaseUiEvent.Failed(e.toUserMessage())
            }
        } catch (e: RevenueCatError) {
            Log.e(TAG, "Satın alma başarısız.", e)
            PurchaseUiEvent.Failed(e.toUserMessage())
        }
    }

    suspend fun restorePurchases(): PurchaseUiEvent {
        return try {
            val state = revenueCatManager.restorePurchases()
            PurchaseUiEvent.Success(state)
        } catch (e: RevenueCatError) {
            Log.e(TAG, "Geri yükleme başarısız.", e)
            PurchaseUiEvent.Failed(e.toUserMessage())
        }
    }
}
