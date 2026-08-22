// android/app/src/main/java/com/eva/app/MainActivity.kt
package com.eva.app

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import android.graphics.Color
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.DirectionsCarFilled
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.eva.app.location.EvaLocation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.annotation.StringRes
import androidx.compose.ui.res.stringResource
import com.eva.app.R
import com.eva.app.location.LocationStatus
import com.eva.app.location.LocationViewModel
import com.eva.app.ui.location.LocationRequiredScreen
import com.eva.app.ui.dashboard.DashboardScreen
import com.eva.app.ui.hud.VehicleHudScreen
import com.eva.app.ui.settings.SettingsScreen
import com.eva.app.ui.stations.StationDetailScreen
import com.eva.app.ui.stations.StationDto
import com.eva.app.ui.stations.StationsScreen
import com.eva.app.ui.stations.StationsViewModel
import com.eva.app.ui.subscription.SubscriptionScreen
import com.eva.app.ui.subscription.SubscriptionViewModel
import com.eva.app.ui.theme.EvaTheme
import com.eva.app.ui.vehicle.VehicleOnboardingViewModel
import com.eva.app.ui.vehicle.VehicleScreen
import com.eva.app.ui.vehicle.VehicleMonitorViewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.PermissionStatus
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.serialization.json.Json

/**
 * Uygulamanın tek Activity'si — Jetpack Compose Navigation ile ekranlar
 * arası geçiş yönetiliyor. @AndroidEntryPoint, Hilt'in bu Activity'ye ve
 * içindeki hiltViewModel() çağrılarına inject yapabilmesi için gerekli.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Uygulama koyu tema sabitli; varsayilan enableEdgeToEdge() ise
        // sistem temasini izleyip acik moddaki telefonlarda BEYAZ bir
        // gezinme cubugu birakiyordu.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )

        setContent {
            EvaTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    EvaApp()
                }
            }
        }
    }
}

private object EvaRoutes {
    const val DASHBOARD = "dashboard"
    const val STATIONS_LIST = "stations_list"
    const val VEHICLE = "vehicle"
    const val SUBSCRIPTION = "subscription"
    const val STATION_DETAIL = "station_detail"
    const val SETTINGS = "settings"
    const val VEHICLE_HUD = "vehicle_hud"
}

/**
 * Alt navigasyon sekmeleri.
 *
 * NOT: sesli asistan üründen çıkarıldı; kodu _archive/voice-assistant
 * altında duruyor.
 */
