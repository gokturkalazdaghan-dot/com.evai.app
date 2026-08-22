// android/app/src/main/java/com/eva/app/ui/hud/MatrixRain.kt
package com.eva.app.ui.hud

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import kotlin.random.Random

/** Ekranda dusen tek bir glif sutunu. */
private data class RainColumn(
    val xFraction: Float,
    /** Bir dongude kac ekran boyu dustugu. */
    val speed: Float,
    /** Baslangic kaymasi; sutunlar ayni anda dusmesin diye. */
    val phase: Float,
    val glyphs: List<Char>,
    val alpha: Float,
)

/**
 * "Matrix" dijital yagmuru.
 *
 * RENK NEDEN YESIL DEGIL
 * ----------------------
 * Matrix denince yesil akla gelir, ama bu uygulamanin paletinden yesil
 * BILINCLI olarak cikarilmis, yerine elektrik mavisi/camgobegi
 * gelmisti. Yesil bir katman uygulamanin geri kalaniyla catisirdi.
 * Efektin kimligi zaten rengi degil AKISI: dusen sutunlar, basi parlak
 * kuyrugu sonen izler. Ayni etki kendi paletimizde uygulaniyor.
 *
 * PERFORMANS
 * ----------
 * Sutunlar bir kez uretilip saklaniyor; her karede yalnizca dikey kayma
 * hesaplaniyor. Paint nesnesi de bir kez olusturuluyor -- her glif icin
 * yeni Paint ayirmak kare basina yuzlerce nesne demekti.
 */
@Composable
fun MatrixRain(
    modifier: Modifier = Modifier,
    color: Color,
    columnCount: Int = 22,
    glyphSizePx: Float = 26f,
) {
    // Sabit tohum: ekran her yeniden olustugunda ayni desen. Rastgele
    // tohum, donme/tema degisiminde yagmurun sicramasina yol acardi.
    val columns = remember(columnCount) {
        val random = Random(seed = 42)
        List(columnCount) { index ->
            RainColumn(
                xFraction = (index + 0.5f) / columnCount,
                speed = 0.8f + random.nextFloat() * 1.6f,
                phase = random.nextFloat(),
                glyphs = List(random.nextInt(8, 18)) { GLYPHS[random.nextInt(GLYPHS.size)] },
                alpha = 0.14f + random.nextFloat() * 0.24f,
            )
        }
    }

    val paint = remember {
        Paint().apply {
            isAntiAlias = true
            typeface = Typeface.MONOSPACE
        }
    }

    val progress by rememberInfiniteTransition(label = "rain").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(CYCLE_MS, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "fall",
    )

    Canvas(modifier = modifier) {
        paint.textSize = glyphSizePx
        columns.forEach { column ->
            drawColumn(column, progress, color, glyphSizePx, paint)
        }
    }
}

private fun DrawScope.drawColumn(
    column: RainColumn,
    progress: Float,
    color: Color,
    glyphSizePx: Float,
    paint: Paint,
) {
    val trailHeight = column.glyphs.size * glyphSizePx
    // mod 1: ekranin altindan cikan sutun ustten geri girer.
    val fall = (progress * column.speed + column.phase).mod(1f)
    val headY = fall * (size.height + trailHeight) - trailHeight

    val x = column.xFraction * size.width
    val canvas = drawContext.canvas.nativeCanvas

    column.glyphs.forEachIndexed { index, glyph ->
        val y = headY + index * glyphSizePx
        if (y < -glyphSizePx || y > size.height + glyphSizePx) return@forEachIndexed

        // Bas parlak, kuyruk soner: izin YONUNU okunur kilan sey bu.
        val tailRatio = 1f - index.toFloat() / column.glyphs.size
        paint.color = color.copy(alpha = (column.alpha * tailRatio).coerceIn(0f, 1f)).toArgb()

        canvas.drawText(glyph.toString(), x, y, paint)
    }
}

/**
 * Glif kumesi.
 *
 * Katakana DEGIL: o yazi tipi bulunmayan cihazlarda her glif bos kare
 * cizilir ve efekt "bozuk" gorunur. Rakam + buyuk harf + birkac sembol
 * her cihazda ayni gorunur ve teknik his icin yeterli.
 */
private val GLYPHS: List<Char> =
    (('0'..'9') + ('A'..'Z')).toList() + listOf('/', '\\', '<', '>', '=', '+', '*', '#', '%')

private const val CYCLE_MS = 9_000
