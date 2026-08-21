// android/app/src/main/java/com/eva/app/ui/dashboard/DashboardScreen.kt
package com.eva.app.ui.dashboard

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.EvStation
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import android.Manifest
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.eva.app.R
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import com.eva.app.ui.theme.EvaLogo
import com.eva.app.ui.map.StationMap
import com.eva.app.ui.vehicle.VehicleMonitorViewModel
import com.eva.app.ui.dashboard.components.BatteryHealthCard
import com.eva.app.ui.dashboard.components.VehicleTurntable
import com.eva.app.ui.dashboard.components.CheapestNearbyCard
import com.eva.app.ui.dashboard.components.EvStatusBar
import com.eva.app.ui.stations.StationDto
import com.eva.app.ui.stations.connectorDisplayLabel
import com.eva.app.ui.stations.formatDistance
import com.eva.app.ui.stations.OfflineBanner
import com.eva.app.ui.stations.PriceTrendVisual
import com.eva.app.ui.stations.formatPricePerKwh
import kotlin.math.abs
import kotlin.math.roundToInt
import com.eva.app.ui.stations.unitSystemFor
import com.eva.app.ui.vehicle.VehicleOnboardingDialog
import com.eva.app.ui.vehicle.VehicleOnboardingViewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.eva.app.ui.stations.StationFilterSheet

