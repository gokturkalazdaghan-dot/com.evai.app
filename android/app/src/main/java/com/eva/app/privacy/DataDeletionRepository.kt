// android/app/src/main/java/com/eva/app/privacy/DataDeletionRepository.kt
package com.eva.app.privacy

import android.content.Context
import android.util.Log
import com.eva.app.network.APIClient
import com.eva.app.location.LAST_LOCATION_PREFS
import com.eva.app.network.APIClientException
import com.eva.app.security.RequestSigner
import com.eva.app.security.SecureTokenStore
import com.eva.app.ui.stations.STATIONS_CACHE_PREFS
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

private const val TAG = "DataDeletion"

/** Sunucunun sildigi satir dokumunu iceren yanit. */
@Serializable
data class DeletionResponse(
    val deleted: Boolean = false,
    @SerialName("subscriptionRetained")
    val subscriptionRetained: Boolean = false,
)

/** Silme isteginin sonucu. */
sealed interface DeletionResult {
    /**
     * Her sey silindi.
     *
     * @param subscriptionRetained aktif abonelik kaydi korunduysa true --
     *        kullaniciya SOYLENIR, sessizce gecilmez.
     */
    data class Success(val subscriptionRetained: Boolean) : DeletionResult

    /** Sunucuya ulasilamadi ya da sunucu reddetti. Hicbir sey silinmedi. */
    data class Failed(val reason: String) : DeletionResult
}

/**
 * Kullanicinin tum verisini siler: once sunucudan, sonra cihazdan.
 *
 * SIRA NEDEN ONEMLI
 * -----------------
 * Once SUNUCU silinir. Cunku sunucuya silme istegi gondermek imzalanmis
 * bir cagri gerektirir ve o imza yerel anahtarla atilir. Yerel anahtari
 * once silersek sunucuya bir daha ulasamayiz -- kullanicinin verisi
 * sunucuda SONSUZA KADAR kalir, uygulama ise "sildim" der. Sessiz ve
 * geri donusu olmayan bir yalan olurdu.
 *
 * Sunucu adimi basarisiz olursa yerel veriye DOKUNULMAZ ve kullaniciya
 * hata gosterilir; boylece tekrar deneyebilir.
 */
class DataDeletionRepository(
    private val context: Context,
    private val apiClient: APIClient,
    private val secureTokenStore: SecureTokenStore,
    private val requestSigner: RequestSigner,
) {

    suspend fun deleteEverything(): DeletionResult {
        val response = try {
            apiClient.post<Map<String, String>, DeletionResponse>(
                path = "/v1/privacy/delete-me",
                // Bos govde: silinecek kimlik SUNUCUDA imzadan cikarilir.
                // Istemcinin gonderdigi bir deviceId'ye guvenmek, herkesin
                // baskasinin verisini sildirebilmesi demek olurdu.
                body = emptyMap(),
                requiresAuth = true,
            )
        } catch (e: APIClientException) {
            Log.e(TAG, "Sunucudaki veriler silinemedi.", e)
            return DeletionResult.Failed(e.message ?: "Sunucuya ulasilamadi")
        }

        if (!response.deleted) {
            return DeletionResult.Failed("Sunucu silme islemini onaylamadi")
        }

        wipeLocalData()
        return DeletionResult.Success(subscriptionRetained = response.subscriptionRetained)
    }

    /**
     * Cihazda kalan her seyi siler.
     *
     * Kapsam: arac profili, sarj gecmisi, oturum anahtarlari ve cihaz
     * kimligi (SecureTokenStore), son bilinen konum, istasyon onbellegi
     * ve donanim deposundaki imzalama anahtari.
     *
     * Cihaz kimligi ve imzalama anahtari da silinir: uygulama bir sonraki
     * aciliste YENI ve eskisiyle baglantisiz bir anonim kimlikle kaydolur.
     * Ayni kimlikle geri donmek, silme istegini anlamsiz kilardi.
     */
    private fun wipeLocalData() {
        runCatching { secureTokenStore.deleteAll() }
            .onFailure { Log.w(TAG, "Guvenli depo temizlenemedi.", it) }

        LOCAL_PREFS.forEach { name ->
            runCatching {
                context.getSharedPreferences(name, Context.MODE_PRIVATE)
                    .edit()
                    .clear()
                    .apply()
            }.onFailure { Log.w(TAG, "Tercihler temizlenemedi: $name", it) }
        }

        requestSigner.deleteKey()

        Log.i(TAG, "Cihazdaki veriler silindi.")
    }

    private companion object {
        /**
         * Sifrelenmemis yerel depolar.
         *
         * Adlar sahiplerinden ICERI AKTARILIYOR, burada tekrar
         * yazilmiyor: elle kopyalanan bir ad, sahibi degistiginde
         * sessizce yanlisa doner ve silme akisi o depoyu atlar --
         * kullaniciya "sildim" deyip veriyi birakmis oluruz.
         *
         * Yeni bir SharedPreferences eklendiginde BU LISTEYE de eklenmeli.
         */
        val LOCAL_PREFS = listOf(
            LAST_LOCATION_PREFS,   // son bilinen konum
            STATIONS_CACHE_PREFS,  // cevrimdisi fiyatlar
        )
    }
}
