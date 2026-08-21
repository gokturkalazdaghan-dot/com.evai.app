// android/app/src/main/java/com/eva/app/ui/dashboard/components/VehicleTurntable.kt
package com.eva.app.ui.dashboard.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.eva.app.R
import com.eva.app.ui.theme.NeonCyan
import kotlin.math.cos
import kotlin.math.sin

/** Bir tam tur icin gereken yatay suruklenme (piksel). */
private const val PIXELS_PER_REVOLUTION = 900f

/** Baslangic acisi: uc-ceyrek gorunum, tam yandan degil. */
private const val INITIAL_ANGLE = 35f

/** Kameranin yukaridan bakma acisi (derece). */
private const val CAMERA_PITCH = 16f

/** Perspektif icin kamera uzakligi (model birimi). */
private const val CAMERA_DISTANCE = 14f

/** Aracin govde rengi. */
private val CarRed = Color(0xFFE02020)

/** Cam rengi -- koyu, hafif mavi. */
private val CarGlass = Color(0xFF1B3A4A)

private val TireBlack = Color(0xFF15191E)
private val RimSilver = Color(0xFF9FB0BC)

/**
 * Araci sag/sola kaydirarak her acidan incelenebilen bir tezgah.
 *
 * NASIL CIZILIYOR
 * ---------------
 * Gercek bir 3B model (bkz. CarMesh) her karede donduruluyor,
 * perspektifle yansitiliyor ve yuzeyler derinlige gore sirali
 * ciziliyor (ressam algoritmasi). Her yuzeyin parlakligi kendi
 * normaline gore hesaplaniyor.
 *
 * Onceki surum tek bir yandan gorunum cizimini `rotationY` ile
 * donduruyordu; o yaklasim aracin onunu ve arkasini GOSTEREMEZ, 90
 * derecede cizim bir cizgiye iner cunku derinligi yoktur.
 */
@Composable
fun VehicleTurntable(modifier: Modifier = Modifier) {
    var angle by remember { mutableFloatStateOf(INITIAL_ANGLE) }
    val description = stringResource(R.string.vehicle_turntable_description)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(16f / 10f)
            .semantics { contentDescription = description }
            .pointerInput(Unit) {
                detectHorizontalDragGestures { _, dragAmount ->
                    // Aci sinirsiz buyumesin: uzun kullanimda kayan
                    // nokta hassasiyeti bozulur.
                    angle = (angle + dragAmount / PIXELS_PER_REVOLUTION * 360f).mod(360f)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2, size.height * 0.56f)
            val scale = size.minDimension * 0.20f

            drawStage(center, scale, angle)
            drawCar(center, scale, angle)
        }
    }
}

/** Zemindeki donen platform halkalari. */
private fun DrawScope.drawStage(center: Offset, scale: Float, angleDegrees: Float) {
    val groundY = center.y + scale * 1.05f

    listOf(2.9f to 0.26f, 2.3f to 0.18f, 1.7f to 0.11f).forEach { (radius, alpha) ->
        val rx = radius * scale
        val ry = rx * 0.28f
        drawOval(
            color = NeonCyan.copy(alpha = alpha),
            topLeft = Offset(center.x - rx, groundY - ry),
            size = Size(rx * 2, ry * 2),
            style = Stroke(width = 2f),
        )
    }

    // Donusu gorunur kilan isaret: simetrik bir araci dondururken
    // hareket edip etmedigi aksi halde anlasilmiyor.
    val radians = Math.toRadians(angleDegrees.toDouble())
    val rx = 2.9f * scale
    drawCircle(
        color = NeonCyan,
        radius = 5f,
        center = Offset(
            center.x + (rx * cos(radians)).toFloat(),
            groundY + (rx * 0.28f * sin(radians)).toFloat(),
        ),
    )
}