/**
 * Referans görseldeki (image_3.png) ana dashboard ekranı: üstte konum
 * başlığı, harita alanı (bkz. aşağıdaki MapPlaceholder notu), pil sağlığı
 * + en ucuz istasyon kartları, EV durum çubuğu, en altta sesli asistan
 * paneli.
 *
 * HARİTA HAKKINDA DÜRÜSTLÜK NOTU: Görseldeki koyu temalı, özel pin'li
 * harita gerçek bir harita SDK'sı (Google Maps Compose ya da Mapbox)
 * gerektirir — bu, build.gradle.kts'e yeni bir bağımlılık + bir API
 * anahtarı (Google Cloud Console'dan) + `local.properties`'e eklenecek
 * bir alan gerektiren AYRI bir kurulum adımıdır. Onu sahte bir görselle
 * "varmış gibi" göstermek yerine, burada haritanın YERİNİ TUTAN ama
 * gerçek istasyon verisini (isim, mesafe, fiyat) liste halinde gösteren
 * bir `MapPlaceholderWithStationList` var. Harita SDK kurulumunu
 * istediğinde ayrı bir adım olarak yapacağız.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalPermissionsApi::class)
@Composable
fun DashboardScreen(
    dashboardViewModel: DashboardViewModel = hiltViewModel(),
    onboardingViewModel: VehicleOnboardingViewModel = hiltViewModel(),
    vehicleMonitorViewModel: VehicleMonitorViewModel = hiltViewModel(),
    currentLat: Double,
    currentLon: Double,
    locationLabel: String,
    /** Konum diskten okundu / tazelenemedi mi? Kullaniciya soylenmeli. */
    isLocationStale: Boolean,
    onStationSelected: (StationDto) -> Unit = {},
    onSettingsClick: () -> Unit = {},
) {
    val uiState by dashboardViewModel.uiState.collectAsState()
    val liveTelemetry by vehicleMonitorViewModel.telemetry.collectAsState()

    var showFilters by rememberSaveable { mutableStateOf(false) }

    // Arac profili VEYA konum degistiginde yeniden yukle. Daha once yalnizca
    // arac degisimini dinliyordu; konum izni verilip gercek fix alindiginda
    // panel hala varsayilan sehrin istasyonlarini gosteriyordu.
    LaunchedEffect(uiState.vehicle, currentLat, currentLon) {
        if (uiState.vehicle != null) {
            dashboardViewModel.loadNearbyStations(currentLat, currentLon)
        }
    }

    if (uiState.vehicle == null) {
        VehicleOnboardingDialog(
            viewModel = onboardingViewModel,
            onCompleted = { /* uiState.vehicle değişince LaunchedEffect otomatik tetiklenir */ },
        )
        // Araç kaydedilene kadar dashboard'un geri kalanını göstermiyoruz —
        // altındaki içerik anlamsız (soketsiz filtre, aracsız menzil vb.)
        // olurdu.
        return
    }

    // NOT: Sesli asistan paneli daha once Scaffold'in bottomBar'iydi ve
    // ekranin yaklasik %40'ini kapliyordu -- kaydirilabilir icerigin ustune
    // binip pil/fiyat kartlarini ORTUYORDU. Artik alt navigasyonda kendi
    // "Eva" sekmesi oldugu icin burada sabit bir cubuk olarak durmasi hem
    // gereksiz hem zararliydi; icerigin sonuna, kaydirmaya dahil edildi.
    Scaffold { paddingValues ->
        // ASAGI CEKEREK YENILE. Onceden hicbir yenileme yolu yoktu:
        // fiyatlar yalnizca ekran ilk acildiginda yukleniyor, ajan arka
        // planda yeni tarife yazsa bile kullanici eski degeri goruyordu.
        PullToRefreshBox(
            isRefreshing = uiState.isRefreshing,
            onRefresh = { dashboardViewModel.refresh(currentLat, currentLon) },
            modifier = Modifier.padding(paddingValues),
        ) {
        // KAYDIRILABILIR: icerik buyudugunde (ornegin 5 istasyon listelenince)
        // kaydirma olmadan Column cocuklarini SIKISTIRIYORDU -- pil sagligi
        // halkasi elipse donuyor, EV durum cubugu ekrandan tasip
        // kayboluyordu. Kucuk ekranlarda ve buyuk yazi tipi ayarlarinda da
        // ayni sorun yasanir.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            DashboardHeader(
                locationLabel = locationLabel,
                isLocationStale = isLocationStale,
                onFilterClick = { showFilters = true },
                onSettingsClick = onSettingsClick,
            )

            // Cevrimdisiyken fiyatlar YINE gosterilir; banner bunlarin
            // eski oldugunu soyler.
            uiState.offlineSinceEpochMs?.let { fetchedAt ->
                OfflineBanner(
                    fetchedAtEpochMs = fetchedAt,
                    onRetry = { dashboardViewModel.refresh(currentLat, currentLon) },
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            // Arac gorseli: sag/sola kaydirilarak incelenebilir.
            VehicleTurntable(modifier = Modifier.padding(top = 12.dp))

            // Harita YALNIZCA gecerli bir MAPS_API_KEY varken cizilir;
            // yoksa bu composable hicbir sey yayinlamaz (bkz. StationMap).
            StationMap(
                userLat = currentLat,
                userLon = currentLon,
                stations = uiState.nearbyStations,
                onStationSelected = onStationSelected,
                modifier = Modifier.padding(top = 12.dp),
            )

            // BATARYA IZLEME: kullanicinin sarj yuzdesini elle girmesi
            // gerekmez; telemetri katmani mevcut en iyi kaynagi secer ve
            // %50/%30 esiklerinde telefonun bildirim sistemi uyarir.
            LaunchedEffect(Unit) {
                vehicleMonitorViewModel.start {
                    uiState.nearbyStations
                        .filter { it.pricePerKwh != null }
                        .minByOrNull { it.distanceMeters }
                }
            }

            NearbyStationsSection(
                stations = uiState.nearbyStations,
                isLoading = uiState.isLoadingStations,
                errorMessage = uiState.stationsErrorMessage,
                onRetry = { dashboardViewModel.loadNearbyStations(currentLat, currentLon) },
                onStationSelected = onStationSelected,
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                uiState.vehicle?.let { vehicle ->
                    // Pil sağlığı, VehicleProfile'da henüz ayrı bir alan
                    // olarak yok (bkz. BatteryHealthCard.kt dosya başı
                    // dürüstlük notu) — burada onboarding'de girilen şarj
                    // yüzdesinden BAĞIMSIZ, sabit bir örnek değer (92)
                    // kullanılıyor. Gerçek "batarya sağlığı" (state of
                    // health) verisi bir araç API'sinden gelmeden bu alan
                    // kavramsal bir yer tutucudur.
                    BatteryHealthCard(
                        healthPercent = 92,
                        modifier = Modifier.weight(1f),
                    )
                }
                CheapestNearbyCard(
                    cheapestStation = uiState.cheapestStation,
                    // Kullanicinin yasadigi ulkeye gore; daha once IMPERIAL
                    // sabit kodluydu ve StationsScreen ile celisiyordu.
                    unitSystem = unitSystemFor(),
                    modifier = Modifier.weight(1f),
                )
            }

            uiState.vehicle?.let { vehicle ->
                EvStatusBar(
                    vehicle = vehicle,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    // Telemetri varsa sarj ve menzil ANLIK guncellenir;
                    // yoksa profildeki son deger kullanilir.
                    livePercent = liveTelemetry?.takeIf { it.hasUsableBattery }?.batteryPercent,
                )
            }


            Spacer(Modifier.height(24.dp))
            }
        }
    }

    if (showFilters) {
        StationFilterSheet(
            current = uiState.filters,
            onDismiss = { showFilters = false },
            onApply = { filters ->
                dashboardViewModel.applyFilters(filters, currentLat, currentLon)
                showFilters = false
            },
        )
    }
}

