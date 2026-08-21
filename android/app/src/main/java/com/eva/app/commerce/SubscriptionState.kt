// android/app/src/main/java/com/eva/app/commerce/SubscriptionState.kt
package com.eva.app.commerce

import java.time.Instant

enum class SubscriptionTier {
    FREE, TRIALING, ACTIVE, EXPIRED, GRACE_PERIOD, BILLING_ISSUE, REVOKED
}

/**
 * RevenueCat'in CustomerInfo/EntitlementInfo nesnesinden türetilen, uygulama
 * genelinde kullanılan sadeleştirilmiş abonelik durumu. UI katmanı
 * doğrudan RevenueCat tiplerine bağımlı olmasın diye bu domain modeli
 * kullanılıyor — RevenueCat SDK'sı gelecekte değişse bile UI kodu etkilenmez.
 */
data class SubscriptionState(
    val tier: SubscriptionTier,
    val activeProductId: String?,
    val expirationDate: Instant?,
    val willAutoRenew: Boolean,
    val isSandbox: Boolean,
    val lastUpdatedAt: Instant,
) {
    companion object {
        val UNKNOWN = SubscriptionState(
            tier = SubscriptionTier.FREE,
            activeProductId = null,
            expirationDate = null,
            willAutoRenew = false,
            isSandbox = false,
            lastUpdatedAt = Instant.EPOCH,
        )
    }

    val isPremiumActive: Boolean
        get() = tier == SubscriptionTier.ACTIVE ||
            tier == SubscriptionTier.TRIALING ||
            tier == SubscriptionTier.GRACE_PERIOD
}
