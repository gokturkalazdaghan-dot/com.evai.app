// android/app/src/main/java/com/eva/app/commerce/RevenueCatManager.kt
package com.eva.app.commerce

import android.app.Activity
import android.content.Context
import android.util.Log
import com.eva.app.core.AppConfig
import com.revenuecat.purchases.CacheFetchPolicy
import com.revenuecat.purchases.CustomerInfo
import com.revenuecat.purchases.EntitlementInfo
import com.revenuecat.purchases.LogLevel
import com.revenuecat.purchases.Offering
import com.revenuecat.purchases.Offerings
import com.revenuecat.purchases.Package
import com.revenuecat.purchases.Purchases
import com.revenuecat.purchases.PurchasesConfiguration
import com.revenuecat.purchases.PurchasesError
// PurchasesError bir Throwable DEGILDIR -- SDK, hatayi
// PurchasesException icinde firlatir ve .error alaninda tasir.
import com.revenuecat.purchases.PurchasesException
import com.revenuecat.purchases.interfaces.UpdatedCustomerInfoListener
import com.revenuecat.purchases.awaitCustomerInfo
import com.revenuecat.purchases.awaitOfferings
import com.revenuecat.purchases.awaitPurchase
import com.revenuecat.purchases.awaitRestore
import com.revenuecat.purchases.PurchaseParams
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant

private const val TAG = "RevenueCatManager"

/**
 * RevenueCat Purchases SDK'sının uygulama genelinde tek instance olarak
 * sarmalandığı yönetici sınıf. Google Play Billing'in tüm karmaşıklığı
 * (BillingClient bağlantı yönetimi, acknowledge, purchase token yaşam
 * döngüsü) RevenueCat SDK'sının içinde soyutlanmış durumda — bu sınıf
 * yalnızca RevenueCat'in sonuçlarını Eva'nın kendi domain modeline
 * (SubscriptionState) çevirir.
 *
 * RevenueCat, kendi "app_user_id"sini otomatik üretir (anonim, cihaza
 * bağlı) — Eva'nın sıfır-PII ilkesiyle doğrudan uyumludur; biz bu ID'yi
 * asla kişisel veriyle eşlemeyiz.
 */
class RevenueCatManager(private val context: Context) {

    private val _subscriptionState = MutableStateFlow(SubscriptionState.UNKNOWN)
    val subscriptionState: StateFlow<SubscriptionState> = _subscriptionState.asStateFlow()

    private val _isConfigured = MutableStateFlow(false)
    val isConfigured: StateFlow<Boolean> = _isConfigured.asStateFlow()

    private val customerInfoListener = UpdatedCustomerInfoListener { customerInfo ->
        Log.d(TAG, "CustomerInfo güncellendi (RevenueCat push/poll).")
        _subscriptionState.value = mapToSubscriptionState(customerInfo)
    }

    /**
     * Application.onCreate()'te bir kez çağrılmalı. debugLogsEnabled yalnızca
     * debug build'de true olmalı — production'da RevenueCat log seviyesini
     * WARN'a düşürüyoruz ki hassas olmayan ama gereksiz ayrıntı loglara
     * yazılmasın.
     */
    /**
     * @param appUserId Gateway'in imzayla dogruladigi CIHAZ kimligi.
     *
     * NEDEN SART: bu verilmezse RevenueCat anonim bir kimlik uretir ve
     * webhook olayi sunucuda HANGI CIHAZA ait oldugu bilinmeden gelir --
     * yani satin alma yapilsa bile sunucu tarafi hak kaydi olusamaz ve
     * premium uclar kilitli kalir.
     */
    fun configure(debugLogsEnabled: Boolean, appUserId: String) {
        if (_isConfigured.value) {
            Log.w(TAG, "RevenueCat zaten yapılandırılmış, tekrar configure() çağrısı atlanıyor.")
            return
        }

        Purchases.logLevel = if (debugLogsEnabled) LogLevel.DEBUG else LogLevel.WARN

        val configuration = PurchasesConfiguration.Builder(context, AppConfig.revenueCatPublicApiKey)
            .appUserID(appUserId)
            .build()

        Purchases.configure(configuration)
        Purchases.sharedInstance.updatedCustomerInfoListener = customerInfoListener

        _isConfigured.value = true
        Log.i(TAG, "RevenueCat yapılandırıldı.")
    }

    /**
     * Mevcut abonelik durumunu sunucudan (RevenueCat'in kendi backend'i)
     * zorla tazeler. Uygulama açılışında ve "abonelik ekranı" görüntülenmeden
     * hemen önce çağrılması önerilir.
     */
    suspend fun refreshCustomerInfo(): SubscriptionState {
        return try {
            val customerInfo = Purchases.sharedInstance.awaitCustomerInfo(
                fetchPolicy = CacheFetchPolicy.FETCH_CURRENT,
            )
            val state = mapToSubscriptionState(customerInfo)
            _subscriptionState.value = state
            state
        } catch (e: PurchasesException) {
            Log.e(TAG, "CustomerInfo tazeleme başarısız: ${e.message}")
            _subscriptionState.value
        }
    }

