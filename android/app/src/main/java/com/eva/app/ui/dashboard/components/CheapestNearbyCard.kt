// android/app/src/main/java/com/eva/app/ui/dashboard/components/CheapestNearbyCard.kt
package com.eva.app.ui.dashboard.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eva.app.R
import com.eva.app.ui.stations.StationDto
import com.eva.app.ui.stations.UnitSystem
import com.eva.app.ui.stations.formatPricePerKwh
import com.eva.app.ui.stations.formatDistance

/**
 * Referans görseldeki "Cheapest Nearby $0.26/kWh" kartı. GERÇEK veriye
 * dayanır — StationsViewModel'in son yüklediği istasyon listesinden
 * fiyatı BİLİNEN (pricePerKwh != null) istasyonlar arasından en ucuzu
 * seçilir. Hiçbir istasyonun fiyatı bilinmiyorsa, kart "fiyat bekleniyor"
 * durumunu gösterir — asla uydurma bir rakam göstermez.
 */
@Composable
fun CheapestNearbyCard(
    cheapestStation: StationDto?,
    unitSystem: UnitSystem,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(stringResource(R.string.cheapest_nearby), style = MaterialTheme.typography.labelLarge)

            if (cheapestStation == null || cheapestStation.pricePerKwh == null) {
                Text(
                    "Fiyat bekleniyor",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    text = formatPricePerKwh(
                        pricePerKwh = cheapestStation.pricePerKwh,
                        currencyCode = cheapestStation.currency,
                    ),
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = stringResource(
                        R.string.distance_away,
                        formatDistance(cheapestStation.distanceMeters, unitSystem),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = cheapestStation.name,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}
