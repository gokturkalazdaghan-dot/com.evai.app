// android/app/src/main/java/com/eva/app/ui/dashboard/components/VehicleTurntable.kt
package com.eva.app.ui.dashboard.components

import android.provider.Settings
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.eva.app.R
import com.eva.app.ui.theme.NeonCyan
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Bir tam tur suresi.
 *
 * Istenen hiz dakikada 6 tur: 60 sn / 6 tur = 10 sn.
 */
private const val ROTATION_PERIOD_MS = 10_000

/** Bir tam tur icin gereken yatay suruklenme (piksel). */
private const val PIXELS_PER_REVOLUTION = 900f

/** Kameranin yukaridan bakma acisi (derece). */
private const val CAMERA_PITCH = 26f

/** Perspektif icin kamera uzakligi (model birimi). */
private const val CAMERA_DISTANCE = 14f

/** Govde rengi. */
private val CarRed = Color(0xFFE02020)

/** Cam -- koyu, hafif mavi. */
private val CarGlass = Color(0xFF16323F)

/** Tampon, izgara ve camurluk gibi koyu parcalar. */
private val CarDark = Color(0xFF23282E)

private val TireBlack = Color(0xFF2C333B)
private val RimSilver = Color(0xFF8A9BA8)

/**
 * Kendi ekseni etrafinda donen arac tezgahi.
 *
 * NASIL CIZILIYOR
 * ---------------
 * Gercek bir 3B model (bkz. [CarMesh]) her karede donduruluyor,
 * perspektifle yansitiliyor ve yuzeyler derinlige gore sirali ciziliyor
 * (ressam algoritmasi). Her yuzeyin parlakligi kendi normalinden
 * hesaplaniyor, arkaya bakanlar eleniyor.
 *
 * Tekerlekler yuzeylerle AYNI siralamaya giriyor; ayri cizilselerdi
 * arac yandan gorunurken uzaktaki tekerlek govdenin onune tasardi.
 */
@Composable
fun VehicleTurntable(modifier: Modifier = Modifier) {
    val description = stringResource(R.string.vehicle_turntable_description)
    val context = LocalContext.current

    // Sistem animasyonlari kapaliysa surekli donen bir nesne
    // dayatmiyoruz: bu ayari acan kullanicilarin bir kismi icin hareket
    // rahatsizlik verici. O durumda arac sabit durur ama parmakla
    // cevrilmeye devam eder.
    val autoRotate = remember(context) {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) > 0f
    }

    val spin by rememberInfiniteTransition(label = "turntable").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(ROTATION_PERIOD_MS, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "spin",
    )

    // Suruklemenin kattigi sapma. Otomatik donus durmaz; kullanici
    // yalnizca hangi yuzun one gelecegini kaydirmis olur.
    var dragOffset by remember { mutableFloatStateOf(0f) }

    // Model sabittir; her karede yeniden uretilmemeli.
    val faces = remember { CarMesh.faces(body = CarRed, glass = CarGlass, dark = CarDark) }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(16f / 10f)
            .semantics { contentDescription = description }
            .pointerInput(Unit) {
                detectHorizontalDragGestures { _, dragAmount ->
                    // Sinirsiz buyumesin: uzun kullanimda kayan nokta
                    // hassasiyeti bozulur.
                    dragOffset =
                        (dragOffset + dragAmount / PIXELS_PER_REVOLUTION * 360f).mod(360f)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val angle = ((if (autoRotate) spin else 0f) + dragOffset).mod(360f)
            val yaw = Math.toRadians(angle.toDouble()).toFloat()
            val pitch = Math.toRadians(CAMERA_PITCH.toDouble()).toFloat()
            val center = Offset(size.width / 2, size.height * 0.50f)
            val scale = size.minDimension * 0.19f

            drawCarShadow(center, scale, yaw, pitch)
            drawStage(center, scale, yaw, pitch)
            drawCar(faces, center, scale, yaw, pitch)
        }
    }
}

/**
 * Zemin (y = 0) duzlemindeki bir elipsin ekrandaki yolu.
 *
 * Zemin cizgileri ekran koordinatinda sabit bir offsetle cizilirse
 * arac tezgahin uzerinde HAVADA asili durur: tekerlekler 3B'de y = 0'a
 * degiyor ama halkalar baska bir yukseklikte cizilmis oluyor. Ayni
 * donusumden gecirmek ikisini kilitler.
 */
private fun groundPath(
    rx: Float,
    rz: Float,
    yaw: Float,
    pitch: Float,
    center: Offset,
    scale: Float,
    segments: Int = 64,
): Path = Path().apply {
    for (i in 0 until segments) {
        val t = 2.0 * Math.PI * i / segments
        val v = Vec3((rx * cos(t)).toFloat(), 0f, (rz * sin(t)).toFloat())
            .rotateY(yaw)
            .rotateX(pitch)
        val p = v.project(center, scale, CAMERA_DISTANCE)
        if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
    }
    close()
}

