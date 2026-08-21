// android/app/src/main/java/com/eva/app/ui/theme/ElectroEffects.kt
package com.eva.app.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Elektro-Atom temasının neon vurgu rengi. */
val NeonCyan = Color(0xFF00E5FF)

/** "Müsait" durumu için neon yeşil (harita pinleri, doluluk rozetleri). */
/**
 * Ikincil aksan: acik gok mavisi.
 *
 * Onceden neon yesildi. Palet tek bir kimlige indirildi -- iki farkli
 * aksan rengi (yesil + mavi), hangisinin "onemli" oldugunu belirsiz
 * birakiyordu.
 */
val ElectricSky = Color(0xFF38BDF8)

/**
 * Neon parıltı efekti.
 *
 * `shadow` kullanılıyor çünkü Compose'da gerçek bir "glow" primitifi yok;
 * ambientColor/spotColor'ı neon renge çekmek, koyu zeminde ışıma
 * izlenimi verir. Açık temada işe yaramazdı — bu tema koyu zemine
 * commit ettiği için güvenli.
 */
fun Modifier.glow(
    color: Color = NeonCyan,
    blurRadius: Dp = 16.dp,
    cornerRadius: Dp = 16.dp,
): Modifier = this.shadow(
    elevation = blurRadius,
    shape = RoundedCornerShape(cornerRadius),
    ambientColor = color,
    spotColor = color,
)

/**
 * Arka plandaki atom yörünge deseni.
 *
 * Çok düşük alfa ile çizilir: amaç dokunun hissedilmesi, okunabilirliğin
 * bozulmaması. İçerikle yarışan bir arka plan, sürüş sırasında bakılan
 * bir ekranda kabul edilemez.
 */
@Composable
fun AtomBackgroundPattern(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.fillMaxSize()) {
        val centerX = size.width / 2
        val centerY = size.height * 0.32f
        val pattern = NeonCyan.copy(alpha = 0.08f)

        // Yörüngeler
        drawArc(
            color = pattern, startAngle = 0f, sweepAngle = 360f, useCenter = false,
            topLeft = Offset(centerX - 320f, centerY - 160f),
            size = Size(640f, 320f), style = Stroke(1.5f),
        )
        drawArc(
            color = pattern, startAngle = 0f, sweepAngle = 360f, useCenter = false,
            topLeft = Offset(centerX - 160f, centerY - 320f),
            size = Size(320f, 640f), style = Stroke(1.5f),
        )
        drawArc(
            color = pattern, startAngle = 0f, sweepAngle = 360f, useCenter = false,
            topLeft = Offset(centerX - 260f, centerY - 260f),
            size = Size(520f, 520f), style = Stroke(1f),
        )

        // Çekirdek ve elektronlar
        drawCircle(pattern.copy(alpha = 0.18f), radius = 46f, center = Offset(centerX, centerY))
        drawCircle(NeonCyan.copy(alpha = 0.5f), radius = 5f, center = Offset(centerX + 260f, centerY))
        drawCircle(NeonCyan.copy(alpha = 0.5f), radius = 5f, center = Offset(centerX, centerY - 320f))
    }
}
