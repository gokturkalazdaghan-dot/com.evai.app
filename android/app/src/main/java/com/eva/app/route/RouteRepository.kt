// android/app/src/main/java/com/eva/app/route/RouteRepository.kt
package com.eva.app.route

import android.util.Log
import com.eva.app.network.APIClient
import com.eva.app.network.APIClientException
import kotlinx.coroutines.CancellationException

private const val TAG = "RouteRepository"

sealed class RouteResult {
    data class Success(val route: ActiveRoute) : RouteResult()
    data class Failure(val message: String) : RouteResult()
}

class RouteRepository(private val apiClient: APIClient) {

    /**
     * Gateway'den hedef istasyona rota ister.
     *
     * Rota hesabi SUNUCUDA yapilir: Google Routes anahtari uygulamaya
     * gomulemez (APK'dan cikarilabilir) ve saglayici degisince istemciyi
     * guncellemek gerekmez.
     */
    suspend fun routeToStation(
        originLat: Double,
        originLon: Double,
        stationId: String,
    ): RouteResult {
        return try {
            val response: RouteResponse = apiClient.post(
                path = "/v1/routes/to-station",
                body = RouteRequest(originLat, originLon, stationId),
                requiresAuth = true,
            )

            val points = decodePolyline(response.encodedPolyline)
            if (points.size < 2) {
                // Cizilemeyecek bir geometri geldiyse haritaya bos bir
                // sey koymaktansa hata dondurmek dogru.
                Log.w(TAG, "Rota geometrisi yetersiz: ${points.size} nokta")
                return RouteResult.Failure("Rota çizilemedi.")
            }

            RouteResult.Success(
                ActiveRoute(
                    stationId = stationId,
                    destinationName = response.destinationName,
                    destination = com.google.android.gms.maps.model.LatLng(
                        response.destinationLat,
                        response.destinationLon,
                    ),
                    points = points,
                    distanceMeters = response.distanceMeters,
                    durationSeconds = response.durationSeconds,
                    isRealRoad = response.isRealRoad,
                ),
            )
        } catch (e: CancellationException) {
            // Iptal bir HATA DEGILDIR (kullanici baska bir hedef sectiginde
            // onceki istek iptal edilir). Yutulursa "islem iptal edildi"
            // metni kullaniciya hata gibi gosterilirdi.
            throw e
        } catch (e: APIClientException) {
            Log.e(TAG, "Rota alinamadi: ${e.message}", e)
            RouteResult.Failure("Rota şu anda alınamıyor.")
        } catch (e: Exception) {
            Log.e(TAG, "Rota alinirken beklenmeyen hata", e)
            RouteResult.Failure("Rota şu anda alınamıyor.")
        }
    }
}