/** Zemindeki donen platform halkalari. */
private fun DrawScope.drawStage(center: Offset, scale: Float, yaw: Float, pitch: Float) {
    // Halka dairesel; kendi ekseni etrafinda donmesi gorunmez, bu yuzden
    // yaw uygulanmaz.
    listOf(3.0f to 0.26f, 2.4f to 0.18f, 1.8f to 0.11f).forEach { (radius, alpha) ->
        drawPath(
            path = groundPath(radius, radius, 0f, pitch, center, scale),
            color = NeonCyan.copy(alpha = alpha),
            style = Stroke(width = 2f),
        )
    }

    // Donusu okunur kilan isaret noktasi.
    val marker = Vec3(3.0f, 0f, 0f).rotateY(yaw).rotateX(pitch)
    drawCircle(NeonCyan, 5f, marker.project(center, scale, CAMERA_DISTANCE))
}

private fun DrawScope.drawCar(
    faces: List<Face>,
    center: Offset,
    scale: Float,
    yaw: Float,
    pitch: Float,
) {
    fun transform(v: Vec3): Vec3 = v.rotateY(yaw).rotateX(pitch)

    // RESSAM ALGORITMASI: uzaktan yakina cizilir, boylece yakindakiler
    // uzaktakileri dogal olarak kapatir. Z-tamponu olmadan derinligi
    // dogru gostermenin en basit yolu.
    val ops = ArrayList<Pair<Float, () -> Unit>>(faces.size + 4)

    faces.forEach { face ->
        val pts = face.points.map(::transform)
        val normal = polygonNormal(pts)

        // Arkaya bakan yuzeyler ve alani sifir olanlar cizilmez.
        if (normal.z <= 0f) return@forEach

        val color = shade(normal, face.baseColor)
        val path = Path().apply {
            pts.forEachIndexed { i, v ->
                val p = v.project(center, scale, CAMERA_DISTANCE)
                if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
            }
            close()
        }

        var depth = 0f
        pts.forEach { depth += it.z }
        ops += (depth / pts.size) to { drawPath(path, color) }
    }

    // Tum tekerlekler ayni duzlemde oldugu icin yassilma bir kez hesaplanir.
    // Ekranda tekerlek bir elipse doner: kisa ekseni tekerlek normalinin
    // yonunde, boyu ise normalin kameraya bakma miktariyla orantili.
    // Bu yapilmazsa arac onden gorunurken tekerlekler daire kalir ve
    // "yapistirilmis" durur.
    val wheelAxis = transform(Vec3(0f, 0f, 1f))
    val squash = abs(wheelAxis.z).coerceAtLeast(0.07f)
    val tilt = Math.toDegrees(atan2(-wheelAxis.y, wheelAxis.x).toDouble()).toFloat()

    CarMesh.wheelCenters.forEach { w ->
        val v = transform(w)
        val p = v.project(center, scale, CAMERA_DISTANCE)
        val depthFactor = CAMERA_DISTANCE / (CAMERA_DISTANCE - v.z).coerceAtLeast(0.1f)
        val r = CarMesh.WHEEL_RADIUS * scale * depthFactor
        ops += v.z to { drawWheel(p, r, squash, tilt) }
    }

    ops.sortBy { it.first }
    ops.forEach { it.second() }

    CarMesh.headlights.forEach { drawLight(transform(it), center, scale, Color(0xFFEAF6FF), 4.5f) }
    CarMesh.taillights.forEach { drawLight(transform(it), center, scale, Color(0xFFFF2D2D), 4f) }
}

/** Araci zemine baglayan golge; aracin yonunu izler. */
private fun DrawScope.drawCarShadow(center: Offset, scale: Float, yaw: Float, pitch: Float) {
    drawPath(
        path = groundPath(2.35f, 1.15f, yaw, pitch, center, scale),
        color = Color.Black.copy(alpha = 0.45f),
    )
}

/**
 * Tekerlek: aracin donusune gore yassilan bir elips.
 *
 * @param squash kisa eksenin uzun eksene orani (1 = tam daire)
 * @param tilt kisa eksenin ekrandaki acisi (derece)
 */
private fun DrawScope.drawWheel(p: Offset, r: Float, squash: Float, tilt: Float) {
    rotate(degrees = tilt, pivot = p) {
        fun oval(color: Color, k: Float) {
            val w = r * squash * k
            val h = r * k
            drawOval(color, topLeft = Offset(p.x - w, p.y - h), size = Size(w * 2, h * 2))
        }
        oval(TireBlack, 1f)
        oval(RimSilver.copy(alpha = 0.9f), 0.45f)
        oval(TireBlack.copy(alpha = 0.85f), 0.18f)
    }
}

private fun DrawScope.drawLight(
    v: Vec3,
    center: Offset,
    scale: Float,
    color: Color,
    radius: Float,
) {
    // Aracin arkasinda kalan isik gorunmemeli.
    if (v.z <= 0f) return

    val p = v.project(center, scale, CAMERA_DISTANCE)
    drawCircle(color.copy(alpha = 0.30f), radius * 2.4f, p)
    drawCircle(color, radius, p)
}
