// android/app/src/main/java/com/eva/app/ui/subscription/SubscriptionScreen.kt
package com.eva.app.ui.subscription

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import com.eva.app.R
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.eva.app.commerce.SubscriptionState
import com.revenuecat.purchases.Package

/**
 * Premium abonelik ekranı.
 *
 * Bu ekran daha önce YOKTU: navigation grafiğinde "subscription" adında boş
 * bir route iskeleti vardı ve içine hiçbir composable bağlanmamıştı. Yani
 * RevenueCat entegrasyonu (SubscriptionRepository, RevenueCatManager,
 * webhook'lar) baştan sona yazılmış olmasına rağmen kullanıcının satın alma
 * yapabileceği bir arayüz bulunmuyordu.
 *
 * Google Play politikası gereği abonelik satan bir uygulamada; fiyat, süre,
 * yenilenme davranışı ve geri yükleme (restore) seçeneği kullanıcıya AÇIKÇA
 * gösterilmek zorundadır — bu ekran bu üç şartı da karşılar.
 */
@Composable
fun SubscriptionScreen(
    viewModel: SubscriptionViewModel,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Box(modifier = modifier.fillMaxSize()) {
        when (val state = uiState) {
            is SubscriptionScreenState.Loading -> LoadingState()

            is SubscriptionScreenState.Unavailable -> MessageState(
                title = stringResource(R.string.subscription_unavailable),
                body = state.reason,
            )

            is SubscriptionScreenState.Loaded -> LoadedState(
                state = state,
                onPurchase = { pkg ->
                    (context as? Activity)?.let { viewModel.onPurchaseClicked(it, pkg) }
                },
                onRestore = viewModel::onRestoreClicked,
                onDismissError = viewModel::dismissError,
            )
        }
    }
}

@Composable
private fun LoadingState() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun MessageState(title: String, body: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LoadedState(
    state: SubscriptionScreenState.Loaded,
    onPurchase: (Package) -> Unit,
    onRestore: () -> Unit,
    onDismissError: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
    ) {
        // Baslik yalnizca abonelik AKTIFKEN; paywall kendi hero'sunu
        // tasiyor ve iki baslik ust uste biniyordu.
        if (state.subscriptionState.isPremiumActive) {
            PremiumHeader(isPremiumActive = true)
            Spacer(Modifier.height(24.dp))
        }

        if (state.subscriptionState.isPremiumActive) {
            ActiveSubscriptionCard(state.subscriptionState)
        } else {
            val plans = remember(state.availablePackages) {
                buildPlanOptions(state.availablePackages)
            }

            // Secim, plan listesi degistiginde sifirlanmali; aksi halde
            // artik teklif edilmeyen bir paketi satin almaya calisirdik.
            var selectedIdentifier by remember(plans) {
                mutableStateOf(plans.firstOrNull()?.rcPackage?.identifier)
            }
            val selectedPlan = plans.firstOrNull { it.rcPackage.identifier == selectedIdentifier }

            if (plans.isEmpty()) {
                // Paket yoksa SAHTE bir fiyat gosterilmez. Genellikle
                // Play Console urunleri henuz yayinlanmamistir.
                Text(
                    "Şu anda satın alınabilir bir paket bulunmuyor.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                PaywallContent(
                    plans = plans,
                    selectedPlan = selectedPlan,
                    isPurchaseInProgress = state.isPurchaseInProgress,
                    onPlanSelected = { selectedIdentifier = it.rcPackage.identifier },
                    onPurchase = { onPurchase(it.rcPackage) },
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        // Google Play, daha önce satın alım yapmış kullanıcıların aboneliğini
        // geri yükleyebilmesini ZORUNLU tutar.
        TextButton(
            onClick = onRestore,
            enabled = !state.isPurchaseInProgress,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.action_restore_purchases))
        }

        state.errorMessage?.let { message ->
            Spacer(Modifier.height(12.dp))
            ErrorCard(message = message, onDismiss = onDismissError)
        }

        Spacer(Modifier.height(16.dp))
        Text(
            "Abonelik, iptal edilmediği sürece dönem sonunda otomatik yenilenir. " +
                "Yönetim ve iptal işlemleri Google Play hesabınızdan yapılır.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PremiumHeader(isPremiumActive: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            Icons.Filled.WorkspacePremium,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(40.dp),
        )
        Spacer(Modifier.size(12.dp))
        Column {
            Text(
                if (isPremiumActive) "Eva Premium aktif" else "Eva Premium",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                if (isPremiumActive) {
                    "Tüm özellikler açık."
                } else {
                    "Daha akıllı şarj, daha az maliyet."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ActiveSubscriptionCard(subscriptionState: SubscriptionState) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    "Aboneliğiniz aktif",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }

            subscriptionState.expirationDate?.let { expiry ->
                Spacer(Modifier.height(8.dp))
                Text(
                    if (subscriptionState.willAutoRenew) {
                        "Sonraki yenilenme: $expiry"
                    } else {
                        "Bitiş tarihi: $expiry"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }

            if (subscriptionState.isSandbox) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Test (sandbox) aboneliği",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}

@Composable
private fun ErrorCard(message: String, onDismiss: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onDismiss) {
                Text("Tamam")
            }
        }
    }
}
