// android/app/src/main/java/com/eva/app/ui/vehicle/TelemetryStatusCard.kt
package com.eva.app.ui.vehicle

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.annotation.StringRes
import androidx.compose.ui.res.stringResource
import com.eva.app.R
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.eva.app.vehicle.telemetry.TelemetryConnection
import com.eva.app.vehicle.telemetry.TelemetrySource
import com.eva.app.vehicle.telemetry.VehicleTelemetry

/**
 * Şarj verisinin NEREDEN geldiğini gösterir.
 *
 * NEDEN GEREKLİ
 * -------------
 * "Aracından canlı" ile "senin elle girdiğin değer" aynı şey değildir ve
 * sürücü hangisine baktığını bilmelidir. Ekranda %42 yazarken bunun üç
 * saat önce elle girilmiş bir sayı olduğunu bilmeyen biri, olmayan bir
 * menzile güvenerek yola çıkabilir.
 */
@Composable
fun TelemetryStatusCard(
    connection: TelemetryConnection,
    telemetry: VehicleTelemetry?,
    modifier: Modifier = Modifier,
) {
    val presentation = presentationFor(connection, telemetry)

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (presentation.isLive) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            },
        ),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(
                presentation.icon,
                contentDescription = null,
                modifier = Modifier.size(28.dp),
                tint = if (presentation.isLive) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(presentation.titleRes),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (presentation.isLive) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    presentation.detailOverride ?: stringResource(presentation.detailRes),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (presentation.isLive) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }

            // Canlı okuma varsa yüzdeyi burada da göster: kullanıcı kartın
            // gerçekten veri aldığını görmeli.
            telemetry?.batteryPercent?.takeIf { telemetry.hasUsableBattery }?.let { percent ->
                Text(
                    "%$percent",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}

private data class TelemetryPresentation(
    val icon: ImageVector,
    @StringRes val titleRes: Int,
    @StringRes val detailRes: Int,
    val isLive: Boolean,
    /** Uretici hesabinda arac adi varsa detay yerine bu gosterilir. */
    val detailOverride: String? = null,
)

private fun presentationFor(
    connection: TelemetryConnection,
    telemetry: VehicleTelemetry?,
): TelemetryPresentation {
    // Bağlantı "kurulu" görünse bile okuma bayatladıysa CANLI DEĞİLDİR.
    // Eski bir değeri canlı etiketiyle göstermek, etiketi yalan yapar.
    val isFresh = telemetry != null && !telemetry.isStale()

    return when (connection) {
        is TelemetryConnection.Connected -> {
            if (!isFresh) {
                TelemetryPresentation(
                    icon = Icons.Filled.CloudOff,
                    titleRes = R.string.telemetry_waiting,
                    detailRes = R.string.telemetry_waiting_hint,
                    isLive = false,
                )
            } else {
                when (connection.source) {
                    TelemetrySource.ANDROID_AUTOMOTIVE -> TelemetryPresentation(
                        icon = Icons.Filled.DirectionsCar,
                        titleRes = R.string.telemetry_from_car,
                        detailRes = R.string.telemetry_from_car,
                        isLive = true,
                    )

                    TelemetrySource.OBD_DONGLE -> TelemetryPresentation(
                        icon = Icons.Filled.Bluetooth,
                        titleRes = R.string.telemetry_from_obd,
                        detailRes = R.string.telemetry_from_obd,
                        isLive = true,
                    )

                    TelemetrySource.OEM_CLOUD -> TelemetryPresentation(
                        icon = Icons.Filled.DirectionsCar,
                        titleRes = R.string.telemetry_from_cloud,
                        detailRes = R.string.telemetry_from_cloud,
                        isLive = true,
                        detailOverride = connection.vehicleLabel,
                    )

                    TelemetrySource.MANUAL -> manualPresentation()
                }
            }
        }

        is TelemetryConnection.Interrupted -> TelemetryPresentation(
            icon = Icons.Filled.CloudOff,
            titleRes = R.string.telemetry_interrupted,
            detailRes = R.string.telemetry_waiting_hint,
            isLive = false,
            detailOverride = connection.reason,
        )

        TelemetryConnection.NotConnected -> manualPresentation()
    }
}

private fun manualPresentation() = TelemetryPresentation(
    icon = Icons.Filled.EditNote,
    titleRes = R.string.telemetry_manual,
    detailRes = R.string.telemetry_manual_hint,
    isLive = false,
)
