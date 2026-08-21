// android/app/src/main/java/com/eva/app/ui/settings/SettingsViewModel.kt
package com.eva.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.eva.app.privacy.DataDeletionRepository
import com.eva.app.privacy.DeletionResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Silme akisinin durumu. */
sealed interface DeletionUiState {
    data object Idle : DeletionUiState

    /** Istek surerken. Buton kilitlenir: cift silme istegi anlamsiz. */
    data object InProgress : DeletionUiState

    /**
     * Silindi.
     *
     * @param subscriptionRetained abonelik kaydi hukuki zorunluluk
     *        nedeniyle korunduysa true; ekranda ACIKCA yazilir.
     */
    data class Done(val subscriptionRetained: Boolean) : DeletionUiState

    /** Hicbir sey silinmedi. Kullanici tekrar deneyebilir. */
    data class Error(val reason: String) : DeletionUiState
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val dataDeletion: DataDeletionRepository,
) : ViewModel() {

    private val _deletionState = MutableStateFlow<DeletionUiState>(DeletionUiState.Idle)
    val deletionState: StateFlow<DeletionUiState> = _deletionState.asStateFlow()

    fun deleteMyData() {
        // Devam eden bir silme varken ikinci istek gonderme: ilki cihaz
        // kimligini dondurur, ikincisi artik gecersiz bir imzayla gider
        // ve kullaniciya sebepsiz bir hata gosterirdi.
        if (_deletionState.value is DeletionUiState.InProgress) return

        _deletionState.value = DeletionUiState.InProgress

        viewModelScope.launch {
            _deletionState.value = when (val result = dataDeletion.deleteEverything()) {
                is DeletionResult.Success ->
                    DeletionUiState.Done(result.subscriptionRetained)

                is DeletionResult.Failed ->
                    DeletionUiState.Error(result.reason)
            }
        }
    }

    fun dismissError() {
        if (_deletionState.value is DeletionUiState.Error) {
            _deletionState.value = DeletionUiState.Idle
        }
    }
}