private fun DrawScope.drawCar(center: Offset, scale: Float, angleDegrees: Float) {
    val yaw = Math.toRadians(angleDegrees.toDouble()).toFloat()
    val pitch = Math.toRadians(CAMERA_PITCH.toDouble()).toFloat()

    fun transform(v: Vec3): Vec3 = v.rotateY(yaw).rotateX(pitch)

    val transformed = CarMesh.vertices.map(::transform)
    val projected = transformed.map { it.project(center, scale, CAMERA_DISTANCE) }

    // Golge: araci zemine baglar. Olmadan havada asili duruyor gibi.
    drawCarShadow(center, scale)

    // Tekerlekler govdeden ONCE cizilir; govde onlerini kapatir ve
    // sadece disarida kalan kismi gorunur.
    val wheels = CarMesh.wheelCenters
        .map { transform(it) }
        .sortedBy { it.z }
    wheels.forEach { drawWheel(it, center, scale) }

    // RESSAM ALGORITMASI: yuzeyler UZAKTAN YAKINA cizilir, boylece
    // yakindakiler uzaktakileri dogal olarak kapatir. Z-tamponu
    // olmadan derinligi dogru gostermenin en basit yolu.
    CarMesh.faces(CarRed, CarGlass)
        .map { face ->
            val pts = face.indices.map { transformed[it] }
            val depth = pts.map { it.z }.average().toFloat()
            Triple(face, pts, depth)
        }
        .sortedBy { it.third }
        .forEach { (face, pts, _) ->
            val normal = faceNormal(pts[0], pts[1], pts[2])

            // Arkaya bakan yuzeyleri atla: hem gereksiz cizim hem de
            // yari saydam kenarlarda hayalet gorunumler yaratir.
            if (normal.z <= 0f) return@forEach

            val path = Path().apply {
                val first = projected[face.indices[0]]
                moveTo(first.x, first.y)
                face.indices.drop(1).forEach {
                    val p = projected[it]
                    lineTo(p.x, p.y)
                }
                close()
            }
            drawPath(path, shade(normal, face.baseColor))
        }

    drawLights(transform(CarMesh.headlights[0]), center, scale, NeonCyan, 4.5f)
    drawLights(transform(CarMesh.headlights[1]), center, scale, NeonCyan, 4.5f)
    drawLights(transform(CarMesh.taillights[0]), center, scale, Color(0xFFFF2D2D), 4f)
    drawLights(transform(CarMesh.taillights[1]), center, scale, Color(0xFFFF2D2D), 4f)
}

private fun DrawScope.drawCarShadow(center: Offset, scale: Float) {
    val rx = 2.2f * scale
    val ry = rx * 0.26f
    drawOval(
        color = Color.Black.copy(alpha = 0.35f),
        topLeft = Offset(center.x - rx, center.y + scale * 0.92f - ry),
        size = Size(rx * 2, ry * 2),
    )
}

/**
 * Tekerlek: yuzeye dik bir disk.
 *
 * Silindir olarak modellemek daha dogru olurdu ama bu olcekte fark
 * edilmiyor; disk hem ucuz hem yeterli.
 */
private fun DrawScope.drawWheel(centerVec: Vec3, center: Offset, scale: Float) {
    val p = centerVec.project(center, scale, CAMERA_DISTANCE)

    // Uzaktaki tekerlek daha kucuk gorunmeli.
    val depthFactor = CAMERA_DISTANCE / (CAMERA_DISTANCE - centerVec.z).coerceAtLeast(0.1f)
    val r = CarMesh.WHEEL_RADIUS * scale * depthFactor

    drawCircle(TireBlack, r, p)
    drawCircle(RimSilver.copy(alpha = 0.9f), r * 0.52f, p)
    drawCircle(TireBlack.copy(alpha = 0.85f), r * 0.18f, p)
}

private fun DrawScope.drawLights(
    v: Vec3,
    center: Offset,
    scale: Float,
    color: Color,
    radius: Float,
) {
    // Aracin arkasinda kalan isik gorunmemeli.
    if (v.z <= 0f) return

    val p = v.project(center, scale, CAMERA_DISTANCE)
    drawCircle(color.copy(alpha = 0.35f), radius * 2.4f, p)
    drawCircle(color, radius, p)
}
