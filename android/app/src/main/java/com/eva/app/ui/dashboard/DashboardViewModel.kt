// android/app/src/main/java/com/eva/app/ui/dashboard/DashboardViewModel.kt
package com.eva.app.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.eva.app.ui.stations.StationDto
import com.eva.app.ui.stations.StationFilters
import com.eva.app.ui.stations.StationsRepository
import com.eva.app.ui.stations.StationsResult
import com.eva.app.vehicle.VehicleProfile
import com.eva.app.vehicle.VehicleProfileRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DashboardUiState(
    val vehicle: VehicleProfile?,
    val filters: StationFilters = StationFilters(),
    val isRefreshing: Boolean = false,
    val nearbyStations: List<StationDto>,
    val cheapestStation: StationDto?,
    val isLoadingStations: Boolean,
    val stationsErrorMessage: String?,
    /**
     * Veriler cevrimdisi onbellekten geliyorsa son basarili sorgunun
     * zamani; canliysa null.
     */
    val offlineSinceEpochMs: Long? = null,
)

/** combine() en fazla 5 akis aldigi icin ara paket. */
private data class StationsSnapshot(
    val stations: List<StationDto>,
    val loading: Boolean,
    val error: String?,
    val refreshing: Boolean,
    val filters: StationFilters,
    val offlineSinceEpochMs: Long?,
)

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val vehicleProfileRepository: VehicleProfileRepository,
    private val stationsRepository: StationsRepository,
) : ViewModel() {

    private val _nearbyStations = MutableStateFlow<List<StationDto>>(emptyList())
    private var loadJob: Job? = null

    private val _filters = MutableStateFlow(StationFilters())
    private val _isRefreshing = MutableStateFlow(false)

    private val _isLoadingStations = MutableStateFlow(true)
    private val _stationsErrorMessage = MutableStateFlow<String?>(null)
    private val _offlineSince = MutableStateFlow<Long?>(null)

    // combine() en fazla 5 akis alir; cevrimdisi bilgisi ayri bir
    // combine ile ekleniyor.
    private val stationsState = combine(
        combine(
            _nearbyStations,
            _isLoadingStations,
            _stationsErrorMessage,
            _isRefreshing,
            _filters,
        ) { stations, loading, error, refreshing, filters ->
            StationsSnapshot(stations, loading, error, refreshing, filters, null)
        },
        _offlineSince,
    ) { snapshot, offlineSince ->
        snapshot.copy(offlineSinceEpochMs = offlineSince)
    }

    val uiState: StateFlow<DashboardUiState> = combine(
        vehicleProfileRepository.currentVehicle,
        stationsState,
    ) { vehicle, snapshot ->
        val stations = snapshot.stations
        DashboardUiState(
            vehicle = vehicle,
            filters = snapshot.filters,
            isRefreshing = snapshot.refreshing,
            nearbyStations = stations,
            // En ucuz istasyon YALNIZCA fiyatı bilinen istasyonlar arasından
            // seçilir (pricePerKwh != null) — bkz. CheapestNearbyCard.kt
            // dosya başı yorumu.
            cheapestStation = stations
                .filter { it.pricePerKwh != null }
                .minByOrNull { it.pricePerKwh!! },
            isLoadingStations = snapshot.loading,
            stationsErrorMessage = snapshot.error,
            offlineSinceEpochMs = snapshot.offlineSinceEpochMs,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = DashboardUiState(
            vehicle = vehicleProfileRepository.currentVehicle.value,
            nearbyStations = emptyList(),
            cheapestStation = null,
            isLoadingStations = true,
            stationsErrorMessage = null,
        ),
    )

    /**
     * Kullanicinin asagi cekerek tetikledigi yenileme.
     *
     * Neden gerekli: fiyatlar YALNIZCA ekran ilk acildiginda yukleniyordu.
     * Fiyat Tasarruf Ajani arka planda yeni tarife yazsa bile kullanici
     * ekranda eski degeri gormeye devam ediyordu; uygulamayi tamamen
     * kapatip acmaktan baska yenileme yolu yoktu. Harita da ayni listeyi
     * kullandigi icin o da guncellenmiyordu.
     */
    fun refresh(lat: Double, lon: Double) {
        _isRefreshing.value = true
        loadNearbyStations(lat, lon, isRefresh = true)
    }

    /** Filtre alt sayfasindan "Uygula" ile cagrilir. */
    fun applyFilters(filters: StationFilters, lat: Double, lon: Double) {
        _filters.value = filters
        loadNearbyStations(lat, lon)
    }

    fun loadNearbyStations(lat: Double, lon: Double, isRefresh: Boolean = false) {
        val vehicle = vehicleProfileRepository.currentVehicle.value

        // Yenilemede tam ekran spinner GOSTERILMEZ; mevcut liste ekranda
        // kalir ve ustte kucuk bir gosterge doner. Aksi halde her yenilemede
        // icerik kaybolup geri gelir, bu da "atlama" hissi yaratir.
        if (!isRefresh) {
            _isLoadingStations.value = true
        }
        _stationsErrorMessage.value = null

    // Ucusta olan istek varsa IPTAL edilir.
    //
    // Neden gerekli: konum FALLBACK'ten gercek fix'e gecince art arda iki
    // yukleme tetiklenir. Iptal olmadan bunlar YARISIR ve once baslayan
    // (varsayilan sehir) sonra tamamlanirsa YENI sonucun uzerine yazar --
    // kullanici "Ipsala" basligi altinda San Francisco istasyonlarini gorur.
    // Job'u iptal ederek her zaman EN SON istegin kazanmasini garantiliyoruz.
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            // Araç kayıtlıysa, soket tipiyle UYUMLU OLMAYAN istasyonlar
            // Gateway tarafında zaten elenir (connectorTypes filtresi) —
            // kullanıcı asla kendi aracına takamayacağı bir istasyonu
            // "en ucuz" olarak görmez.
            val connectorFilter = vehicle?.let { listOf(it.connectorType) } ?: emptyList()

            val filters = _filters.value

            when (
                val result = stationsRepository.findNearbyStations(
                    lat = lat,
                    lon = lon,
                    radiusMeters = filters.radiusMeters,
                    connectorTypes = connectorFilter,
                    minPowerKw = filters.minPowerKw,
                )
            ) {
                is StationsResult.Success -> {
                    _offlineSince.value = null
                    // onlyWithPrice sunucu sozlesmesinde YOK; tek istemci
                    // tarafi elemesi bu. Fiyati bilinmeyen istasyonu
                    // gizlemek kullanicinin acik tercihi.
                    _nearbyStations.value = if (filters.onlyWithPrice) {
                        result.stations.filter { it.pricePerKwh != null }
                    } else {
                        result.stations
                    }
                    _isLoadingStations.value = false
                    _isRefreshing.value = false
                }
                is StationsResult.Offline -> {
                    // Ag yok ama elimizde gercek fiyatlar var. Ekrani
                    // bosaltmak yerine bunlari gosteriyoruz; kullanici
                    // banner'dan verinin eski oldugunu goruyor.
                    _nearbyStations.value = if (filters.onlyWithPrice) {
                        result.stations.filter { it.pricePerKwh != null }
                    } else {
                        result.stations
                    }
                    _offlineSince.value = result.fetchedAtEpochMs
                    _stationsErrorMessage.value = null
                    _isLoadingStations.value = false
                    _isRefreshing.value = false
                }
                is StationsResult.Failure -> {
                    _stationsErrorMessage.value = result.message
                    _isLoadingStations.value = false
                    _isRefreshing.value = false
                }
            }
        }
    }

    fun updateChargeLevel(newPercent: Int) {
        vehicleProfileRepository.updateChargeLevel(newPercent)
    }
}