    /**
     * RevenueCat Dashboard'da tanımlı mevcut "offering"i (aylık + yıllık
     * paketleri içeren teklif seti) döndürür. Offerings, Play Console'daki
     * ürünlerle RevenueCat tarafında eşleştirilmiş olmalı.
     */
    suspend fun fetchCurrentOffering(): Offering {
        val offerings: Offerings = try {
            Purchases.sharedInstance.awaitOfferings()
        } catch (e: PurchasesException) {
            Log.e(TAG, "Offerings alınamadı: ${e.message}")
            throw RevenueCatError.OfferingsUnavailable(e.message)
        }

        return offerings.current
            ?: throw RevenueCatError.OfferingsUnavailable(
                "RevenueCat Dashboard'da 'current' olarak işaretlenmiş bir offering yok."
            )
    }

    /**
     * Belirtilen paketi satın alır (örn. offering.monthly, offering.annual).
     * Sonuç RevenueCat'in kendi doğrulamasından geçmiş CustomerInfo'ya göre
     * hesaplanır — istemci tarafında ayrıca bir "acknowledge" veya sunucuya
     * makbuz gönderme adımı GEREKMEZ, RevenueCat bunu arka planda yönetir.
     */
    suspend fun purchase(activity: Activity, packageToPurchase: Package): SubscriptionState {
        val purchaseParams = PurchaseParams.Builder(activity, packageToPurchase).build()

        val result = try {
            Purchases.sharedInstance.awaitPurchase(purchaseParams)
        } catch (e: PurchasesException) {
            Log.e(TAG, "Satın alma başarısız: ${e.message}")
            throw RevenueCatError.PurchaseFailed(
                reason = e.message,
                userCancelled = e.code == com.revenuecat.purchases.PurchasesErrorCode.PurchaseCancelledError,
            )
        }

        val state = mapToSubscriptionState(result.customerInfo)
        _subscriptionState.value = state
        return state
    }

    /**
     * Kullanıcı yeni bir cihaza geçtiğinde veya uygulamayı yeniden
     * yüklediğinde önceki satın almalarını geri yükler.
     */
    suspend fun restorePurchases(): SubscriptionState {
        val customerInfo = try {
            Purchases.sharedInstance.awaitRestore()
        } catch (e: PurchasesException) {
            Log.e(TAG, "Geri yükleme başarısız: ${e.message}")
            throw RevenueCatError.RestoreFailed(e.message)
        }

        val state = mapToSubscriptionState(customerInfo)
        _subscriptionState.value = state
        return state
    }

    private fun mapToSubscriptionState(customerInfo: CustomerInfo): SubscriptionState {
        val entitlement: EntitlementInfo? =
            customerInfo.entitlements[AppConfig.REVENUECAT_ENTITLEMENT_ID]

        if (entitlement == null || !entitlement.isActive) {
            // Entitlement hiç yoksa ya da aktif değilse — ancak geçmişte bir
            // satın alma varsa (expirationDate dolu ama isActive=false),
            // bunu EXPIRED olarak ayırt ediyoruz; hiç satın alma yoksa FREE.
            val hadPriorPurchase = entitlement?.latestPurchaseDate != null
            return SubscriptionState(
                tier = if (hadPriorPurchase) SubscriptionTier.EXPIRED else SubscriptionTier.FREE,
                activeProductId = entitlement?.productIdentifier,
                expirationDate = entitlement?.expirationDate?.toInstant(),
                willAutoRenew = false,
                isSandbox = customerInfo.entitlements.all.values.any { it.isSandbox },
                lastUpdatedAt = Instant.now(),
            )
        }

        val tier = when {
            entitlement.periodType == com.revenuecat.purchases.PeriodType.TRIAL ->
                SubscriptionTier.TRIALING
            entitlement.billingIssueDetectedAt != null ->
                SubscriptionTier.BILLING_ISSUE
            entitlement.periodType == com.revenuecat.purchases.PeriodType.INTRO ->
                SubscriptionTier.TRIALING
            entitlement.unsubscribeDetectedAt != null && entitlement.isActive ->
                // Kullanıcı yenilemeyi iptal etti ama dönem sonuna kadar
                // erişim devam ediyor — hâlâ ACTIVE, ama willAutoRenew=false.
                SubscriptionTier.ACTIVE
            else ->
                SubscriptionTier.ACTIVE
        }

        return SubscriptionState(
            tier = tier,
            activeProductId = entitlement.productIdentifier,
            expirationDate = entitlement.expirationDate?.toInstant(),
            willAutoRenew = entitlement.unsubscribeDetectedAt == null,
            isSandbox = entitlement.isSandbox,
            lastUpdatedAt = Instant.now(),
        )
    }
}
