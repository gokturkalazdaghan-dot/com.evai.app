// android/app/src/main/java/com/eva/app/ui/vehicle/VehicleMonitorViewModel.kt
package com.eva.app.ui.vehicle

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.eva.app.ui.stations.StationDto
import com.eva.app.ui.stations.formatPricePerKwh
import com.eva.app.vehicle.telemetry.BatteryAlertMonitor
import com.eva.app.vehicle.telemetry.BatteryAlertNotifier
import com.eva.app.vehicle.telemetry.VehicleTelemetry
import com.eva.app.vehicle.telemetry.VehicleTelemetryRepository
import com.eva.app.vehicle.telemetry.batteryAlertMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "VehicleMonitorVM"

/**
 * Aracin durumunu izler ve esik asilinca bildirim gonderir.
 *
 * NEDEN SESLI ASISTANDAN AYRILDI
 * ------------------------------
 * Batarya izleme once VoiceAssistantViewModel'in icindeydi. Bu, iki ayri
 * ozelligi birbirine baglıyordu: sesli asistan devre disi birakilinca
 * batarya uyarilari da susuyordu -- oysa uyarilar asistandan BAGIMSIZ
 * calismali. Telemetri, menzil ve uyari artik burada.
 */
@HiltViewModel
class VehicleMonitorViewModel @Inject constructor(
    private val telemetryRepository: VehicleTelemetryRepository,
    private val notifier: BatteryAlertNotifier,
) : ViewModel() {

    private val batteryMonitor = BatteryAlertMonitor()
    private var telemetryJob: Job? = null

    private val _telemetry = MutableStateFlow<VehicleTelemetry?>(null)
    val telemetry: StateFlow<VehicleTelemetry?> = _telemetry.asStateFlow()

    /** Verinin hangi kaynaktan geldigi; "Aracim" ekraninda gosterilir. */
    val connection = telemetryRepository.connection

    init {
        // Kanal ilk bildirimden ONCE olusmali; yoksa bildirim sessizce
        // dusurulur.
        notifier.ensureChannel()
    }

    /**
     * Izlemeyi baslatir.
     *
     * @param nearestStation uyari metnine eklenecek istasyon; bilinen bir
     *        istasyon yoksa null donmeli -- uydurulmaz.
     */
    fun start(nearestStation: () -> StationDto?) {
        if (telemetryJob?.isActive == true) return

        telemetryJob = viewModelScope.launch {
            telemetryRepository.telemetryFlow().collect { reading ->
                _telemetry.value = reading

                when (val decision = batteryMonitor.onTelemetry(reading)) {
                    is BatteryAlertMonitor.Decision.Nothing -> Unit

                    is BatteryAlertMonitor.Decision.Alert -> {
                        val station = nearestStation()
                        val message = batteryAlertMessage(
                            level = decision.level,
                            batteryPercent = decision.batteryPercent,
                            nearestStationName = station?.name,
                            nearestStationPrice = station?.pricePerKwh?.let {
                                formatPricePerKwh(it, station.currency)
                            },
                        )
                        Log.i(TAG, "Batarya uyarisi: ${decision.level} %${decision.batteryPercent}")
                        notifier.notify(decision.level, message)
                    }
                }
            }
        }
    }

    fun stop() {
        telemetryJob?.cancel()
        telemetryJob = null
    }

    override fun onCleared() {
        stop()
        super.onCleared()
    }
}
