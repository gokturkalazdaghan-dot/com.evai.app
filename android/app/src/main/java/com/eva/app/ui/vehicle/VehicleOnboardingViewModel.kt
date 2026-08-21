// android/app/src/main/java/com/eva/app/ui/vehicle/VehicleOnboardingViewModel.kt
package com.eva.app.ui.vehicle

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.eva.app.vehicle.ConnectorOption
import com.eva.app.vehicle.VehicleProfile
import com.eva.app.vehicle.VehicleProfileRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class VehicleOnboardingFormState(
    val brand: String = "",
    val model: String = "",
    val batteryCapacityKwhText: String = "",
    val selectedConnector: ConnectorOption = ConnectorOption.CCS2,
    val currentChargePercentText: String = "80",
) {
    /** Form gönderilebilir mi — sunucuya/depoya boş ya da anlamsız veri
     * gitmesin diye burada doğrulanıyor. */
    val isValid: Boolean
        get() = brand.isNotBlank() &&
            model.isNotBlank() &&
            batteryCapacityKwhText.toDoubleOrNull()?.let { it > 0 } == true &&
            currentChargePercentText.toIntOrNull()?.let { it in 0..100 } == true
}

@HiltViewModel
class VehicleOnboardingViewModel @Inject constructor(
    private val repository: VehicleProfileRepository,
) : ViewModel() {

    private val _formState = MutableStateFlow(VehicleOnboardingFormState())
    val formState: StateFlow<VehicleOnboardingFormState> = _formState.asStateFlow()

    /**
     * Kayıtlı araç. Onboarding tek seferlikti; "Aracım" sekmesi ise her
     * açılışta mevcut değerleri göstermek zorunda (şarj yüzdesi her
     * yolculukta değişir), o yüzden depo akışı buradan yayımlanıyor.
     */
    val currentVehicle: StateFlow<VehicleProfile?> = repository.currentVehicle

    /**
     * Eva'nın onboarding'de söylediği ilk cümle — kullanıcının dil
     * tercihine göre backend'deki i18n mekanizmasıyla aynı yaklaşım
     * (bkz. Localizable.strings / strings.xml), burada dashboard'a özel
     * bir persona metni olarak tutuluyor.
     */
    val greetingMessage: String =
        "Selam! Yolculuğumuzun kusursuz olması için önce can yoldaşını " +
            "(aracını) tanımam lazım. Hangi elektrikli canavarla yollardayız?"

    fun onBrandChanged(value: String) {
        _formState.value = _formState.value.copy(brand = value)
    }

    fun onModelChanged(value: String) {
        _formState.value = _formState.value.copy(model = value)
    }

    fun onBatteryCapacityChanged(value: String) {
        // Yalnızca rakam ve tek bir ondalık ayırıcıya izin ver — kullanıcı
        // yanlışlıkla harf girerse form sessizce "geçersiz" kalır,
        // çökmez.
        if (value.isEmpty() || value.matches(Regex("^\\d*\\.?\\d*$"))) {
            _formState.value = _formState.value.copy(batteryCapacityKwhText = value)
        }
    }

    fun onConnectorSelected(option: ConnectorOption) {
        _formState.value = _formState.value.copy(selectedConnector = option)
    }

    fun onChargePercentChanged(value: String) {
        if (value.isEmpty() || value.matches(Regex("^\\d*$"))) {
            _formState.value = _formState.value.copy(currentChargePercentText = value)
        }
    }

    fun submit(onComplete: () -> Unit) {
        val state = _formState.value
        if (!state.isValid) return

        viewModelScope.launch {
            val profile = VehicleProfile(
                brand = state.brand.trim(),
                model = state.model.trim(),
                batteryCapacityKwh = state.batteryCapacityKwhText.toDouble(),
                connectorType = state.selectedConnector.backendValue,
                currentChargePercent = state.currentChargePercentText.toInt(),
            )
            repository.saveVehicle(profile)
            onComplete()
        }
    }
}
