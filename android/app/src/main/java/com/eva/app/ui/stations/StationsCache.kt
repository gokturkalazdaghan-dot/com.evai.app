// android/app/src/main/java/com/eva/app/ui/stations/StationsCache.kt
package com.eva.app.ui.stations

import android.content.Context
import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.math.abs

private const val TAG = "StationsCache"
/**
 * Bu ad DataDeletionRepository tarafindan da okunur: "verilerimi sil"
 * akisi bu depoyu temizlemek zorunda. Adi burada tek yerde tutmak,
 * degistirildiginde silme akisinin sessizce eksik kalmasini onler.
 */
internal const val STATIONS_CACHE_PREFS = "eva.stations.cache"
private const val KEY_PAYLOAD = "lastNearby"

/**
 * Onbellek bu mesafeden UZAK bir konum icin kullanilmaz (metre).
 *
 * 25 km: kullanici sehir degistirdiyse eski sehrin istasyonlarini
 * "yakinindaki" diye gostermek, cozmeye calistigimiz San Francisco
 * hatasinin ta kendisi olurdu.
 */
private const val MAX_REUSE_DISTANCE_METERS = 25_000.0

/**
 * Fiyatlarin "eski" sayilacagi sure.
 *
 * 6 saat: tarifeler gun icinde degisebilir. Bundan eskisi hala
 * gosterilir -- ama UI'da acikca eski oldugu yazar. Tamamen atmak,
 * cevrimdisi kullaniciyi bos ekranla birakmak olurdu.
 */
const val CACHE_STALE_AFTER_MS = 6 * 60 * 60 * 1000L

@Serializable
private data class CachedNearby(
    val stations: List<StationDto>,
    val lat: Double,
    val lon: Double,
    val fetchedAtEpochMs: Long,
)

/** Onbellekten donen sonuc. */
data class CachedStations(
    val stations: List<StationDto>,
    val fetchedAtEpochMs: Long,
) {
    val ageMs: Long get() = System.currentTimeMillis() - fetchedAtEpochMs
    val isStale: Boolean get() = ageMs > CACHE_STALE_AFTER_MS
}

/**
 * Son basarili istasyon sorgusunu diskte tutar.
 *
 * NEDEN GEREKLI
 * -------------
 * Fiyat gormek uygulamanin TEMEL islevi. Ag kesildiginde ekranin
 * tamamen bosalip "ag hatasi" demesi, uygulamayi kullanilamaz yapiyordu
 * -- oysa on dakika onceki fiyat, hicbir fiyattan iyidir. Yeter ki
 * kullanici bunun ESKI bir veri oldugunu bilsin.
 *
 * SIFRELEME YOK: bu veri herkese acik istasyon ve fiyat bilgisi, sir
 * degil. Kullanicinin sorgu yaptigi KONUM da burada tutuluyor ama zaten
 * cihazin kendi konum servisinden geliyor.
 */
class StationsCache(context: Context) {

    private val prefs = context.getSharedPreferences(STATIONS_CACHE_PREFS, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    fun save(stations: List<StationDto>, lat: Double, lon: Double) {
        // Bos sonucu onbellege YAZMA: gecici bir sunucu sorunu yuzunden
        // donen bos liste, sonraki cevrimdisi acilislarda "hicbir istasyon
        // yok" gibi gorunurdu.
        if (stations.isEmpty()) return

        runCatching {
            prefs.edit()
                .putString(
                    KEY_PAYLOAD,
                    json.encodeToString(
                        CachedNearby(stations, lat, lon, System.currentTimeMillis()),
                    ),
                )
                .apply()
        }.onFailure { Log.w(TAG, "Onbellek yazilamadi.", it) }
    }

    /**
     * Verilen konum icin kullanilabilir bir onbellek var mi?
     *
     * @return yakinlik kosulu saglanmiyorsa null -- baska bir sehrin
     *         verisini dondurmektense hicbir sey dondurmemek dogru.
     */
    fun load(lat: Double, lon: Double): CachedStations? {
        val raw = prefs.getString(KEY_PAYLOAD, null) ?: return null

        val cached = runCatching { json.decodeFromString<CachedNearby>(raw) }
            .onFailure { Log.w(TAG, "Onbellek cozulemedi, yok sayiliyor.", it) }
            .getOrNull() ?: return null

        if (!isCacheUsableFor(lat, lon, cached.lat, cached.lon)) {
            Log.i(TAG, "Onbellek cok uzak bir konuma ait, kullanilmiyor.")
            return null
        }

        return CachedStations(cached.stations, cached.fetchedAtEpochMs)
    }

    fun clear() {
        prefs.edit().remove(KEY_PAYLOAD).apply()
    }
}

/**
 * Onbellek bu konum icin kullanilabilir mi?
 *
 * Saf fonksiyon: cihaz ya da disk gerektirmez, dogrudan test edilir.
 */
fun isCacheUsableFor(
    currentLat: Double,
    currentLon: Double,
    cachedLat: Double,
    cachedLon: Double,
): Boolean = haversineMeters(currentLat, currentLon, cachedLat, cachedLon) <=
    MAX_REUSE_DISTANCE_METERS

private const val EARTH_RADIUS_METERS = 6_371_000.0

private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
        Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
        Math.sin(dLon / 2) * Math.sin(dLon / 2)
    return 2 * EARTH_RADIUS_METERS * Math.asin(Math.sqrt(abs(a)))
}