private enum class EvaTab(
    val route: String,
    /** Ceviri kaynagi; sabit metin DEGIL -- cihaz diline gore cozulur. */
    @StringRes val labelRes: Int,
    val icon: ImageVector,
) {
    DASHBOARD(EvaRoutes.DASHBOARD, R.string.tab_dashboard, Icons.Filled.Dashboard),
    STATIONS(EvaRoutes.STATIONS_LIST, R.string.tab_stations, Icons.Filled.Bolt),
    // Arac bilgileri ve canli batarya durumu.
    VEHICLE(EvaRoutes.VEHICLE, R.string.tab_vehicle, Icons.Filled.DirectionsCarFilled),
    PREMIUM(EvaRoutes.SUBSCRIPTION, R.string.tab_premium, Icons.Filled.WorkspacePremium),
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
private fun EvaApp(navController: NavHostController = rememberNavController()) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    // Konum: tum sekmeler icin TEK kaynak. Activity kapsaminda tek ornek
    // oldugu icin Panel, Istasyonlar ve Eva ayni koordinati gorur.
    val locationViewModel: LocationViewModel = hiltViewModel()
    val location by locationViewModel.location.collectAsStateWithLifecycle()
    val locationStatus by locationViewModel.status.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    // Detay ekranina gecerken secilen istasyon. StationDto @Serializable
    // oldugu icin JSON olarak saklaniyor; boylece ekran dondurulse bile
    // secim kaybolmaz (rememberSaveable, Bundle'a yalnizca ilkel tipleri
    // ve Saver'i olan nesneleri yazabilir).
    var selectedStation by rememberSaveable(stateSaver = StationDtoSaver) {
        mutableStateOf<StationDto?>(null)
    }

    // Sarj istasyonu bulucusu konum OLMADAN calisamaz: "yakinindaki
    // istasyonlar" sorusunun cevabi konuma baglidir. Izin reddedilirse
    // uygulama uydurma bir sehre dusmez, LocationRequiredScreen gosterir.
    // NEDEN IKI IZIN BIRDEN ISTENIYOR
    // -------------------------------
    // Yalnizca COARSE istendiginde Android konumu ~1-3 km'lik bir
    // izgaraya yuvarlar VE ayni yuvarlanmis degeri SAATLERCE yeniden
    // kullanir. Olculdu: cihazdaki onbellek fix'i 1 saat 57 dakika
    // eskiydi ve tazelenmiyordu -- harita bu yuzden guncellenmiyordu.
    // Android 12'den itibaren COARSE istenmisse FINE'a hic yukselinemez,
    // bu yuzden ikisi BIRLIKTE istenmeli.
    //
    // Kullanici yine "Yaklasik" secebilir; uygulama o durumda da calisir,
    // yalnizca tazelik beklentisi dusuk tutulur (bkz. LocationRepository).
    val locationPermission = rememberMultiplePermissionsState(
        listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        ),
    )

    // Ikisinden HERHANGI biri yeterli: kullanici yalnizca "Yaklasik"
    // verdiginde allPermissionsGranted false olur ama uygulama calisabilir.
    val hasLocationPermission = locationPermission.permissions.any { it.status.isGranted }

    // Izin bir kez istendi mi? "Bir daha sorma" tespiti icin gerekli:
    // ilk acilista da shouldShowRationale false olur ve bu bayrak
    // olmadan kullaniciyi hemen "ayarlara git" ekranina yollardik.
    var hasRequestedLocationOnce by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        // Kosul "hicbir izin yok" DEGIL "kesin konum yok": yalnizca
        // "Yaklasik" verilmis bir kullaniciya yukseltme teklifi hic
        // gosterilmezse, uygulama o cihazda kalici olarak saatlik
        // tazelenen, ~1-3 km yuvarlanmis bir konuma mahkum olur.
        // Diyalogun tekrarini Android kendisi sinirlar.
        if (!locationPermission.permissions.all { it.status.isGranted }) {
            hasRequestedLocationOnce = true
            locationPermission.launchMultiplePermissionRequest()
        }
    }

    // KONUM NEDEN DONGUDE TAZELENIYOR
    // -------------------------------
    // Onceki surumde konum YALNIZCA izin verildigi anda bir kez
    // okunuyordu. Sonuc: kullanici uygulamayi acik tutup yola cikinca
    // harita hic guncellenmiyordu -- oturum boyunca ayni koordinatta
    // kaliyordu. Sarj istasyonu bulmak dogrudan konuma bagli oldugu icin
    // bu, uygulamanin ana isini bozan bir davranisti.
    //
    // repeatOnLifecycle(RESUMED): uygulama one gelince hemen tazeler ve
    // acik kaldigi surece tekrarlar; arka plana dusunce KENDILIGINDEN
    // durur, boylece arka planda pil ve konum tuketmeyiz.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner, hasLocationPermission) {
        if (!hasLocationPermission) return@LaunchedEffect

        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                locationViewModel.refresh()
                delay(LOCATION_REFRESH_INTERVAL_MS)
            }
        }
    }

    Scaffold(
        bottomBar = {
            // Arac paneli TAM EKRAN: kendi basina bir ortam, sekmelerden
            // biri degil. Alt cubuk burada dursaydi ekran "bir sekme daha"
            // gibi okunur ve HUD'un kaplama hissi kaybolurdu.
            if (currentRoute == EvaRoutes.VEHICLE_HUD) return@Scaffold

            NavigationBar {
                EvaTab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = currentRoute == tab.route,
                        onClick = {
                            if (currentRoute != tab.route) {
                                navController.navigate(tab.route) {
                                    // Sekmeler arasında gidip gelirken geri
                                    // yığınının şişmesini engeller; her sekme
                                    // kendi durumunu korur.
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        },
                        icon = { Icon(tab.icon, contentDescription = stringResource(tab.labelRes)) },
                        label = {
                            Text(
                                stringResource(tab.labelRes),
                                style = MaterialTheme.typography.labelMedium,
                            )
                        },
                    )
                }
            }
        },
    ) { innerPadding ->
        EvaNavHost(
            navController = navController,
            location = location,
            locationStatus = locationStatus,
            onRequestLocationPermission = {
                if (hasLocationPermission) {
                    // Izin zaten var: buton "tekrar dene" anlamina gelir.
                    scope.launch { locationViewModel.refresh() }
                } else {
                    hasRequestedLocationOnce = true
                    locationPermission.launchMultiplePermissionRequest()
                }
            },
            // "Bir daha sorma" secildiginde shouldShowRationale false olur
            // ve izin istegi sessizce hicbir sey yapmaz; bu durumda
            // kullaniciyi ayarlara yonlendirmek gerekir.
            // Kalici ret: hicbiri verilmemis VE hicbiri icin gerekce
            // ekrani gosterilemiyor. Tek bir izne bakmak yeterli degil --
            // FINE reddedilip COARSE verilmis olabilir.
            isLocationPermanentlyDenied = hasRequestedLocationOnce &&
                !hasLocationPermission &&
                locationPermission.permissions.none {
                    (it.status as? PermissionStatus.Denied)?.shouldShowRationale == true
                },
            selectedStation = selectedStation,
            onStationSelected = { station ->
                selectedStation = station
                navController.navigate(EvaRoutes.STATION_DETAIL)
            },
            modifier = Modifier.padding(innerPadding),
        )
    }
}

