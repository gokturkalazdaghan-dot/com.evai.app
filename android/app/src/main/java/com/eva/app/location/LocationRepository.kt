// android/app/src/main/java/com/eva/app/location/LocationRepository.kt
package com.eva.app.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.LocationManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

private const val TAG = "LocationRepository"

/**
 * Uygulamanin kullandigi konum.
 *
 * [isPrecise] false ise deger cihazdan DEGIL, [EvaLocation.FALLBACK]
 * varsayilanindan gelir. UI bunu kullaniciya belli etmelidir; "yakinindaki
 * istasyonlar" baslikli bir liste aslinda baska bir sehri gosteriyorsa bu
 * sessiz bir yanlislik olur.
 */
data class EvaLocation(
    val lat: Double,
    val lon: Double,
    val label: String,
    val isPrecise: Boolean,
) {
    companion object {
        /**
         * SABIT KODLANMIS BIR SEHIR YEDEGI YOKTUR -- NEDEN
         * ------------------------------------------------
         * Burada bir zamanlar San Francisco vardi. Sonuc: Turkiye'deki
         * bir kullanici uygulamayi acinca "yakinindaki istasyonlar"
         * basligi altinda BASKA BIR KITADAKI istasyonlari ve fiyatlari
         * goruyordu. Bu, fiyat uydurmakla ayni kategoride bir hata --
         * yalnizca daha az fark ediliyor.
         *
         * Konum bilinmiyorsa deger `null`dur ve UI izin ister. Bilinmeyen
         * bir konum icin rastgele bir sehir gostermek yerine, kullaniciya
         * neyin eksik oldugunu soylemek dogru.
         */
        const val UNKNOWN_LABEL = "Konum bekleniyor"
    }
}

/**
 * Cihazin gercek konumunu saglar.
 *
 * Tasarim notlari:
 *  - Izin YOKSA hata firlatmaz; [EvaLocation.FALLBACK] doner. Boylece
 *    izni reddeden kullanici bos bir ekran yerine calisan bir uygulama
 *    gorur, ama [EvaLocation.isPrecise] false oldugu icin UI durumu
 *    durustce belirtebilir.
 *  - Once son bilinen konumu dener (aninda doner), yoksa taze bir fix ister.
 *  - Ters cografi kodlama (Geocoder) basarisiz olursa koordinatin kendisi
 *    etiket olarak kullanilir -- asla yanlis bir sehir adi gosterilmez.
 */
