// android/app/src/main/java/com/eva/app/ui/dashboard/components/CarMesh.kt
package com.eva.app.ui.dashboard.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Uc boyutlu nokta. x: uzunluk (burun +), y: yukseklik, z: genislik. */
data class Vec3(val x: Float, val y: Float, val z: Float)

/** Modelin bir yuzeyi: dunya uzayindaki koseleri ve temel rengi. */
data class Face(val points: List<Vec3>, val baseColor: Color)

/** Aracin uzunluk ekseni boyunca bir en-kesit. */
private data class Ring(
    val x: Float,
    val bottomY: Float,
    val topY: Float,
    val halfWidth: Float,
) {
    /** Halkanin ekseni uzerindeki orta nokta -- katinin ICINDE kalir. */
    val axis: Vec3 get() = Vec3(x, (bottomY + topY) / 2f, 0f)
}

/**
 * Dusuk poligonlu bir SUV / jeep.
 *
 * NEDEN JEEP, SPOR ARABA DEGIL
 * ----------------------------
 * Duz golgeli (flat-shaded) poligonla spor araba cizmek kotu sonuc verir:
 * spor arabanin kimligi YUMUSAK EGRILERINDEDIR ve az sayida yuzeyle o
 * egriler koselere doner -- goz bunu "araba" diye okumaz. Jeep gercekte
 * de koselidir; ayni teknik burada dogal duruyor. Onceki deneme tam da
 * bu yuzden arabaya benzemiyordu.
 *
 * SILUETI KURAN UC SEY
 * --------------------
 *   1. Alcak, duz kaput + geride ve yuksek kabin (BELT ve ROOF farki)
 *   2. Yatik on cam, neredeyse dik arka kapak
 *   3. Buyuk tekerlekler ve uzerlerindeki camurluk kabartmasi
 */
object CarMesh {

    // Olculer metre; kompakt bir SUV oranlarina yakin.
    private const val CLEARANCE = 0.58f  // govde alt hatti
    private const val BELT = 1.36f       // cam alti kusak hatti
    private const val ROOF = 2.00f       // tavan

    private const val BODY_HALF = 0.95f
    private const val CABIN_HALF = 0.84f

    /** Govde halkalari: burundan kuyruga. */
    private val bodyRings = listOf(
        Ring(2.10f, CLEARANCE + 0.10f, BELT - 0.10f, 0.86f), // on tampon
        Ring(1.98f, CLEARANCE + 0.02f, BELT, 0.90f),         // izgara
        Ring(1.40f, CLEARANCE, BELT, BODY_HALF),             // on aks
        Ring(0.55f, CLEARANCE, BELT, BODY_HALF),             // on kapi
        Ring(-0.55f, CLEARANCE, BELT, BODY_HALF),            // arka kapi
        Ring(-1.34f, CLEARANCE, BELT, BODY_HALF),            // arka aks
        Ring(-2.02f, CLEARANCE + 0.02f, BELT, 0.88f),        // arka panel
        Ring(-2.12f, CLEARANCE + 0.10f, BELT - 0.10f, 0.84f),// arka tampon
    )

    /**
     * Kabin halkalari.
     *
     * Ilk halka sifir yuksekliktedir: on cam, kusak hattindan tavana
     * yukselen tek bir yatik yuzey olarak dogar. Bu, A-direklerini de
     * dogru acida ucgen yapar.
     */
    private val cabinRings = listOf(
        Ring(0.85f, BELT, BELT, CABIN_HALF),   // on cam tabani (cizgi)
        Ring(0.10f, BELT, ROOF, CABIN_HALF),    // on cam ustu
        Ring(-1.85f, BELT, ROOF, CABIN_HALF),   // tavanin arkasi / arka cam
    )

    const val WHEEL_RADIUS = 0.58f

    /** Tekerlek merkezleri; govde yuzeyinin hafif disinda. */
    val wheelCenters: List<Vec3> = listOf(
        Vec3(1.40f, WHEEL_RADIUS, -0.99f),
        Vec3(1.40f, WHEEL_RADIUS, 0.99f),
        Vec3(-1.34f, WHEEL_RADIUS, -0.99f),
        Vec3(-1.34f, WHEEL_RADIUS, 0.99f),
    )

