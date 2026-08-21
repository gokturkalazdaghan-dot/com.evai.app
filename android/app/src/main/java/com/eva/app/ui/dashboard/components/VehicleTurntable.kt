// android/app/src/main/java/com/eva/app/ui/dashboard/components/VehicleTurntable.kt
package com.eva.app.ui.dashboard.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.eva.app.R
import com.eva.app.ui.theme.ElectricSky
import com.eva.app.ui.theme.NeonCyan
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Bir tam tur icin gereken yatay suruklenme (piksel). */
private const val PIXELS_PER_REVOLUTION = 900f

/**
 * Araci sag/sol kaydirilarak incelenebilen bir tezgah uzerinde gosterir.
 *
 * NASIL DONUYOR
 * -------------
 * Arac vektorel cizim; yatay suruklenme `rotationY` acisina cevriliyor ve
 * `cameraDistance` ile gercek bir perspektif veriliyor. Zemindeki halka
 * ve golge acyla birlikte hareket ettigi icin hareket "kagit cevirme"
 * degil "tezgah donuyor" gibi okunuyor.
 *
 * FOTOGERCEKCI 360 ICIN
 * ---------------------
 * Gercek bir 360 gorunum, aracin 24-36 acidan render edilmis kare
 * dizisini gerektirir; boyle bir varlik setimiz yok ve olmayan bir seyi
 * varmis gibi gostermek yerine vektorel tezgah kullaniliyor. Kareler
 * hazir oldugunda [frameProvider] parametresine verilir ve cizim yerine
 * o kareler gosterilir -- geri kalan etkilesim aynen calisir.
 */
@Composable
fun VehicleTurntable(
    modifier: Modifier = Modifier,
    /** Kare dizisi hazir oldugunda: aci (0-360) -> cizilecek kare. */
    frameProvider: (@Composable (angleDegrees: Float) -> Unit)? = null,
) {
    var angle by remember { mutableFloatStateOf(INITIAL_ANGLE) }
    val description = stringResource(R.string.vehicle_turntable_description)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(16f / 10f)
            .semantics { contentDescription = description }
            .pointerInput(Unit) {
                detectHorizontalDragGestures { _, dragAmount ->
                    angle = (angle + dragAmount / PIXELS_PER_REVOLUTION * 360f)
                        // Aci sinirsiz buyumesin: kayan nokta hassasiyeti
                        // uzun kullanimda bozulur.
                        .mod(360f)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        // Zemin: acyla birlikte donen halkalar.
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawStage(angle)
        }

        if (frameProvider != null) {
            frameProvider(angle)
        } else {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        rotationY = angle
                        // Dusuk kamera mesafesi asiri bir perspektif verir;
                        // 14 dp arac boyutunda dogal duruyor.
                        cameraDistance = 14 * density
                    },
            ) {
                drawCar(angle)
            }
        }
    }
}

/** Baslangic acisi: tam yandan degil, hafif uc-ceyrek gorunum. */
private const val INITIAL_ANGLE = 340f

/** Donen platform: uc ic ice halka. */
private fun DrawScope.drawStage(angle: Float) {
    val center = Offset(size.width / 2, size.height * 0.72f)
    val baseRadius = size.minDimension * 0.42f

    listOf(1f to 0.30f, 0.78f to 0.20f, 0.56f to 0.12f).forEach { (scale, alpha) ->
        drawOval(
            color = NeonCyan.copy(alpha = alpha),
            topLeft = Offset(
                center.x - baseRadius * scale,
                center.y - baseRadius * scale * ELLIPSE_FLATTEN,
            ),
            size = Size(
                baseRadius * 2 * scale,
                baseRadius * 2 * scale * ELLIPSE_FLATTEN,
            ),
            style = Stroke(width = 2f),
        )
    }

    // Donusu gorunur kilan isaret: halkanin uzerinde acyla birlikte
    // dolasan bir parlak nokta. Bu olmadan simetrik bir araci dondururken
    // hareket edip etmedigi anlasilmiyordu.
    val radians = Math.toRadians(angle.toDouble())
    drawCircle(
        color = NeonCyan,
        radius = 5f,
        center = Offset(
            center.x + (baseRadius * cos(radians)).toFloat(),
            center.y + (baseRadius * ELLIPSE_FLATTEN * sin(radians)).toFloat(),
        ),
    )
}

