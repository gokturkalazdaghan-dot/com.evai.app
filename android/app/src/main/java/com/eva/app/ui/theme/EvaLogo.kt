// android/app/src/main/java/com/eva/app/ui/theme/EvaLogo.kt
package com.eva.app.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Logonun sari halkasi. */
val EvaRingYellow = Color(0xFFFFD60A)

private const val RING_STROKE_FRACTION = 0.075f

/** Halkanin ic yaricapi, kutu boyutunun orani olarak. */
private const val RING_RADIUS_FRACTION = 0.42f

/**
 * EVA logosu: sari halka icinde simsek + enerji isaretleri.
 *
 * Neden Canvas: logo tek bir vektor cizim ve iki renk disinda hicbir
 * varlik gerektirmiyor. PNG kullanmak her yogunluk icin ayri dosya
 * demekti; VectorDrawable ise Compose tarafinda tema rengiyle
 * eslesmiyordu (halka rengi sabit kalirdi).
 */
@Composable
fun EvaLogo(
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
) {
    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(size)) {
            val side = kotlin.math.min(this.size.width, this.size.height)
            val center = Offset(this.size.width / 2, this.size.height / 2)
            val radius = side * RING_RADIUS_FRACTION
            val strokeWidth = side * RING_STROKE_FRACTION

            // Sari halka
            drawCircle(
                color = EvaRingYellow,
                radius = radius,
                center = center,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
            )

            // Simsek: halkanin icinde, neon gradyanli.
            val boltHeight = radius * 1.15f
            val boltWidth = radius * 0.62f
            val boltPath = Path().apply {
                moveTo(center.x + boltWidth * 0.18f, center.y - boltHeight / 2)
                lineTo(center.x - boltWidth * 0.42f, center.y + boltHeight * 0.10f)
                lineTo(center.x - boltWidth * 0.02f, center.y + boltHeight * 0.10f)
                lineTo(center.x - boltWidth * 0.18f, center.y + boltHeight / 2)
                lineTo(center.x + boltWidth * 0.44f, center.y - boltHeight * 0.12f)
                lineTo(center.x + boltWidth * 0.02f, center.y - boltHeight * 0.12f)
                close()
            }
            drawPath(
                path = boltPath,
                brush = Brush.verticalGradient(
                    colors = listOf(NeonCyan, ElectricSky),
                    startY = center.y - boltHeight / 2,
                    endY = center.y + boltHeight / 2,
                ),
            )

            // Enerji isaretleri: halkanin iki yaninda kisa cizgiler.
            // Onceden ses dalgasiydi ve sesli asistani anlatiyordu;
            // asistan urunden cikinca anlamsiz bir sus olarak kalmisti.
            val waveGap = radius * 0.38f
            val waveHeights = listOf(0.30f, 0.52f, 0.30f)
            waveHeights.forEachIndexed { index, heightFraction ->
                val offsetX = radius + waveGap * (index + 1)
                val half = radius * heightFraction
                listOf(center.x - offsetX, center.x + offsetX).forEach { x ->
                    drawLine(
                        color = NeonCyan.copy(alpha = 0.85f - index * 0.22f),
                        start = Offset(x, center.y - half),
                        end = Offset(x, center.y + half),
                        strokeWidth = strokeWidth * 0.7f,
                        cap = StrokeCap.Round,
                    )
                }
            }
        }
    }
}