@Composable
private fun EvaNavHost(
    navController: NavHostController,
    /** null = konum henuz bilinmiyor; ekranlar duruma gore tepki verir. */
    location: EvaLocation?,
    locationStatus: LocationStatus,
    onRequestLocationPermission: () -> Unit,
    isLocationPermanentlyDenied: Boolean,
    selectedStation: StationDto?,
    onStationSelected: (StationDto) -> Unit,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = EvaRoutes.DASHBOARD,
        modifier = modifier,
    ) {
        composable(EvaRoutes.DASHBOARD) {
            // Konum yoksa istasyon SORGULANMAZ. Uydurma bir konumla
            // sorgu yapmak, baska bir sehrin fiyatlarini "yakinindaki"
            // diye gostermek olurdu.
            if (location == null) {
                LocationGate(
                    status = locationStatus,
                    onRequestPermission = onRequestLocationPermission,
                    isPermanentlyDenied = isLocationPermanentlyDenied,
                )
                return@composable
            }

            DashboardScreen(
                currentLat = location.lat,
                currentLon = location.lon,
                locationLabel = location.label,
                isLocationStale = !location.isPrecise,
                onStationSelected = onStationSelected,
                onSettingsClick = { navController.navigate(EvaRoutes.SETTINGS) },
                onVehicleClick = { navController.navigate(EvaRoutes.VEHICLE_HUD) },
            )
        }

        // Arac paneli: donen 3B arac ve cevresinde canli telemetri.
        // Panelde kucuk bir gorsel olarak degil, kendi ekraninda --
        // dort kose okumasi icin yer gerekiyor ve surucu bu ekrana
        // "araca bakmak" icin bilincli olarak giriyor.
        composable(EvaRoutes.VEHICLE_HUD) {
            val onboardingViewModel: VehicleOnboardingViewModel = hiltViewModel()
            val vehicleState by onboardingViewModel.currentVehicle
                .collectAsStateWithLifecycle()

            VehicleHudScreen(
                vehicle = vehicleState,
                onBack = { navController.popBackStack() },
            )
        }

        // Ayarlar alt sekmelerde DEGIL: kullanicinin gunluk akisinda yeri
        // yok, ama gizlilik politikasinin vaat ettigi "Verilerimi sil"
        // yolunun uygulama icinden erisilebilir olmasi Play tarafindan
        // zorunlu. Panel basligindaki disli simgesinden aciliyor.
        composable(EvaRoutes.SETTINGS) {
            SettingsScreen(onBack = { navController.popBackStack() })
        }

        composable(EvaRoutes.STATIONS_LIST) {
            val viewModel: StationsViewModel = hiltViewModel()

            if (location == null) {
                LocationGate(
                    status = locationStatus,
                    onRequestPermission = onRequestLocationPermission,
                    isPermanentlyDenied = isLocationPermanentlyDenied,
                )
                return@composable
            }

            // Konum degistiginde listeyi yeniden yukle -- aksi halde
            // kullanici izni verse bile eski konumun istasyonlarini
            // gormeye devam ederdi.
            LaunchedEffect(location.lat, location.lon) {
                viewModel.onLocationChanged(location.lat, location.lon)
            }

            StationsScreen(
                viewModel = viewModel,
                onStationSelected = onStationSelected,
                onRetryRequested = {
                    viewModel.loadNearbyStations(lat = location.lat, lon = location.lon)
                },
                userLat = location.lat,
                userLon = location.lon,
            )
        }

        composable(EvaRoutes.VEHICLE) {
            val onboardingViewModel: VehicleOnboardingViewModel = hiltViewModel()
            val vehicleState by onboardingViewModel.currentVehicle.collectAsStateWithLifecycle()

            // Telemetri, panelde batarya uyarilarini izleyen ViewModel ile
            // AYNI kaynaktan okunuyor; ikinci bir akis baslatmak dongle'a
            // paralel iki baglanti demek olurdu.
            val monitorViewModel: VehicleMonitorViewModel = hiltViewModel()
            val telemetry by monitorViewModel.telemetry.collectAsStateWithLifecycle()
            val telemetryConnection by monitorViewModel.connection
                .collectAsStateWithLifecycle()

            VehicleScreen(
                viewModel = onboardingViewModel,
                currentVehicle = vehicleState,
                telemetryConnection = telemetryConnection,
                telemetry = telemetry,
            )
        }

        composable(EvaRoutes.SUBSCRIPTION) {
            val viewModel: SubscriptionViewModel = hiltViewModel()
            SubscriptionScreen(viewModel = viewModel)
        }
    }
}

