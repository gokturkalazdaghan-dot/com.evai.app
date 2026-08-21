// android/app/src/main/java/com/eva/app/ui/dashboard/components/EvStatusBar.kt
package com.eva.app.ui.dashboard.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.eva.app.R
import com.eva.app.ui.stations.unitSystemFor
import com.eva.app.vehicle.VehicleProfile
import com.eva.app.vehicle.estimateRangeKm
import com.eva.app.vehicle.formatRange

/**
 * Mevcut şarj ve ona göre ANLIK hesaplanan menzil.
 *
 * Menzil artık onboarding'de girilmiş sabit bir değer değil: şarj yüzdesi
 * her değiştiğinde (telemetriden ya da elle) yeniden hesaplanır
 * (bkz. RangeEstimator.kt).
 *
 * @param livePercent telemetriden gelen anlık şarj; null ise araç
 *        profilindeki son bilinen değer kullanılır.
 */
@Composable
fun EvStatusBar(
    vehicle: VehicleProfile,
    modifier: Modifier = Modifier,
    livePercent: Int? = null,
) {
    // Canlı okuma varsa o esas alınır; yoksa profildeki son değer.
    val chargePercent = livePercent ?: vehicle.currentChargePercent

    val rangeText = formatRange(
        estimateRangeKm(chargePercent, vehicle.batteryCapacityKwh),
        unitSystemFor(),
    )
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
            ) {
                Icon(
                    imageVector = Icons.Filled.DirectionsCar,
                    contentDescription = vehicle.displayName,
                    modifier = Modifier.padding(16.dp).height(32.dp),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(stringResource(R.string.current_charge), style = MaterialTheme.typography.labelMedium)
                    Text(
                        rangeText,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    "$chargePercent%",
                    style = MaterialTheme.typography.headlineSmall,
                )
                LinearProgressIndicator(
                    progress = { chargePercent / 100f },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
            }
        }
    }
}
