// android/app/src/main/java/com/eva/app/ui/stations/StationsRepository.kt
package com.eva.app.ui.stations

import android.util.Log
import com.eva.app.network.APIClient
import com.eva.app.network.APIClientException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable

private const val TAG = "StationsRepository"

sealed class StationsResult {
    data class Success(val stations: List<StationDto>) : StationsResult()

    /**
     * Ag yok ama diskte kullanilabilir veri var.
     *
     * Basarisizliktan AYRI bir durum: kullaniciya gosterilecek gercek
     * fiyatlar var, yalnizca eskiler. "Ag hatasi" deyip ekrani bosaltmak,
     * on dakika onceki fiyati bilen bir uygulamanin isine yaramaz hale
     * gelmesi demekti.
     */
    data class Offline(
        val stations: List<StationDto>,
        val fetchedAtEpochMs: Long,
        val isStale: Boolean,
    ) : StationsResult()

    data class Failure(val message: String) : StationsResult()
}

@Serializable
private data class NearbyStationsEmptyBody(val placeholder: String = "unused")

class StationsRepository(
    private val apiClient: APIClient,
    private val cache: StationsCache,
) {

    /**
     * Aşama 2'deki NestJS `GET /v1/stations/nearby` uç noktasına karşılık
     * gelir. Ağ hatası veya sunucu hatası durumunda exception fırlatmak
     * yerine StationsResult.Failure döndürülüyor — çağıran Compose katmanı
     * bunu doğrudan kullanıcı dostu bir hata durumuna çevirebilir.
     */
    suspend fun findNearbyStations(
        lat: Double,
        lon: Double,
        radiusMeters: Int = 15_000,
        connectorTypes: List<String> = emptyList(),
        minPowerKw: Double? = null,
    ): StationsResult {
        val queryParams = buildMap {
            put("lat", lat.toString())
            put("lon", lon.toString())
            put("radiusMeters", radiusMeters.toString())
            if (connectorTypes.isNotEmpty()) {
                put("connectorTypes", connectorTypes.joinToString(","))
            }
            minPowerKw?.let { put("minPowerKw", it.toString()) }
        }

        return try {
            val stations: List<StationDto> = apiClient.get(
                path = "/v1/stations/nearby",
                queryParams = queryParams,
                requiresAuth = true,
            )
            // Basarili her sorgu onbellegi tazeler.
            cache.save(stations, lat, lon)
            StationsResult.Success(stations)
        } catch (e: CancellationException) {
            // ONEMLI: CancellationException bir HATA DEGILDIR, iptal
            // sinyalidir ve coroutine'e geri firlatilmak ZORUNDADIR.
            //
            // Yutuldugunda: konum guncellenince onceki istek iptal edilir,
            // bu blok onu "basarisiz" sayar ve kullaniciya teknik bir metin
            // ("StandaloneCoroutine was cancelled") gosterilir. Ayrica iptal
            // edilmis is, state'e yazmaya devam ederek yeni sonucun uzerine
            // biner.
            throw e
        } catch (e: APIClientException) {
            Log.w(TAG, "Yakın istasyon sorgusu başarısız, önbelleğe bakılıyor: ${e.message}")
            fallbackToCache(lat, lon)
                ?: StationsResult.Failure(e.message ?: "İstasyon verisi alınamadı.")
        } catch (e: Exception) {
            Log.e(TAG, "Beklenmeyen hata.", e)
            fallbackToCache(lat, lon)
                ?: StationsResult.Failure("Bağlantı kurulamadı. Biraz sonra tekrar dener misin?")
        }
    }

    /**
     * Ag basarisiz oldugunda son bilinen veriyi dondurur.
     *
     * Onbellek YAKINDAKI bir konuma ait degilse null doner -- baska bir
     * sehrin fiyatlarini gostermektense hata gostermek dogru.
     */
    private fun fallbackToCache(lat: Double, lon: Double): StationsResult? {
        val cached = cache.load(lat, lon) ?: return null
        Log.i(TAG, "Önbellekten ${cached.stations.size} istasyon sunuluyor.")
        return StationsResult.Offline(
            stations = cached.stations,
            fetchedAtEpochMs = cached.fetchedAtEpochMs,
            isStale = cached.isStale,
        )
    }
}
