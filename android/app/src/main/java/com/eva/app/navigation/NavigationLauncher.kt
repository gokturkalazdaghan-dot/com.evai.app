// android/app/src/main/java/com/eva/app/navigation/NavigationLauncher.kt
package com.eva.app.navigation

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import java.util.Locale

private const val TAG = "NavigationLauncher"

/** Navigasyon baslatma denemesinin sonucu. */
sealed class NavigationResult {
    /** Bir harita uygulamasi acildi. */
    data object Launched : NavigationResult()

    /** Cihazda harita uygulamasi yok. */
    data object NoMapsApp : NavigationResult()
}

/**
 * Yol tarifini TELEFONUN kendi harita uygulamasina devreder.
 *
 * NEDEN KENDI ROTAMIZI CIZMIYORUZ
 * -------------------------------
 * Uygulama icinde rota cizmek, Google Haritalar'in yillardir yaptigi isi
 * bastan yazmak demek: donus donus sesli yonlendirme, canli trafik,
 * yoldan cikinca yeniden hesaplama, serit bilgisi, hiz limiti. Bunlarin
 * hicbirini kendi cizdigimiz cizgi veremez ve surucu de zaten
 * navigasyon icin Haritalar'i acar.
 *
 * Bizim isimiz DOGRU ISTASYONU bulmak; oraya nasil gidilecegi telefonun
 * isi. Bu devir ayrica bakim yuku birakmaz: Haritalar guncellendikce
 * yonlendirme de iyilesir, biz hicbir sey yapmayiz.
 */
class NavigationLauncher(private val context: Context) {

    /**
     * Hedefe surus navigasyonu baslatir.
     *
     * @param label istasyon adi; harita uygulamasinda gorunur.
     */
    fun navigateTo(lat: Double, lon: Double, label: String? = null): NavigationResult {
        // google.navigation: dogrudan SURUS MODUNDA baslatir -- kullanici
        // ayrica "yol tarifi al" demek zorunda kalmaz.
        val navigationUri = Uri.parse(
            String.format(Locale.US, "google.navigation:q=%f,%f&mode=d", lat, lon),
        )

        val googleMapsIntent = Intent(Intent.ACTION_VIEW, navigationUri).apply {
            setPackage("com.google.android.apps.maps")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        if (tryStart(googleMapsIntent)) return NavigationResult.Launched

        // Google Haritalar yoksa (bazi cihazlarda kurulu degil, Cin
        // pazari, kullanici kaldirmis olabilir) standart geo: semasiyla
        // HERHANGI bir harita uygulamasina duseriz.
        val geoUri = Uri.parse(
            buildString {
                append(String.format(Locale.US, "geo:%f,%f", lat, lon))
                append(String.format(Locale.US, "?q=%f,%f", lat, lon))
                if (!label.isNullOrBlank()) {
                    append("(")
                    append(Uri.encode(label))
                    append(")")
                }
            },
        )

        val genericIntent = Intent(Intent.ACTION_VIEW, geoUri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        if (tryStart(genericIntent)) return NavigationResult.Launched

        Log.w(TAG, "Cihazda harita uygulamasi bulunamadi.")
        return NavigationResult.NoMapsApp
    }

    private fun tryStart(intent: Intent): Boolean = try {
        context.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: Exception) {
        Log.w(TAG, "Harita uygulamasi baslatilamadi.", e)
        false
    }
}
