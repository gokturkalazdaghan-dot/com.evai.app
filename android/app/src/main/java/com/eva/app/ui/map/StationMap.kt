// android/app/src/main/java/com/eva/app/ui/map/StationMap.kt
package com.eva.app.ui.map

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.eva.app.R
import com.eva.app.core.AppConfig
import com.eva.app.ui.stations.StationDto
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.MapStyleOptions
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapType
import com.google.maps.android.compose.MapUiSettings
import com.google.android.gms.maps.model.Dash
import com.google.android.gms.maps.model.Gap
import androidx.compose.ui.geometry.Offset
import com.eva.app.ui.stations.formatPricePerKwh
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.Polyline
import com.eva.app.route.ActiveRoute
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.maps.android.compose.rememberMarkerState

/** Haritanin varsayilan yakinlastirma seviyesi (tek nokta gosterilirken). */
private const val DEFAULT_ZOOM = 13f

/** Tum istasyonlari cerceveye sigdirirken birakilan kenar boslugu (px). */
private const val BOUNDS_PADDING_PX = 96

/** Rota cizgisi kalinligi (piksel). Direksiyondan bakilacagi icin kalin. */
private const val ROUTE_STROKE_WIDTH_PX = 14f

/**
 * Kus ucusu rota icin kesikli desen. Duz bir cizgiyi kesintisiz cizmek,
 * kullaniciya "yol bu" izlenimi verirdi -- oysa yalnizca yon gosteriyor.
 */
private val STRAIGHT_LINE_PATTERN = listOf(Dash(28f), Gap(18f))

/**
 * Yakindaki istasyonlari harita uzerinde gosterir.
 *
 * ANAHTAR YOKSA HIC CIZILMEZ. Google Maps, gecerli bir API anahtari
 * olmadan gri bir alan ve "for development purposes only" filigrani
 * gosterir; bu, temiz bir liste gorunumunden DAHA KOTU bir deneyimdir.
 * [AppConfig.isMapEnabled] false iken bu composable hicbir sey yayinlamaz
 * ve ekran liste gorunumuyle calismaya devam eder.
 *
 * Anahtari eklemek icin: android/local.properties -> MAPS_API_KEY=...
 * (bkz. android/RELEASE-CHECKLIST.md)
 */
