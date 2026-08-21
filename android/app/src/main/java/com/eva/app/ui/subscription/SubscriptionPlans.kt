// android/app/src/main/java/com/eva/app/ui/subscription/SubscriptionPlans.kt
package com.eva.app.ui.subscription

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.eva.app.R
import com.revenuecat.purchases.Package
import com.revenuecat.purchases.PackageType

/**
 * Abonelik planlarinin SUNUMU.
 *
 * FIYATLAR BURADA SABIT DEGIL -- NEDEN
 * ------------------------------------
 * Urun fiyati aylik 4,99 USD / yillik 39,99 USD olarak Play Console'da
 * tanimlanir. Ekranda gosterilen tutar ise DAIMA Google'in o kullanici
 * icin dondurdugu yerel fiyattir (`product.price.formatted`).
 *
 * Sabit "$4,99" yazmak, Turkiye'deki bir kullaniciya dolar gosterip
 * hesabindan lira cekilmesine yol acardi: hem yaniltici hem de Google
 * Play politikasina aykiri. Ayni sebeple deneme suresi de urunun
 * kendisinden okunur, elle yazilmaz.
 *
 * Asagidaki sabitler yalnizca YAPILANDIRMA REFERANSIDIR: Play Console
 * ve RevenueCat panelinde ayni degerlerin girildigini dogrulamak icin.
 */
object PlanConfig {
    /** Play Console'da tanimlanmasi gereken temel fiyat (USD). */
    const val MONTHLY_USD = "4.99"
    const val ANNUAL_USD = "39.99"

    /** Play Console'daki teklife eklenmesi gereken ucretsiz deneme. */
    const val TRIAL_DAYS = 3
}

/** Ekranda gosterilecek tek bir plan. */
data class PlanOption(
    val rcPackage: Package,
    /**
     * Kisa baslik. Sabit metin DEGIL kaynak kimligi: buildPlanOptions
     * bir @Composable degil ve stringResource cagiramaz.
     */
    @StringRes val titleRes: Int,
    /** Google'in dondurdugu YEREL fiyat metni. */
    val priceText: String,
    /** "ayda 4,16 ₺ gibi" -- yillik planin aylik karsiligi; aylik planda null. */
    val perMonthText: String?,
    /**
     * Yillik planin aylik plana gore yuzde tasarrufu. Iki fiyat da
     * okunamiyorsa null -- uydurma bir "%17 tasarruf" rozeti basilmaz.
     */
    val savingsPercent: Int?,
    /** Urunde tanimli ucretsiz deneme gun sayisi; yoksa null. */
    val trialDays: Int?,
    val isRecommended: Boolean,
)

/**
 * RevenueCat paketlerini ekrana hazir planlara cevirir.
 *
 * Yillik plan onerilir cunku kullanici icin gercekten ucuzdur; bu rozet
 * hesaplanan tasarruf POZITIF oldugunda gosterilir, pesinen degil.
 */
fun buildPlanOptions(packages: List<Package>): List<PlanOption> {
    val monthly = packages.firstOrNull { it.packageType == PackageType.MONTHLY }
    val annual = packages.firstOrNull { it.packageType == PackageType.ANNUAL }

    val monthlyAmountMicros = monthly?.product?.price?.amountMicros
    val annualAmountMicros = annual?.product?.price?.amountMicros

    // Tasarruf yalnizca IKI fiyat da ayni para biriminde bilindiginde
    // hesaplanir. Farkli para birimleri karsilastirilamaz.
    val savings: Int? = run {
        if (monthlyAmountMicros == null || annualAmountMicros == null) return@run null
        if (monthly?.product?.price?.currencyCode != annual?.product?.price?.currencyCode) return@run null
        val yearAtMonthlyRate = monthlyAmountMicros * 12
        if (yearAtMonthlyRate <= 0) return@run null
        val ratio = 1.0 - annualAmountMicros.toDouble() / yearAtMonthlyRate.toDouble()
        val percent = Math.round(ratio * 100).toInt()
        percent.takeIf { it > 0 }
    }

    return packages.mapNotNull { pkg ->
        when (pkg.packageType) {
            PackageType.MONTHLY -> PlanOption(
                rcPackage = pkg,
                titleRes = R.string.plan_monthly,
                priceText = pkg.product.price.formatted,
                perMonthText = null,
                savingsPercent = null,
                trialDays = pkg.freeTrialDays(),
                isRecommended = false,
            )

            PackageType.ANNUAL -> PlanOption(
                rcPackage = pkg,
                titleRes = R.string.plan_annual,
                priceText = pkg.product.price.formatted,
                perMonthText = pkg.formattedPerMonth(),
                savingsPercent = savings,
                trialDays = pkg.freeTrialDays(),
                isRecommended = savings != null,
            )

            // Haftalik/omur boyu gibi baska paketler tanimlanirsa
            // gosterilmez: bu urunun plan yapisi iki secenektir ve
            // beklenmedik bir paketi rastgele bir baslikla basmak
            // kullaniciyi yanilir.
            else -> null
        }
    }.sortedByDescending { it.isRecommended }
}

/**
 * Urunun ucretsiz deneme suresini GUN olarak okur.
 *
 * Deneme, Play Console'daki teklifin bir fazidir; SDK bunu ISO-8601
 * sure olarak verir (orn. "P3D"). Elle "3 gun" yazmak, panelde sure
 * degistiginde ekranin yalan soylemesine yol acardi.
 */
private fun Package.freeTrialDays(): Int? {
    val freePhase = product.defaultOption?.freePhase ?: return null
    val period = freePhase.billingPeriod ?: return null
    return when (period.unit) {
        com.revenuecat.purchases.models.Period.Unit.DAY -> period.value
        com.revenuecat.purchases.models.Period.Unit.WEEK -> period.value * 7
        com.revenuecat.purchases.models.Period.Unit.MONTH -> period.value * 30
        com.revenuecat.purchases.models.Period.Unit.YEAR -> period.value * 365
        else -> null
    }
}

/**
 * Yillik fiyatin aylik karsiligini, urunun KENDI para biriminde
 * bicimlendirir. Tutar Google'dan geldigi icin yalnizca 12'ye bolunur.
 */
private fun Package.formattedPerMonth(): String? {
    val micros = product.price.amountMicros
    if (micros <= 0) return null
    val currency = product.price.currencyCode
    val perMonth = micros / 12.0 / MICROS_PER_UNIT
    return runCatching {
        val format = java.text.NumberFormat.getCurrencyInstance()
        format.currency = java.util.Currency.getInstance(currency)
        format.format(perMonth)
    }.getOrNull()
}

private const val MICROS_PER_UNIT = 1_000_000.0

/**
 * Deneme suresini insan diline cevirir.
 *
 * @Composable: metin cihaz diline gore cozuluyor.
 */
@Composable
fun trialLabel(days: Int): String = when {
    days % 7 == 0 && days >= 7 -> stringResource(R.string.trial_weeks_free, days / 7)
    else -> stringResource(R.string.trial_days_free, days)
}