@Singleton
class LocationRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val fusedClient = LocationServices.getFusedLocationProviderClient(context)

    // DIKKAT: bu alan _location'DAN ONCE tanimli olmali. Kotlin
    // ozellikleri yazilma sirasiyla baslatir; _location initializer'i
    // loadLastKnown() -> prefs zincirini cagiriyor ve prefs henuz
    // baslatilmamis olursa NullPointerException ile cokuyordu.
    private val prefs =
        context.getSharedPreferences(LAST_LOCATION_PREFS, Context.MODE_PRIVATE)

    /**
     * Mevcut konum. `null` = HENUZ BILINMIYOR.
     *
     * Uygulama acilirken diskteki son GERCEK konum yuklenir: kullanici
     * her acilista bos ekran gormemeli, ama gordugu sey de uydurma bir
     * sehir olmamali -- kendi en son bulundugu yer.
     */
    private val _location = MutableStateFlow(loadLastKnown())
    val location: StateFlow<EvaLocation?> = _location.asStateFlow()

    /**
     * Konumun neden bilinmedigi.
     *
     * UC AYRI DURUM -- NEDEN ONEMLI
     * -----------------------------
     * "Izin yok", "fix bekleniyor" ve "fix alinamadi" farkli seylerdir.
     * Ilk surumde ucu de ayni ekrani gosteriyordu: izni ZATEN VERMIS bir
     * kullaniciya "konum iznini ver" butonu cikiyordu -- basmasi hicbir
     * ise yaramayan, cikissiz bir ekran.
     */
    private val _status = MutableStateFlow(
        if (loadLastKnown() != null) LocationStatus.Available else LocationStatus.Resolving,
    )
    val status: StateFlow<LocationStatus> = _status.asStateFlow()

    fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED

        val coarse = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED

        return fine || coarse
    }

    /**
     * Konumu tazeler.
     *
     * Izin yoksa ya da fix alinamazsa SON BILINEN gercek konum korunur;
     * o da yoksa null kalir ve UI izin ister.
     */
    suspend fun refresh(): EvaLocation? {
        if (!hasLocationPermission()) {
            Log.i(TAG, "Konum izni yok.")
            _status.value = LocationStatus.PermissionRequired
            return _location.value
        }

        _status.value = if (_location.value != null) {
            LocationStatus.Available
        } else {
            LocationStatus.Resolving
        }

        val coordinates = runCatching { lastKnownOrFresh() }
            .onFailure { Log.w(TAG, "Konum alinamadi.", it) }
            .getOrNull()

        if (coordinates == null) {
            Log.w(TAG, "Cihaz bir konum fix'i uretmedi; son bilinen korunuyor.")
            // Elimizde eski bir konum varsa onunla devam: fix alinamamasi
            // (kapali alan, GPS sogumasi) gecicidir ve kullaniciyi bos
            // ekranda birakmayi gerektirmez.
            _status.value = if (_location.value != null) {
                LocationStatus.Available
            } else {
                LocationStatus.Unavailable
            }
            return _location.value
        }

        val (lat, lon) = coordinates
        val resolved = EvaLocation(
            lat = lat,
            lon = lon,
            label = reverseGeocode(lat, lon),
            isPrecise = true,
        )
        _location.value = resolved
        _status.value = LocationStatus.Available
        saveLastKnown(resolved)
        return resolved
    }

    /**
     * Once cihazin son bilinen konumunu dener, yoksa taze fix ister.
     *
     * TUM ZINCIR ZAMAN ASIMLI: `getCurrentLocation` fix uretemedigi
     * durumlarda (kapali alan, GPS sogumus, ag konumu kapali) HIC
     * donmeyebilir. Zaman asimi olmadan ekran "Konumun alınıyor…"
     * yazisinda sonsuza kadar asili kaliyordu -- kullanici icin
     * uygulamanin donmus olmasindan farksiz.
     */
    private suspend fun lastKnownOrFresh(): Pair<Double, Double>? =
        withTimeoutOrNull(OVERALL_TIMEOUT_MS) {
            lastLocation()
                ?: currentLocation()
                // UCUNCU YOL: platformun kendi LocationManager'i.
                //
                // Neden gerekli: bazi ROM'lar (MIUI/HyperOS, EMUI) Play
                // Services konum servisini arka planda kisitliyor ve
                // fusedClient bos donuyor -- oysa sistemin kendi
                // saglayicilarinda gecerli bir fix duruyor olabiliyor.
                // Bu adim olmadan uygulama o cihazlarda hic konum
                // bulamiyordu.
                ?: platformLastKnown()
        }

    /**
     * Sistemin gps/network saglayicilarindaki EN TAZE son bilinen konum.
     *
     * Cok eski bir fix kabul edilmez: kullanici baska bir sehirde
     * olabilir ve eski koordinat, cozmeye calistigimiz "yanlis sehrin
     * istasyonlari" hatasini geri getirirdi.
     */
    @Suppress("MissingPermission")
    private fun platformLastKnown(): Pair<Double, Double>? {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return null

        val cutoff = System.currentTimeMillis() - PLATFORM_FIX_MAX_AGE_MS

        return runCatching {
            manager.getProviders(true)
                .mapNotNull { provider -> manager.getLastKnownLocation(provider) }
                .filter { it.time >= cutoff }
                .maxByOrNull { it.time }
                ?.let { it.latitude to it.longitude }
        }.onFailure { Log.w(TAG, "Platform konum saglayicisi okunamadi.", it) }
            .getOrNull()
    }

    @Suppress("MissingPermission") // hasLocationPermission() ile dogrulandi
    private suspend fun lastLocation(): Pair<Double, Double>? =
        suspendCancellableCoroutine { continuation ->
            fusedClient.lastLocation
                .addOnSuccessListener { location ->
                    continuation.resume(location?.let { it.latitude to it.longitude })
                }
                .addOnFailureListener { continuation.resume(null) }
        }

    @Suppress("MissingPermission")
    private suspend fun currentLocation(): Pair<Double, Double>? =
        suspendCancellableCoroutine { continuation ->
            val request = CurrentLocationRequest.Builder()
                // BALANCED: sarj istasyonu araminda sokak seviyesi hassasiyet
                // yeterli; HIGH_ACCURACY gereksiz pil tuketir. Ayrica
                // yalnizca COARSE izni verilmis cihazlarda HIGH_ACCURACY
                // istegi bos donebiliyor.
                .setPriority(Priority.PRIORITY_BALANCED_POWER_ACCURACY)
                // 10 dakikalik bir fix, sarj istasyonu aramak icin FAZLASIYLA
                // taze. Onceki 60 sn cok darda: cihazin elindeki gecerli
                // konumu reddedip yeni fix beklemeye zorluyordu.
                .setMaxUpdateAgeMillis(10 * 60_000)
                // Vazgecme suresi: bu olmadan cagri sinirsiz beklerdi.
                .setDurationMillis(FIX_TIMEOUT_MS)
                .build()

            fusedClient.getCurrentLocation(request, null)
                .addOnSuccessListener { location ->
                    continuation.resume(location?.let { it.latitude to it.longitude })
                }
                .addOnFailureListener { continuation.resume(null) }
        }

    /**
     * Koordinati "Kadikoy, Istanbul" gibi okunabilir bir etikete cevirir.
     * Basarisiz olursa koordinatin kendisini doner -- UYDURULMUS bir yer adi
     * gostermek, koordinat gostermekten daha kotudur.
     */
    private suspend fun reverseGeocode(lat: Double, lon: Double): String =
        withContext(Dispatchers.IO) {
            val coordinateLabel = String.format(Locale.US, "%.4f, %.4f", lat, lon)

            if (!Geocoder.isPresent()) return@withContext coordinateLabel

            runCatching {
                @Suppress("DEPRECATION") // API 33+ async varianti minSdk 26'da yok
                val addresses = Geocoder(context, Locale.getDefault())
                    .getFromLocation(lat, lon, 1)

                val address = addresses?.firstOrNull() ?: return@runCatching coordinateLabel

                listOfNotNull(
                    address.subAdminArea ?: address.locality,
                    address.adminArea?.takeIf { it != address.locality },
                )
                    .distinct()
                    .joinToString(", ")
                    .ifBlank { coordinateLabel }
            }.getOrElse {
                Log.w(TAG, "Ters cografi kodlama basarisiz.", it)
                coordinateLabel
            }
        }

    // ------------------------------------------------------------------
    // Son bilinen konumun kalici saklanmasi
    // ------------------------------------------------------------------
    //
    // Neden: uygulama her acildiginda konum fix'i birkac saniye surer.
    // O aralikta ekranin bos ya da "bilinmiyor" kalmasi, kullaniciya
    // uygulama bozuk hissi verir. Son GERCEK konum gosterilir, taze fix
    // gelince guncellenir.
    //
    // Sifreleme yok: bu veri zaten cihazin kendi konum servisinden
    // geliyor ve sehir olceginde. Sir degil, rahatlik verisi.

    private fun loadLastKnown(): EvaLocation? {
        val lat = prefs.getString(KEY_LAT, null)?.toDoubleOrNull() ?: return null
        val lon = prefs.getString(KEY_LON, null)?.toDoubleOrNull() ?: return null
        val label = prefs.getString(KEY_LABEL, null) ?: return null

        return EvaLocation(
            lat = lat,
            lon = lon,
            label = label,
            // isPrecise = false: bu diskten gelen ESKI bir okuma. Kullanici
            // baska bir sehre tasinmis olabilir; UI "guncelleniyor"
            // diyebilsin diye kesin isaretlenmiyor.
            isPrecise = false,
        )
    }

    private fun saveLastKnown(location: EvaLocation) {
        prefs.edit()
            .putString(KEY_LAT, location.lat.toString())
            .putString(KEY_LON, location.lon.toString())
            .putString(KEY_LABEL, location.label)
            .apply()
    }
}