    val headlights: List<Vec3> = listOf(
        Vec3(2.08f, BELT - 0.34f, -0.58f),
        Vec3(2.08f, BELT - 0.34f, 0.58f),
    )

    val taillights: List<Vec3> = listOf(
        Vec3(-2.10f, BELT - 0.28f, -0.66f),
        Vec3(-2.10f, BELT - 0.28f, 0.66f),
    )

    /**
     * Modelin tum yuzeyleri, hepsi disari bakacak sekilde yonlendirilmis.
     *
     * Sonuc sabittir; cagiran taraf `remember` ile bir kez uretmelidir.
     *
     * @param body govde rengi
     * @param glass cam rengi
     * @param dark izgara, tampon ve camurluk gibi koyu parcalar
     */
    fun faces(body: Color, glass: Color, dark: Color): List<Face> {
        val out = mutableListOf<Face>()

        // --- Govde ---
        bodyRings.zipWithNext { front, rear -> out += connect(front, rear, body) }
        // On izgara ve arka kapak: komsu halkanin ekseni pivot olur,
        // boylece kapak normali dogru yone (disari) bakar.
        out += face(corners(bodyRings.first()), dark, bodyRings[1].axis)
        out += face(corners(bodyRings.last()), dark, bodyRings[bodyRings.size - 2].axis)

        // --- Kabin ---
        // On cam: hem yatik yuzey hem A-direkleri cam.
        out += connect(cabinRings[0], cabinRings[1], glass)
        // Kabin: yanlar cam, tavan govde rengi.
        out += connect(cabinRings[1], cabinRings[2], glass, topColor = body)
        // Arka kapak cami (neredeyse dik).
        out += face(corners(cabinRings[2]), glass, cabinRings[1].axis)

        // --- Camurluklar ---
        wheelCenters.forEach { out += fenderFlare(it, dark) }

        return out
    }

    // -----------------------------------------------------------------
    // Geometri yardimcilari
    // -----------------------------------------------------------------

    /** Halkanin dort kosesi: alt-sol, alt-sag, ust-sag, ust-sol. */
    private fun corners(r: Ring) = listOf(
        Vec3(r.x, r.bottomY, -r.halfWidth),
        Vec3(r.x, r.bottomY, r.halfWidth),
        Vec3(r.x, r.topY, r.halfWidth),
        Vec3(r.x, r.topY, -r.halfWidth),
    )

    /**
     * Bir yuzey uretir ve normalinin DISARI baktigindan emin olur.
     *
     * Kose sirasini elle dogru yazmak, 90 yuzeyde kacinilmaz olarak
     * hataya doner ve hatali yuzey "ic taraf" sayilip elenir -- aracta
     * delik gorunur. Pivot (katinin icindeki bir nokta) verildiginde
     * yon otomatik duzeltilebilir; boylece siralama hatasi imkansiz olur.
     */
    private fun face(points: List<Vec3>, color: Color, pivot: Vec3): Face {
        val n = polygonNormal(points)
        val cx = points.map { it.x }.average().toFloat() - pivot.x
        val cy = points.map { it.y }.average().toFloat() - pivot.y
        val cz = points.map { it.z }.average().toFloat() - pivot.z
        val outward = n.x * cx + n.y * cy + n.z * cz
        return Face(if (outward < 0f) points.reversed() else points, color)
    }

    /** Iki halkayi dort yuzeyle birlestirir: alt, sag, ust, sol. */
    private fun connect(a: Ring, b: Ring, color: Color, topColor: Color = color): List<Face> {
        val f = corners(a)
        val r = corners(b)
        // Pivot iki halkanin ekseninin ortasi -- her zaman katinin icinde.
        val pivot = Vec3(
            (a.axis.x + b.axis.x) / 2f,
            (a.axis.y + b.axis.y) / 2f,
            0f,
        )
        return listOf(
            face(listOf(f[0], f[1], r[1], r[0]), color, pivot),    // alt
            face(listOf(f[1], f[2], r[2], r[1]), color, pivot),    // sag
            face(listOf(f[2], f[3], r[3], r[2]), topColor, pivot), // ust
            face(listOf(f[3], f[0], r[0], r[3]), color, pivot),    // sol
        )
    }

