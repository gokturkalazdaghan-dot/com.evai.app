// android/app/src/main/java/com/eva/app/ui/stations/OfflineBanner.kt
package com.eva.app.ui.stations

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.eva.app.R
import androidx.compose.ui.unit.dp
import java.util.concurrent.TimeUnit

/**
 * Verinin çevrimdışı önbellekten geldiğini söyler.
 *
 * NEDEN ŞART
 * ----------
 * Eski fiyatları göstermek doğru karar — hiçbir fiyattan iyidir. Ama
 * kullanıcı baktığı sayının ne kadar eski olduğunu BİLMEZSE, saatler
 * önceki bir tarifeye göre yola çıkabilir. Veriyi göstermek ile onu
 * güncelmiş gibi sunmak arasındaki fark bu banner.
 */
@Composable
fun OfflineBanner(
    fetchedAtEpochMs: Long,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ageText = formatAge(System.currentTimeMillis() - fetchedAtEpochMs)

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                Icons.Filled.CloudOff,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.offline_prices, ageText),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onRetry) {
                Text(
                    stringResource(R.string.action_refresh),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

/**
 * Yaşı insan diline çevirir.
 *
 * Kesin dakika/saniye vermek yerine kaba aralık: kullanıcının kararı
 * "bu fiyat hâlâ geçerli mi" sorusudur, saniye hassasiyeti gerekmez.
 *
 * @Composable: metinler cihaz diline göre çözülüyor.
 */
@Composable
private fun formatAge(ageMs: Long): String {
    val minutes = TimeUnit.MILLISECONDS.toMinutes(ageMs)
    val hours = TimeUnit.MILLISECONDS.toHours(ageMs)
    val days = TimeUnit.MILLISECONDS.toDays(ageMs)

    return when {
        minutes < 2 -> stringResource(R.string.age_just_now)
        minutes < 60 -> stringResource(R.string.age_minutes, minutes.toInt())
        hours < 24 -> stringResource(R.string.age_hours, hours.toInt())
        days < 2 -> stringResource(R.string.age_yesterday)
        else -> stringResource(R.string.age_days, days.toInt())
    }
}
