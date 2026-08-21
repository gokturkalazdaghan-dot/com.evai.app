// android/app/src/main/java/com/eva/app/ui/stations/StationFilterSheet.kt
package com.eva.app.ui.stations

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import com.eva.app.R
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Kullanıcının istasyon aramasını daraltmasını sağlayan filtreler.
 *
 * Alanlar Gateway'in `/v1/stations/nearby` sözleşmesindeki parametrelere
 * birebir karşılık gelir; istemci tarafında ek bir eleme YAPILMAZ, sunucu
 * zaten filtreli sonuç döner.
 */
data class StationFilters(
    val minPowerKw: Double? = null,
    val radiusMeters: Int = 15_000,
    /** Yalnızca canlı/son bilinen fiyatı olan istasyonlar gösterilsin mi? */
    val onlyWithPrice: Boolean = false,
) {
    val isActive: Boolean
        get() = minPowerKw != null || radiusMeters != 15_000 || onlyWithPrice
}

private val POWER_OPTIONS = listOf<Double?>(null, 50.0, 150.0, 250.0)
private val RADIUS_OPTIONS = listOf(5_000, 15_000, 50_000)

/**
 * Filtre alt sayfası.
 *
 * Bu ekran daha önce YOKTU: sağ üstteki "Filtrele" düğmesinin `onClick`'i
 * tamamen boştu, dokunmak hiçbir şey yapmıyordu.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StationFilterSheet(
    current: StationFilters,
    onDismiss: () -> Unit,
    onApply: (StationFilters) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var draft by remember { mutableStateOf(current) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
            Text(
                "Filtreler",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )

            Spacer(Modifier.height(24.dp))

            Text(
                stringResource(R.string.filter_min_power),
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                POWER_OPTIONS.forEach { power ->
                    FilterChip(
                        selected = draft.minPowerKw == power,
                        onClick = { draft = draft.copy(minPowerKw = power) },
                        label = { Text(power?.let { "${it.toInt()} kW+" } ?: "Hepsi") },
                    )
                }
            }

            Spacer(Modifier.height(24.dp))

            Text(
                stringResource(R.string.filter_radius),
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RADIUS_OPTIONS.forEach { radius ->
                    FilterChip(
                        selected = draft.radiusMeters == radius,
                        onClick = { draft = draft.copy(radiusMeters = radius) },
                        label = { Text("${radius / 1000} km") },
                    )
                }
            }

            Spacer(Modifier.height(24.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                stringResource(R.string.filter_price_known),
                style = MaterialTheme.typography.titleSmall,
            )
                    Text(
                        "Fiyat bilgisi olmayan istasyonları gizler.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = draft.onlyWithPrice,
                    onCheckedChange = { draft = draft.copy(onlyWithPrice = it) },
                )
            }

            Spacer(Modifier.height(24.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TextButton(
                    onClick = { draft = StationFilters() },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.action_reset))
                }
                Button(
                    onClick = { onApply(draft) },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Uygula")
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}
