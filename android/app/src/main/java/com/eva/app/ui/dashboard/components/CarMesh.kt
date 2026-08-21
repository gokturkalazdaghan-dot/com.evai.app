// android/app/src/main/java/com/eva/app/ui/dashboard/components/CarMesh.kt
package com.eva.app.ui.dashboard.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Uc boyutlu bir nokta.
 *
 * Eksenler: x uzunluk (burun +), y yukseklik (yukari +), z genislik.
 */
data class Vec3(val x: Float, val y: Float, val z: Float)

/**
 * Modelin bir yuzeyi.
 *
 * [indices] saat yonunun TERSINE siralanir; normal hesabi buna dayanir
 * ve yanlis sirada bir yuzey ters aydinlatilir.
 */
data class Face(val indices: List<Int>, val baseColor: Color)

/**
 * Dusuk poligonlu bir spor otomobil.
 *
 * NEDEN GERCEK 3B MODEL
 * ---------------------
 * Onceki surumde tek bir yandan gorunum cizimi `rotationY` ile
 * donduruluyordu. O yaklasim aracin ONUNU ve ARKASINI gosteremez:
 * 90 dereceye gelindiginde cizim bir cizgiye iner, cunku derinligi
 * yoktur. Gercek bir model ise her aciyi dogru gosterir.
 *
 * Model bilincli olarak sade: ~20 yuzey. Daha fazlasi her karede daha
 * cok hesap demek ve bu bir vitrin gorseli, teknik resim degil.
 */
object CarMesh {

    // Olculer, gercek bir spor otomobilin oranlarina yakin (metre).
    private const val LENGTH = 4.3f
    private const val WIDTH = 1.9f
    private const val BODY_BOTTOM = 0.35f
    private const val BODY_TOP = 1.05f
    private const val ROOF_TOP = 1.42f

    private val hx = LENGTH / 2
    private val hz = WIDTH / 2

    /**
     * Govde: burun ve kuyruk daraltilmis bir kutu.
     *
     * Daraltma onemli: duz bir kutu "araba" degil "tugla" gibi durur.
     */
    val vertices: List<Vec3> = listOf(
        // 0-3: alt govde, burun (daraltilmis)
        Vec3(hx, BODY_BOTTOM, -hz * 0.72f),
        Vec3(hx, BODY_BOTTOM, hz * 0.72f),
        Vec3(hx, BODY_TOP * 0.78f, hz * 0.66f),
        Vec3(hx, BODY_TOP * 0.78f, -hz * 0.66f),

        // 4-7: alt govde, on aks hizasi (en genis)
        Vec3(hx * 0.45f, BODY_BOTTOM, -hz),
        Vec3(hx * 0.45f, BODY_BOTTOM, hz),
        Vec3(hx * 0.45f, BODY_TOP, hz),
        Vec3(hx * 0.45f, BODY_TOP, -hz),

        // 8-11: arka aks hizasi
        Vec3(-hx * 0.45f, BODY_BOTTOM, -hz),
        Vec3(-hx * 0.45f, BODY_BOTTOM, hz),
        Vec3(-hx * 0.45f, BODY_TOP, hz),
        Vec3(-hx * 0.45f, BODY_TOP, -hz),

        // 12-15: kuyruk (daraltilmis)
        Vec3(-hx, BODY_BOTTOM, -hz * 0.74f),
        Vec3(-hx, BODY_BOTTOM, hz * 0.74f),
        Vec3(-hx, BODY_TOP * 0.86f, hz * 0.68f),
        Vec3(-hx, BODY_TOP * 0.86f, -hz * 0.68f),

        // 16-19: kabin tabani
        Vec3(hx * 0.30f, BODY_TOP, -hz * 0.84f),
        Vec3(hx * 0.30f, BODY_TOP, hz * 0.84f),
        Vec3(-hx * 0.42f, BODY_TOP, hz * 0.84f),
        Vec3(-hx * 0.42f, BODY_TOP, -hz * 0.84f),

        // 20-23: tavan (kabinden dar -> yatik cam etkisi)
        Vec3(hx * 0.02f, ROOF_TOP, -hz * 0.62f),
        Vec3(hx * 0.02f, ROOF_TOP, hz * 0.62f),
        Vec3(-hx * 0.30f, ROOF_TOP, hz * 0.62f),
        Vec3(-hx * 0.30f, ROOF_TOP, -hz * 0.62f),
    )

