// android/app/src/main/java/com/eva/app/ui/stations/StationsViewModel.kt
package com.eva.app.ui.stations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class StationsUiState {
    data object Loading : StationsUiState()
    data class Loaded(
        val stations: List<StationDto>,
        /**
         * Veri cevrimdisi onbellekten geliyorsa son basarili sorgunun
         * zamani; canliysa null. UI bunu banner olarak gosterir --
         * kullanici eski fiyata bakiyorsa bunu BILMELI.
         */
        val offlineSinceEpochMs: Long? = null,
    ) : StationsUiState()
    data class Empty(val message: String) : StationsUiState()
    data class Error(val message: String) : StationsUiState()
}

@HiltViewModel
class StationsViewModel @Inject constructor(
    private val repository: StationsRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow<StationsUiState>(StationsUiState.Loading)
    val uiState: StateFlow<StationsUiState> = _uiState.asStateFlow()

    // Kullanicinin yasadigi ulkeye gore; daha once METRIC sabit kodluydu
    // ve DashboardScreen ile celisiyordu (ayni istasyon iki ekranda
    // farkli birimle gorunuyordu).
    private val _unitSystem = MutableStateFlow(unitSystemFor())
    val unitSystem: StateFlow<UnitSystem> = _unitSystem.asStateFlow()

    private var lastLoadedCoordinates: Pair<Double, Double>? = null
    private var loadJob: Job? = null

    /**
     * Ekran acildiginda ve konum degistiginde cagrilir.
     *
     * Iki sorunu birden cozer:
     *  1) Ilk yuklemeyi tetikleyen HICBIR SEY yoktu -- uiState "Loading" ile
     *     basliyor, loadNearbyStations yalnizca "tekrar dene" dugmesinden
     *     cagriliyordu; kullanici ekrani actiginda sonsuz spinner goruyordu.
     *  2) Konum izni verilip gercek fix alindiginda liste yenilenmiyordu;
     *     kullanici hala varsayilan sehrin istasyonlarini goruyordu.
     *
     * Ayni koordinat icin tekrar sorgu ATILMAZ, boylece sekmeler arasi
     * gecisler bosuna istek uretmez.
     */
    fun onLocationChanged(lat: Double, lon: Double) {
        val coordinates = lat to lon
        if (lastLoadedCoordinates == coordinates) return
        lastLoadedCoordinates = coordinates
        loadNearbyStations(lat, lon)
    }

    fun loadNearbyStations(
        lat: Double,
        lon: Double,
        radiusMeters: Int = 15_000,
        connectorTypes: List<String> = emptyList(),
    ) {
        _uiState.value = StationsUiState.Loading

    // Ucusta olan istek varsa IPTAL edilir.
    //
    // Neden gerekli: konum FALLBACK'ten gercek fix'e gecince art arda iki
    // yukleme tetiklenir. Iptal olmadan bunlar YARISIR ve once baslayan
    // (varsayilan sehir) sonra tamamlanirsa YENI sonucun uzerine yazar --
    // kullanici "Ipsala" basligi altinda San Francisco istasyonlarini gorur.
    // Job'u iptal ederek her zaman EN SON istegin kazanmasini garantiliyoruz.
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            when (val result = repository.findNearbyStations(lat, lon, radiusMeters, connectorTypes)) {
                is StationsResult.Success -> {
                    _uiState.value = if (result.stations.isEmpty()) {
                        StationsUiState.Empty("Bu bölgede uygun istasyon bulunamadı.")
                    } else {
                        StationsUiState.Loaded(result.stations)
                    }
                }
                is StationsResult.Offline -> {
                    // Ag yok ama gercek fiyatlar elimizde: hata ekrani
                    // yerine veriyi goster, eski oldugunu belirt.
                    _uiState.value = StationsUiState.Loaded(
                        stations = result.stations,
                        offlineSinceEpochMs = result.fetchedAtEpochMs,
                    )
                }
                is StationsResult.Failure -> {
                    _uiState.value = StationsUiState.Error(result.message)
                }
            }
        }
    }

    fun setUnitSystem(system: UnitSystem) {
        _unitSystem.value = system
    }
}