    /**
     * Tekerlek ustundeki camurluk kabartmasi.
     *
     * Duz bir kutuya tekerlek yapistirmak "oyuncak" gorunumu veriyordu;
     * camurluk, tekerlegin govdeye ait oldugu hissini kuruyor ve
     * govde ile tekerlek arasindaki bosluk cizgisini gizliyor.
     */
    private fun fenderFlare(wheel: Vec3, color: Color): List<Face> {
        val faces = mutableListOf<Face>()
        val segments = 6
        val sign = if (wheel.z > 0f) 1f else -1f
        val outerZ = wheel.z + sign * 0.13f
        val innerZ = wheel.z - sign * 0.10f
        val radius = WHEEL_RADIUS + 0.20f

        for (i in 0 until segments) {
            // Yayin ucu tam yatayda degil: gercek camurluklar da
            // tekerlegin biraz uzerinde biter.
            val a0 = Math.PI * (0.05 + 0.90 * i / segments)
            val a1 = Math.PI * (0.05 + 0.90 * (i + 1) / segments)

            fun arc(angle: Double, z: Float) = Vec3(
                wheel.x + (radius * cos(angle)).toFloat(),
                wheel.y + (radius * sin(angle)).toFloat(),
                z,
            )

            faces += face(
                listOf(arc(a0, outerZ), arc(a1, outerZ), arc(a1, innerZ), arc(a0, innerZ)),
                color,
                // Pivot tekerlek merkezi: normal yaydan disari bakar.
                wheel,
            )
        }
        return faces
    }
}

// ---------------------------------------------------------------------
// Donusum, yansitma, aydinlatma
// ---------------------------------------------------------------------

fun Vec3.rotateY(radians: Float): Vec3 {
    val c = cos(radians)
    val s = sin(radians)
    return Vec3(x * c + z * s, y, -x * s + z * c)
}

fun Vec3.rotateX(radians: Float): Vec3 {
    val c = cos(radians)
    val s = sin(radians)
    return Vec3(x, y * c - z * s, y * s + z * c)
}

/**
 * Perspektif yansitma.
 *
 * Paralel yansitma daha basit olurdu ama arac "kagittan kesilmis" gibi
 * durur; yakin kenarlarin buyumesi hacim hissini veren sey.
 */
fun Vec3.project(center: Offset, scale: Float, cameraDistance: Float): Offset {
    val depth = (cameraDistance - z).coerceAtLeast(0.1f)
    val factor = cameraDistance / depth
    return Offset(center.x + x * scale * factor, center.y - y * scale * factor)
}

/**
 * Cokgen normali -- Newell yontemi.
 *
 * Uc koseden capraz carpim daha kisa olurdu ama modelde COKEN kenarlar
 * var (on cam tabani sifir yukseklikte bir cizgi, A-direkleri bu yuzden
 * ucgen). Orada ilk uc kose ayni dogru uzerine duser ve capraz carpim
 * sifir verir -- yuzey siyah cizilir. Newell tum kenarlari topladigi
 * icin bu durumdan etkilenmez.
 */
fun polygonNormal(points: List<Vec3>): Vec3 {
    var nx = 0f
    var ny = 0f
    var nz = 0f
    for (i in points.indices) {
        val a = points[i]
        val b = points[(i + 1) % points.size]
        nx += (a.y - b.y) * (a.z + b.z)
        ny += (a.z - b.z) * (a.x + b.x)
        nz += (a.x - b.x) * (a.y + b.y)
    }
    val len = sqrt(nx * nx + ny * ny + nz * nz)
    // Tamamen coken yuzey (alan sifir): normali yok, ciziminde de yok.
    if (len < 1e-6f) return Vec3(0f, 0f, 0f)
    return Vec3(nx / len, ny / len, nz / len)
}

/**
 * Yonlu aydinlatma. Isik ust-on-sol taraftan.
 *
 * Taban parlaklik 0.34: golgedeki yuzler tamamen kararmasin, yoksa
 * aracin bir yani siyah bir lekeye doner.
 */
fun shade(normal: Vec3, baseColor: Color): Color {
    val dot = normal.x * -0.42f + normal.y * 0.80f + normal.z * 0.43f
    val intensity = (0.34f + 0.66f * dot.coerceIn(0f, 1f)).coerceIn(0f, 1f)
    return Color(
        red = baseColor.red * intensity,
        green = baseColor.green * intensity,
        blue = baseColor.blue * intensity,
        alpha = baseColor.alpha,
    )
}
