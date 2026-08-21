// android/app/src/main/java/com/eva/app/ui/dashboard/components/BatteryHealthCard.kt
package com.eva.app.ui.dashboard.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import com.eva.app.ui.theme.ElectricSky
import com.eva.app.ui.theme.NeonCyan
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eva.app.R

/**
 * Referans görseldeki "Battery Health %92 Excellent" halka grafiği.
 *
 * DÜRÜSTLÜK NOTU: Bu değer gerçek bir batarya sağlığı sensöründen
 * (BMS telemetrisi) GELMİYOR — projede henüz araç API entegrasyonu yok.
 * Şimdilik VehicleProfile'da sabit/manuel bir alan olarak tutuluyor ve
 * stringResource(R.string.battery_health_excellent) etiketi basit bir eşik mantığıyla (>=90 Excellent, >=70
 * Good, altı Attention) üretiliyor. Gerçek BMS entegrasyonu yapılana
 * kadar bu, kullanıcıya AÇIKÇA "tahmini" olarak sunulmalı — aksi halde
 * yanıltıcı olur.
 */
@Composable
fun BatteryHealthCard(healthPercent: Int, modifier: Modifier = Modifier) {
    val (label, color) = healthStatusFor(healthPercent)

    Card(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.battery_health), style = MaterialTheme.typography.labelLarge)

            Box(contentAlignment = Alignment.Center) {
                Canvas(modifier = Modifier.size(100.dp)) {
                    val strokeWidth = 10.dp.toPx()
                    drawArc(
                        color = color.copy(alpha = 0.15f),
                        startAngle = -90f,
                        sweepAngle = 360f,
                        useCenter = false,
                        style = Stroke(width = strokeWidth),
                        size = Size(size.width - strokeWidth, size.height - strokeWidth),
                        topLeft = androidx.compose.ui.geometry.Offset(strokeWidth / 2, strokeWidth / 2),
                    )
                    drawArc(
                        color = color,
                        startAngle = -90f,
                        sweepAngle = 360f * (healthPercent / 100f),
                        useCenter = false,
                        style = Stroke(width = strokeWidth, cap = androidx.compose.ui.graphics.StrokeCap.Round),
                        size = Size(size.width - strokeWidth, size.height - strokeWidth),
                        topLeft = androidx.compose.ui.geometry.Offset(strokeWidth / 2, strokeWidth / 2),
                    )
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "$healthPercent%",
                        fontSize = 22.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(label, style = MaterialTheme.typography.labelSmall, color = color)
                }
            }
        }
    }
}

/**
 * Saglik yuzdesini etiket + renge cevirir.
 *
 * @Composable olmak ZORUNDA: stringResource yalnizca composition icinden
 * cagrilabilir. Etiketler artik sabit Ingilizce degil, kaynaklardan gelir
 * (cihaz Turkce ise "Mukemmel/Iyi/Orta").
 */
@Composable
private fun healthStatusFor(percent: Int): Pair<String, Color> {
    return when {
        percent >= 90 -> stringResource(R.string.battery_health_excellent) to NeonCyan
        percent >= 70 -> stringResource(R.string.battery_health_good) to ElectricSky
        percent >= 50 -> stringResource(R.string.battery_health_fair) to Color(0xFFFF9800)
        else -> stringResource(R.string.battery_health_attention) to Color(0xFFF44336)
    }
}