/** Konumun neden bilinmedigini ayirt eder; her biri FARKLI bir ekran. */
enum class LocationStatus {
    /** Izin var, fix bekleniyor. Yukleme gostergesi. */
    Resolving,

    /** Konum biliniyor (taze ya da diskten). */
    Available,

    /** Izin yok. Izin isteme ekrani. */
    PermissionRequired,

    /** Izin var ama cihaz fix uretemedi. Tekrar dene ekrani. */
    Unavailable,
}

/**
 * Taze fix icin beklenecek en uzun sure.
 *
 * 12 sn: acik havada bir fix genelde 1-3 saniyede gelir. Bundan
 * uzun beklemek, kapali alanda oturan bir kullaniciyi bosuna bekletir.
 */
private const val FIX_TIMEOUT_MS = 12_000L

/** Tum zincir (son bilinen + taze fix) icin ust sinir. */
private const val OVERALL_TIMEOUT_MS = 15_000L

/**
 * Platform saglayicisindan kabul edilecek en eski fix.
 *
 * 2 saat: bu bir SON CARE. Daha eskisi, kullanicinin bu sure icinde
 * sehir degistirmis olma ihtimalini ciddi olcude artirir.
 */
private const val PLATFORM_FIX_MAX_AGE_MS = 2 * 60 * 60 * 1000L

private const val LAST_LOCATION_PREFS = "eva.location"
private const val KEY_LAT = "lastLat"
private const val KEY_LON = "lastLon"
private const val KEY_LABEL = "lastLabel"