/**
 * StationDto'yu rememberSaveable ile Bundle'a yazabilmek icin JSON tabanli
 * Saver. Sinif zaten @Serializable oldugu icin ek bir model gerekmez.
 */
private val StationDtoJson = Json { ignoreUnknownKeys = true }

private val StationDtoSaver: Saver<StationDto?, String> = Saver(
    save = { station ->
        station?.let { StationDtoJson.encodeToString(StationDto.serializer(), it) } ?: ""
    },
    restore = { encoded ->
        encoded.takeIf { it.isNotEmpty() }?.let {
            runCatching { StationDtoJson.decodeFromString(StationDto.serializer(), it) }.getOrNull()
        }
    },
)

/**
 * Konum yokken hangi ekranin gosterilecegine karar verir.
 *
 * "Fix bekleniyor" ile "izin yok" AYNI ekrani gostermemeli: izni zaten
 * vermis bir kullaniciya "izin ver" butonu cikarmak, basmasi hicbir ise
 * yaramayan cikissiz bir ekrandir.
 */
@Composable
private fun LocationGate(
    status: LocationStatus,
    onRequestPermission: () -> Unit,
    isPermanentlyDenied: Boolean,
) {
    when (status) {
        LocationStatus.Resolving -> Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Spacer(Modifier.height(16.dp))
                Text(
                    stringResource(R.string.location_resolving),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        LocationStatus.PermissionRequired -> LocationRequiredScreen(
            onRequestPermission = onRequestPermission,
            isPermanentlyDenied = isPermanentlyDenied,
        )

        LocationStatus.Unavailable -> LocationRequiredScreen(
            onRequestPermission = onRequestPermission,
            isPermissionGranted = true,
        )

        // Available iken location null olmamali; olursa bekleme gosterilir.
        LocationStatus.Available -> Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator()
        }
    }
}

/**
 * Uygulama one gelmisken konumun tazelenme araligi.
 *
 * 60 saniye: surerken haritanin geride kalmayacagi, ama surekli GPS
 * kilidi zorlayip pili tuketmeyecegi bir orta nokta. Tazeleme cagrisi
 * zaten 2 dakikadan taze bir fix varsa onbellekten donuyor; bu deger
 * yalnizca "ne siklikta bakalim" sorusunu cevapliyor.
 */
private const val LOCATION_REFRESH_INTERVAL_MS = 60_000L
