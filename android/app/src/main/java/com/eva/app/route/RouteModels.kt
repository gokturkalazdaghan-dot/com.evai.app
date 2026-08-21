// android/app/src/main/java/com/eva/app/route/RouteModels.kt
package com.eva.app.route

import com.google.android.gms.maps.model.LatLng
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class RouteRequest(
    val originLat: Double,
    val originLon: Double,
    /**
     * Hedef KOORDINAT degil KIMLIK gonderilir; koordinati sunucu kendi
     * kaydindan okur. Boylece istemci uydurma bir hedefe rota cizdiremez.
     */
    val stationId: String,
)

@Serializable
data class RouteResponse(
    val encodedPolyline: String,
    val distanceMeters: Int,
    val durationSeconds: Int? = null,
    /** "road" ya da "straight_line". */
    val quality: String,
    val destinationName: String,
    val destinationLat: Double,
    val destinationLon: Double,
) {
    /** Rota gercek yol geometrisi mi, yoksa kus ucusu bir cizgi mi? */
    val isRealRoad: Boolean get() = quality == QUALITY_ROAD

    companion object {
        const val QUALITY_ROAD = "road"
    }
}

/**
 * Ekranda cizilmeye hazir rota.
 *
 * `encodedPolyline` her karede cozulmesin diye cozum bir kez yapilip
 * burada tutulur.
 */
data class ActiveRoute(
    val stationId: String,
    val destinationName: String,
    val destination: LatLng,
    val points: List<LatLng>,
    val distanceMeters: Int,
    val durationSeconds: Int?,
    val isRealRoad: Boolean,
)

/**
 * Google encoded polyline (precision 5) cozucusu.
 *
 * Neden elde: `maps-compose` bunu sunmuyor; `android-maps-utils`
 * bagimliligini yalnizca tek bir fonksiyon icin eklemek gereksiz.
 * Algoritma sabit ve iyi tanimli (Google Encoded Polyline Algorithm).
 */
fun decodePolyline(encoded: String): List<LatLng> {
    val points = ArrayList<LatLng>()
    var index = 0
    var lat = 0
    var lon = 0

    while (index < encoded.length) {
        var shift = 0
        var result = 0
        var b: Int
        do {
            b = encoded[index++].code - 63
            result = result or ((b and 0x1f) shl shift)
            shift += 5
        } while (b >= 0x20 && index < encoded.length)
        lat += if (result and 1 != 0) (result shr 1).inv() else result shr 1

        shift = 0
        result = 0
        do {
            b = encoded[index++].code - 63
            result = result or ((b and 0x1f) shl shift)
            shift += 5
        } while (b >= 0x20 && index < encoded.length)
        lon += if (result and 1 != 0) (result shr 1).inv() else result shr 1

        points.add(LatLng(lat / 1e5, lon / 1e5))
    }
    return points
}
