// android/app/src/main/java/com/eva/app/location/LocationViewModel.kt
package com.eva.app.location

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Konumu tum sekmelere tek kaynaktan dagitir.
 *
 * Activity kapsaminda tek bir ornek olarak kullanilir (bkz. MainActivity);
 * boylece Panel, Istasyonlar ve Eva sekmeleri AYNI koordinati gorur. Daha
 * once her ekran koordinati ayri ayri sabit kodluyordu.
 */
@HiltViewModel
class LocationViewModel @Inject constructor(
    private val repository: LocationRepository,
) : ViewModel() {

    val location: StateFlow<EvaLocation?> = repository.location

    /** Konum yoksa NEDEN yok; ekran secimini bu belirler. */
    val status: StateFlow<LocationStatus> = repository.status

    fun hasPermission(): Boolean = repository.hasLocationPermission()

    /**
     * Izin verildikten sonra ya da ekran acilisinda cagrilir. Izin yoksa
     * sessizce varsayilan konumda kalir -- kullaniciya hata gosterilmez,
     * yalnizca EvaLocation.isPrecise false kalir.
     */
    fun refresh() {
        viewModelScope.launch { repository.refresh() }
    }
}