@Composable
private fun DashboardHeader(
    locationLabel: String,
    isLocationStale: Boolean,
    onFilterClick: () -> Unit,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(top = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EvaLogo(size = 38.dp, modifier = Modifier.padding(end = 10.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.dashboard_title), style = MaterialTheme.typography.headlineSmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.dashboard_near, locationLabel),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Icon(
                    Icons.Filled.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.padding(start = 2.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }

            // ESKI KONUMU ESKI DIYE ISARETLE
            // ------------------------------
            // Cihaz taze bir fix uretemediginde (kapali alan, GPS
            // sogumasi, ROM kisitlamasi) son bilinen konum gosterilmeye
            // devam eder -- bos ekran daha kotu olurdu. Ama bunu SESSIZCE
            // yapmak, kullaniciya bulunmadigi bir yerin istasyonlarini
            // guncelmis gibi sunmak demek. Fiyat uydurmakla ayni kategori.
            if (isLocationStale) {
                Text(
                    stringResource(R.string.dashboard_location_stale),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        IconButton(
            onClick = onFilterClick,
            modifier = Modifier
                .size(40.dp)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape),
        ) {
            Icon(Icons.Filled.Tune, contentDescription = stringResource(R.string.dashboard_filter))
        }
        IconButton(
            onClick = onSettingsClick,
            modifier = Modifier
                .padding(start = 6.dp)
                .size(40.dp)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape),
        ) {
            Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.settings_title))
        }
    }
}

@Composable
private fun NearbyStationsSection(
    stations: List<StationDto>,
    isLoading: Boolean,
    errorMessage: String?,
    onRetry: () -> Unit,
    onStationSelected: (StationDto) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.nearby_stations),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                if (!isLoading && errorMessage == null && stations.isNotEmpty()) {
                    Text(
                        "${stations.size}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            when {
                isLoading -> StationsPlaceholder(
                    icon = null,
                    message = stringResource(R.string.stations_searching),
                )

                errorMessage != null -> Column {
                    StationsPlaceholder(
                        icon = Icons.Filled.ErrorOutline,
                        message = errorMessage,
                        tint = MaterialTheme.colorScheme.error,
                    )
                    TextButton(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
                        Text("Tekrar dene")
                    }
                }

                stations.isEmpty() -> StationsPlaceholder(
                    icon = Icons.Filled.SearchOff,
                    message = stringResource(R.string.stations_none_for_vehicle),
                )

                else -> stations.take(5).forEachIndexed { index, station ->
                    if (index > 0) {
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 10.dp),
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                    }
                    StationRow(station, onClick = { onStationSelected(station) })
                }
            }
        }
    }
}

/** Yukleniyor / hata / bos durumlari icin ortak, sade bir yer tutucu. */
@Composable
private fun StationsPlaceholder(
    icon: androidx.compose.ui.graphics.vector.ImageVector?,
    message: String,
    tint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        } else {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        }
        Spacer(Modifier.width(12.dp))
        Text(message, style = MaterialTheme.typography.bodyMedium, color = tint)
    }
}

/**
 * Tek bir istasyon satiri. Daha once yalnizca "ad + fiyat" gosteriliyordu ve
 * ustunde kullaniciya yonelik olmayan bir gelistirici notu ("Harita
 * entegrasyonu henuz eklenmedi") vardi. Surucunun karar verebilmesi icin
 * mesafe, guc ve soket tipi de gerekli.
 */
@Composable
private fun StationRow(station: StationDto, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(40.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Filled.EvStation,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(22.dp),
                )
            }
        }

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                station.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            Text(
                buildStationSubtitle(station),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.width(8.dp))

        Column(horizontalAlignment = Alignment.End) {
            Text(
                // Fiyat bilinmiyorsa 0 ya da uydurma bir deger DEGIL, acik bir
                // "bilinmiyor" isareti gosterilir.
                station.pricePerKwh?.let { formatPricePerKwh(it, station.currency) } ?: "—",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )

            // Fiyat yonu: yalnizca GERCEKTEN hareket ettiyse gosterilir.
            // Yukselen kirmizi, dusen neon yesil -- surucu icin "dusen
            // fiyat" iyi haberdir.
            PriceTrendVisual.from(station.priceTrend)?.let { visual ->
                val percent = station.priceChangePercent
                Text(
                    text = if (percent != null) {
                        "${visual.arrow} %${abs(percent).roundToInt()}"
                    } else {
                        visual.arrow
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (visual == PriceTrendVisual.FALLING) {
                        MaterialTheme.colorScheme.tertiary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
            }
        }
    }
}

/** "263 m - 250 kW - CCS, Tesla" seklinde tek satirlik ozet. */
private fun buildStationSubtitle(station: StationDto): String {
    val parts = mutableListOf(
        formatDistance(station.distanceMeters, unitSystemFor()),
        "${station.maxPowerKw.toInt()} kW",
    )
    val connectors = station.connectors
        .map { connectorDisplayLabel(it.connectorType) }
        .distinct()
    if (connectors.isNotEmpty()) {
        parts += connectors.joinToString(", ")
    }
    return parts.joinToString(" · ")
}
