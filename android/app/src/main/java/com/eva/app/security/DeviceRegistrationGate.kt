// android/app/src/main/java/com/eva/app/security/DeviceRegistrationGate.kt
package com.eva.app.security

import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "RegistrationGate"

/**
 * İmzalı isteklerin, cihaz kaydının bitmesini beklemesini sağlar.
 *
 * ÇÖZDÜĞÜ HATA
 * ------------
 * Cihaz kaydı `EvaApplication.onCreate()` içinde ateşle-unut olarak
 * başlatılıyordu. Panel ise açılır açılmaz yakın istasyonları soruyordu.
 * Kurulumdan sonraki İLK açılışta bu iki iş yarışıyor ve istasyon
 * sorgusu kayıttan ~250 ms önce gidiyordu; imzalama anahtarı henüz
 * olmadığı için istek imzasız çıkıp Gateway'den **401** dönüyordu.
 *
 * Sonuç: mağazadan indiren kullanıcı uygulamayı ilk açtığında HİÇ fiyat
 * göremiyordu. İkinci açılışta kayıt zaten diskte olduğu için sorun
 * kendiliğinden kayboluyordu — bu yüzden geliştirirken fark edilmesi de
 * zordu. Cihazda ölçüldü:
 *
 *     09:45:26.139  istasyon sorgusu -> HTTP 401
 *     09:45:26.389  cihaz kaydı başarılı
 *
 * NEDEN AYRI BİR SINIF
 * --------------------
 * Beklemeyi doğrudan APIClient'a koymak APIClient -> Registration ->
 * APIClient döngüsü yaratırdı (kayıt isteği de APIClient üzerinden
 * gider). Bağımlılığı olmayan bu küçük kapı döngüyü kırıyor.
 */
@Singleton
class DeviceRegistrationGate @Inject constructor() {

    private val ready = CompletableDeferred<Unit>()

    /**
     * Kayıt denemesi bitti — BAŞARILI YA DA BAŞARISIZ.
     *
     * Başarısızlıkta da açılır: kayıt olmadıysa beklemeye devam etmek
     * isteği kurtarmaz, yalnızca kullanıcıyı boş ekranda bekletir.
     * O durumda istek imzasız gider, 401 alır ve uygulama önbelleğe
     * düşer — yani bugünkü davranışın aynısı, ama gerçekten denedikten
     * sonra.
     */
    fun markAttempted() {
        if (ready.complete(Unit)) {
            Log.i(TAG, "Cihaz kaydı denemesi tamamlandı; imzalı istekler serbest.")
        }
    }

    /**
     * Kayıt denemesinin bitmesini bekler.
     *
     * Zaman aşımı VAR: kayıt bir şekilde hiç tamamlanmazsa (ağ askıda
     * kaldı, süreç garip bir duruma girdi) veri yolu sonsuza kadar
     * kilitlenmemeli. Süre dolarsa istek imzasız gider — kilitlenmiş bir
     * ekran yerine başarısız olan bir istek yeğdir.
     */
    suspend fun awaitAttempted() {
        if (ready.isCompleted) return

        val completed = withTimeoutOrNull(AWAIT_TIMEOUT_MS) { ready.await() }
        if (completed == null) {
            Log.w(TAG, "Cihaz kaydı ${AWAIT_TIMEOUT_MS} ms içinde bitmedi; istek imzasız gidiyor.")
        }
    }

    private companion object {
        /**
         * 8 saniye: kayıt normalde birkaç yüz milisaniye sürer. Bu süre
         * yalnızca patolojik durumlar için bir emniyet valfi.
         */
        const val AWAIT_TIMEOUT_MS = 8_000L
    }
}
