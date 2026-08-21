// android/app/src/main/java/com/eva/app/ui/subscription/PaywallContent.kt
package com.eva.app.ui.subscription

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eva.app.R
import com.eva.app.ui.theme.EvaLogo

/* ---------------------------------------------------------------------
 * Paywall paleti
 *
 * Elektrik mavisi (#00F0FF) yalnizca EYLEM icin ayrildi: secili plan
 * kenarligi ve ana buton. Her yere serpistirilseydi hicbir sey one
 * cikmazdi.
 * ------------------------------------------------------------------- */
private val PaywallGradientTop = Color(0xFF0B0F19)
private val PaywallGradientBottom = Color(0xFF020617)
private val ActionCyan = Color(0xFF00F0FF)
private val AccentBlue = Color(0xFF38BDF8)
private val CardSelected = Color(0xFF0B2545)
private val CardIdle = Color(0xFF111827)
private val BorderIdle = Color(0xFF1E293B)
private val TextPrimary = Color(0xFFF1F5F9)
private val TextSecondary = Color(0xFF94A3B8)
private val CheckBadge = Color(0xFF0369A1)

/**
 * Premium'un gercekten actigi seyler.
 *
 * NOT: "sinirsiz sesli asistan" maddesi KALDIRILDI -- sesli asistan
 * urunden cikarildi ve olmayan bir ozelligi satmak yaniltici olurdu.
 */
private val PREMIUM_BENEFITS = listOf(
    R.string.benefit_live_prices,
    R.string.benefit_price_alerts,
    R.string.benefit_route_planning,
    R.string.benefit_no_ads,
)

/**
 * Odeme duvari icerigi.
 *
 * FIYATLAR BU DOSYADA SABIT DEGIL
 * -------------------------------
 * Tutar, indirim orani ve deneme suresi `PlanOption` uzerinden Google
 * Play'in dondurdugu GERCEK degerlerden gelir (bkz. SubscriptionPlans.kt).
 * Ekrana "₺499,99" ya da "%40 indirim" yazmak iki sorun yaratirdi:
 * kullanicinin hesabindan baska bir tutar cekilirdi (Play politikasina
 * aykiri) ve fiyat panelde degistiginde ekran yalan soylemeye baslardi.
 */
@Composable
fun PaywallContent(
    plans: List<PlanOption>,
    selectedPlan: PlanOption?,
    isPurchaseInProgress: Boolean,
    onPlanSelected: (PlanOption) -> Unit,
    onPurchase: (PlanOption) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(listOf(PaywallGradientTop, PaywallGradientBottom)),
                RoundedCornerShape(20.dp),
            )
            .padding(20.dp),
    ) {
        PaywallHero()

        Spacer(Modifier.height(22.dp))

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            PREMIUM_BENEFITS.forEach { BenefitRow(stringResource(it)) }
        }

        Spacer(Modifier.height(22.dp))

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            plans.forEach { plan ->
                PlanCard(
                    plan = plan,
                    isSelected = plan.rcPackage.identifier == selectedPlan?.rcPackage?.identifier,
                    onClick = { onPlanSelected(plan) },
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        val trialDays = selectedPlan?.trialDays

        Button(
            onClick = { selectedPlan?.let(onPurchase) },
            enabled = selectedPlan != null && !isPurchaseInProgress,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = ActionCyan,
                contentColor = Color.Black,
                disabledContainerColor = ActionCyan.copy(alpha = 0.3f),
            ),
        ) {
            if (isPurchaseInProgress) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    strokeWidth = 2.dp,
                    color = Color.Black,
                )
            } else {
                Text(
                    // Deneme VARSA one cikarilir. Play Console'da teklif
                    // tanimli degilken "ücretsiz dene" yazmak, kullanicidan
                    // hemen para cekilmesi demek olurdu.
                    text = if (trialDays != null) {
                        stringResource(R.string.paywall_cta_trial, trialDays)
                    } else {
                        stringResource(R.string.paywall_cta_subscribe)
                    },
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        if (selectedPlan != null) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = if (trialDays != null) {
                    stringResource(
                        R.string.paywall_terms_trial,
                        trialDays,
                        selectedPlan.priceText,
                    )
                } else {
                    stringResource(R.string.paywall_terms_plain, selectedPlan.priceText)
                },
                color = TextSecondary,
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun PaywallHero() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth(),
    ) {
        EvaLogo(size = 60.dp)
        Spacer(Modifier.height(12.dp))
        Text(
            "EVA AI Premium",
            color = TextPrimary,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.paywall_tagline),
            color = AccentBlue,
            fontSize = 14.sp,
        )
    }
}

@Composable
private fun BenefitRow(text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .background(CheckBadge, RoundedCornerShape(11.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text("✓", color = ActionCyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        Text(text, color = Color(0xFFE2E8F0), fontSize = 14.sp)
    }
}

@Composable
private fun PlanCard(
    plan: PlanOption,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 2.dp,
                color = if (isSelected) ActionCyan else BorderIdle,
                shape = RoundedCornerShape(16.dp),
            )
            .background(
                color = if (isSelected) CardSelected else CardIdle,
                shape = RoundedCornerShape(16.dp),
            )
            .clickable(onClick = onClick)
            .padding(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(plan.titleRes),
                        color = TextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    // Indirim rozeti YALNIZCA gercek fiyatlardan
                    // hesaplanabildiginde basilir -- "%40" gibi sabit bir
                    // oran, fiyat degistiginde yanlisa donerdi.
                    plan.savingsPercent?.let { percent ->
                        Spacer(Modifier.size(8.dp))
                        SavingsBadge(percent)
                    }
                }

                Spacer(Modifier.height(4.dp))

                Text(
                    text = plan.perMonthText
                        ?.let { stringResource(R.string.plan_per_month, it) }
                        ?: stringResource(R.string.plan_cancel_any_time),
                    color = TextSecondary,
                    fontSize = 12.sp,
                )

                plan.trialDays?.let { days ->
                    Spacer(Modifier.height(4.dp))
                    Text(
                        trialLabel(days),
                        color = AccentBlue,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            Text(
                plan.priceText,
                color = ActionCyan,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun SavingsBadge(percent: Int) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = ActionCyan,
    ) {
        Text(
            stringResource(R.string.plan_savings_badge, percent),
            color = Color.Black,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}