    /** Ana govde rengi -- disaridan degistirilebilir. */
    fun faces(bodyColor: Color, glassColor: Color): List<Face> = listOf(
        // Burun
        Face(listOf(0, 1, 2, 3), bodyColor),
        // Burun ile on aks arasi (kaput ustu)
        Face(listOf(3, 2, 6, 7), bodyColor),
        Face(listOf(0, 3, 7, 4), bodyColor),   // sol
        Face(listOf(1, 0, 4, 5), bodyColor),   // alt
        Face(listOf(2, 1, 5, 6), bodyColor),   // sag

        // Orta govde
        Face(listOf(7, 6, 10, 11), bodyColor), // ust
        Face(listOf(4, 7, 11, 8), bodyColor),  // sol
        Face(listOf(5, 4, 8, 9), bodyColor),   // alt
        Face(listOf(6, 5, 9, 10), bodyColor),  // sag

        // Kuyruk
        Face(listOf(11, 10, 14, 15), bodyColor),
        Face(listOf(8, 11, 15, 12), bodyColor),
        Face(listOf(9, 8, 12, 13), bodyColor),
        Face(listOf(10, 9, 13, 14), bodyColor),
        Face(listOf(12, 15, 14, 13), bodyColor),

        // Kabin: on cam, yan camlar, arka cam, tavan
        Face(listOf(16, 17, 21, 20), glassColor),  // on cam
        Face(listOf(19, 16, 20, 23), glassColor),  // sol
        Face(listOf(17, 18, 22, 21), glassColor),  // sag
        Face(listOf(18, 19, 23, 22), glassColor),  // arka cam
        Face(listOf(20, 21, 22, 23), bodyColor),   // tavan
    )

    /** Tekerlek merkezleri ve yaricapi. */
    const val WHEEL_RADIUS = 0.36f
    val wheelCenters: List<Vec3> = listOf(
        Vec3(hx * 0.52f, WHEEL_RADIUS, -hz * 0.92f),
        Vec3(hx * 0.52f, WHEEL_RADIUS, hz * 0.92f),
        Vec3(-hx * 0.52f, WHEEL_RADIUS, -hz * 0.92f),
        Vec3(-hx * 0.52f, WHEEL_RADIUS, hz * 0.92f),
    )

    /** Far ve stop konumlari (yuzey degil, isik noktasi). */
    val headlights: List<Vec3> = listOf(
        Vec3(hx * 0.98f, BODY_TOP * 0.62f, -hz * 0.50f),
        Vec3(hx * 0.98f, BODY_TOP * 0.62f, hz * 0.50f),
    )
    val taillights: List<Vec3> = listOf(
        Vec3(-hx * 0.98f, BODY_TOP * 0.68f, -hz * 0.52f),
        Vec3(-hx * 0.98f, BODY_TOP * 0.68f, hz * 0.52f),
    )
}

// ---------------------------------------------------------------------
// Donusum ve yansitma
// ---------------------------------------------------------------------

/** Y ekseni etrafinda dondurur (aracin kendi ekseninde donmesi). */
fun Vec3.rotateY(radians: Float): Vec3 {
    val c = cos(radians)
    val s = sin(radians)
    return Vec3(x * c + z * s, y, -x * s + z * c)
}

/** X ekseni etrafinda dondurur (kameranin yukaridan bakma acisi). */
fun Vec3.rotateX(radians: Float): Vec3 {
    val c = cos(radians)
    val s = sin(radians)
    return Vec3(x, y * c - z * s, y * s + z * c)
}

/**
 * Perspektif yansitma.
 *
 * Ortografik (paralel) yansitma daha basit olurdu ama arac "kagittan
 * kesilmis" gibi durur -- yakin kenarlar buyumez. Perspektif, hacim
 * hissini veren sey.
 */
fun Vec3.project(
    center: Offset,
    scale: Float,
    cameraDistance: Float,
): Offset {
    val depth = cameraDistance - z
    // Kamera duzlemine cok yaklasan noktalar sonsuza gider; taban deger
    // konarak cizimin patlamasi onlenir.
    val factor = cameraDistance / depth.coerceAtLeast(0.1f)
    return Offset(
        center.x + x * scale * factor,
        center.y - y * scale * factor,
    )
}

/**
 * Yuzeyin normali (capraz carpim).
 *
 * Iki ise yarar: (1) aydinlatma, (2) arkaya bakan yuzeyleri elemek.
 */
fun faceNormal(a: Vec3, b: Vec3, c: Vec3): Vec3 {
    val u = Vec3(b.x - a.x, b.y - a.y, b.z - a.z)
    val v = Vec3(c.x - a.x, c.y - a.y, c.z - a.z)
    val n = Vec3(
        u.y * v.z - u.z * v.y,
        u.z * v.x - u.x * v.z,
        u.x * v.y - u.y * v.x,
    )
    val len = sqrt(n.x * n.x + n.y * n.y + n.z * n.z).coerceAtLeast(1e-6f)
    return Vec3(n.x / len, n.y / len, n.z / len)
}

/**
 * Basit yonlu aydinlatma.
 *
 * Isik ust-on-sol taraftan. Tamamen duz renk kullanmak modeli yassi
 * gosterir; asil hacim hissini yuzey basina degisen parlaklik verir.
 */
fun shade(normal: Vec3, baseColor: Color): Color {
    val lx = -0.45f
    val ly = 0.78f
    val lz = 0.44f
    val dot = (normal.x * lx + normal.y * ly + normal.z * lz)

    // 0.38 taban: golgede kalan yuzler tamamen kararmasin, aksi halde
    // aracin bir yani siyah bir lekeye doner.
    val intensity = (0.38f + 0.62f * dot.coerceIn(0f, 1f)).coerceIn(0f, 1f)

    return Color(
        red = baseColor.red * intensity,
        green = baseColor.green * intensity,
        blue = baseColor.blue * intensity,
        alpha = baseColor.alpha,
    )
}