@Composable
fun StationMap(
    userLat: Double,
    userLon: Double,
    stations: List<StationDto>,
    onStationSelected: (StationDto) -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 220.dp,
    /** Eva rota cizdiyse buradan gelir; null ise harita eskisi gibi calisir. */
    activeRoute: ActiveRoute? = null,
) {
    if (!AppConfig.isMapEnabled) return

    val context = LocalContext.current
    // Uygulamanin TEK temasi var ve o koyu (bkz. EvaTheme). Burada
    // isSystemInDarkTheme() kullanmak, telefonu acik moddaki bir
    // kullaniciya koyu arayuz icinde bembeyaz bir harita gosteriyordu.
    val isDark = true

    // Konum izni yoksa "mavi nokta" katmani ACILAMAZ; acilirsa Maps SDK
    // SecurityException firlatir. Bu yuzden izin durumu her cizimde
    // kontrol ediliyor.
    val hasLocationPermission = remember(context) {
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED
    }

    val userPosition = LatLng(userLat, userLon)
    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(userPosition, DEFAULT_ZOOM)
    }

    // En ucuz istasyonu vurgulamak icin: yalnizca fiyati BILINEN istasyonlar
    // arasindan secilir (null fiyat "ucuz" sayilmaz).
    val cheapestStationId = remember(stations) {
        stations
            .filter { it.pricePerKwh != null }
            .minByOrNull { it.pricePerKwh!! }
            ?.stationId
    }

    // Kamera: istasyonlar geldiginde hepsini + kullaniciyi cerceveye sigdir.
    // Konum degistiginde de yeniden hesaplanir.
    LaunchedEffect(userLat, userLon, stations, activeRoute) {
        // Rota varken cerceve ROTAYI kapsamali. Aksi halde uzaktaki baska
        // istasyonlar cerceveyi buyutup rotayi okunamaz hale getirir.
        if (activeRoute != null) {
            val routeBounds = LatLngBounds.builder()
                .apply { activeRoute.points.forEach { include(it) } }
                .build()
            runCatching {
                cameraPositionState.animate(
                    CameraUpdateFactory.newLatLngBounds(routeBounds, BOUNDS_PADDING_PX),
                )
            }
            return@LaunchedEffect
        }

        if (stations.isEmpty()) {
            cameraPositionState.animate(
                CameraUpdateFactory.newLatLngZoom(LatLng(userLat, userLon), DEFAULT_ZOOM),
            )
            return@LaunchedEffect
        }

        val bounds = LatLngBounds.builder()
            .include(LatLng(userLat, userLon))
            .apply { stations.forEach { include(LatLng(it.lat, it.lon)) } }
            .build()

        // newLatLngBounds, harita henuz olculmemisse hata verebilir; bu
        // durumda basit bir merkeze-tasi ile devam ediyoruz.
        runCatching {
            cameraPositionState.animate(
                CameraUpdateFactory.newLatLngBounds(bounds, BOUNDS_PADDING_PX),
            )
        }.onFailure {
            cameraPositionState.animate(
                CameraUpdateFactory.newLatLngZoom(LatLng(userLat, userLon), DEFAULT_ZOOM),
            )
        }
    }

    Surface(
        // height belirtilmemisse (Dp.Unspecified) cagiran taraf boyutu
        // kendisi veriyordur (orn. tam ekran harita); zorla yukseklik
        // dayatmak Modifier.height(Unspecified) hatasina yol acar.
        modifier = modifier
            .fillMaxWidth()
            .then(if (height == Dp.Unspecified) Modifier else Modifier.height(height)),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        GoogleMap(
            cameraPositionState = cameraPositionState,
            properties = MapProperties(
                mapType = MapType.NORMAL,
                isMyLocationEnabled = hasLocationPermission,
                // Koyu temada acik gri bir harita gozu yorar; uygulama
                // temasiyla tutarli olmasi icin koyu stil uygulaniyor.
                mapStyleOptions = if (isDark) {
                    MapStyleOptions.loadRawResourceStyle(context, R.raw.map_style_dark)
                } else {
                    null
                },
            ),
            uiSettings = MapUiSettings(
                zoomControlsEnabled = false,
                mapToolbarEnabled = false,
                // Harita kaydirilabilir bir Column icinde; dikey suruklemeyi
                // haritanin yutmasi listeyi kaydirilamaz yapardi. Kullanici
                // haritayi yine de yakinlastirip kaydirabilir, ancak tek
                // parmakla dikey surukleme SAYFAYA aittir.
                scrollGesturesEnabled = true,
                myLocationButtonEnabled = hasLocationPermission,
            ),
        ) {
            // Rota, isaretcilerden ONCE cizilir ki pinler cizginin ustunde
            // kalsin. Dongunun DISINDA: her istasyon icin bir kez
            // cizilmemeli.
            activeRoute?.let { route ->
                Polyline(
                    points = route.points,
                    color = MaterialTheme.colorScheme.primary,
                    width = ROUTE_STROKE_WIDTH_PX,
                    // Kus ucusu cizgi GERCEK ROTA DEGILDIR; kesikli cizmek
                    // bunu ekranda da gorunur kilar.
                    pattern = if (route.isRealRoad) null else STRAIGHT_LINE_PATTERN,
                )
            }

            stations.forEach { station ->
                val isCheapest = station.stationId == cheapestStationId

                Marker(
                    state = rememberMarkerState(
                        key = station.stationId,
                        position = LatLng(station.lat, station.lon),
                    ),
                    title = station.name,
                    snippet = buildSnippet(station),
                    // Fiyat pinin UZERINDE yazar: surucunun aradigi bilgi
                    // "hangisi kac para" ve bunun icin her pine tek tek
                    // dokunmasi gerekmemeli. En ucuz olan neon yesil.
                    icon = rememberPriceMarkerIcon(
                        priceLabel = station.pricePerKwh?.let {
                            formatPricePerKwh(it, station.currency)
                        },
                        isCheapest = isCheapest,
                    ),
                    // Rozetin kuyrugu istasyonun tam uzerine denk gelsin.
                    anchor = Offset(0.5f, 1f),
                    onInfoWindowClick = { onStationSelected(station) },
                )
            }
        }
    }
}

/** Isaretci balonunda gosterilen tek satirlik ozet. */
private fun buildSnippet(station: StationDto): String {
    val power = "${station.maxPowerKw.toInt()} kW"
    val price = station.pricePerKwh?.let {
        com.eva.app.ui.stations.formatPricePerKwh(it, station.currency)
    }
    return listOfNotNull(power, price).joinToString(" · ")
}