/** Zemin halkalarinin yassilik orani (perspektif hissi). */
private const val ELLIPSE_FLATTEN = 0.26f

/**
 * Aracin yan silueti.
 *
 * Aci 90-270 arasindayken arac arkadan gorunur; bu araligi ayirt etmek
 * icin far/stop renkleri yer degistiriyor -- yoksa donus sirasinda on ve
 * arka ayni gorunurdu.
 */
private fun DrawScope.drawCar(angle: Float) {
    val w = size.width
    val h = size.height
    val bodyTop = h * 0.34f
    val bodyBottom = h * 0.66f

    val bodyBrush = Brush.verticalGradient(
        colors = listOf(Color(0xFFF2F6F9), Color(0xFF8FA3B3), Color(0xFF44515E)),
        startY = bodyTop,
        endY = bodyBottom,
    )

    val left = w * 0.10f
    val right = w * 0.90f

    // Govde
    val body = Path().apply {
        moveTo(left, bodyBottom)
        cubicTo(left, bodyTop + h * 0.10f, w * 0.22f, bodyTop + h * 0.04f, w * 0.32f, bodyTop + h * 0.02f)
        cubicTo(w * 0.42f, bodyTop - h * 0.10f, w * 0.62f, bodyTop - h * 0.10f, w * 0.72f, bodyTop + h * 0.02f)
        cubicTo(w * 0.82f, bodyTop + h * 0.05f, right, bodyTop + h * 0.12f, right, bodyBottom)
        close()
    }
    drawPath(body, bodyBrush)

    // Cam
    val glass = Path().apply {
        moveTo(w * 0.36f, bodyTop + h * 0.01f)
        cubicTo(w * 0.44f, bodyTop - h * 0.07f, w * 0.60f, bodyTop - h * 0.07f, w * 0.67f, bodyTop + h * 0.01f)
        lineTo(w * 0.64f, bodyTop + h * 0.06f)
        lineTo(w * 0.39f, bodyTop + h * 0.06f)
        close()
    }
    drawPath(glass, Brush.verticalGradient(listOf(ElectricSky.copy(alpha = .85f), Color(0xFF15323F))))

    // Tekerlekler
    val wheelRadius = h * 0.11f
    listOf(w * 0.27f, w * 0.73f).forEach { cx ->
        drawCircle(Color(0xFF1B242C), wheelRadius, Offset(cx, bodyBottom))
        drawCircle(Color(0xFF93A4B1), wheelRadius * 0.55f, Offset(cx, bodyBottom))
        drawCircle(Color(0xFF2A333C), wheelRadius * 0.2f, Offset(cx, bodyBottom))
    }

    // Far ve stop: aci arkadan gorunume gecince yer degistirir.
    val showingFront = angle < 90f || angle > 270f
    val headlightX = if (showingFront) right - w * 0.02f else left + w * 0.02f
    val taillightX = if (showingFront) left + w * 0.02f else right - w * 0.02f

    drawCircle(NeonCyan, h * 0.022f, Offset(headlightX, bodyTop + h * 0.14f))
    drawCircle(Color(0xFFFF3B30), h * 0.018f, Offset(taillightX, bodyTop + h * 0.14f))

    // Alt neon vurgu
    drawLine(
        color = NeonCyan.copy(alpha = 0.55f),
        start = Offset(left + w * 0.04f, bodyBottom + h * 0.03f),
        end = Offset(right - w * 0.04f, bodyBottom + h * 0.03f),
        strokeWidth = 3f,
        cap = StrokeCap.Round,
    )

    // Donus yonunu belli eden hafif govde parlamasi.
    val sheen = abs(sin(Math.toRadians(angle.toDouble()))).toFloat()
    drawPath(body, Color.White.copy(alpha = 0.10f * sheen))
}
