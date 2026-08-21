// android/app/src/main/java/com/eva/app/EvaApplication.kt
package com.eva.app

import android.app.Application
import android.util.Log
import com.eva.app.commerce.RevenueCatManager
import com.eva.app.security.DeviceRegistrationRepository
import com.eva.app.security.IntegrityDecision
import com.eva.app.security.IntegrityGate
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "EvaApplication"

@HiltAndroidApp
class EvaApplication : Application() {

    @Inject
    lateinit var revenueCatManager: RevenueCatManager

    @Inject
    lateinit var deviceRegistrationRepository: DeviceRegistrationRepository

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _integrityDecision = MutableStateFlow(IntegrityDecision.ALLOWED)
    val integrityDecision: StateFlow<IntegrityDecision> = _integrityDecision.asStateFlow()

    override fun onCreate() {
        super.onCreate()

        Log.i(TAG, "Eva uygulaması başlatılıyor. Sürüm: ${BuildConfig.VERSION_NAME}")

        val integrityGate = IntegrityGate(this)
        _integrityDecision.value = integrityGate.evaluateLocalHeuristics()
        if (_integrityDecision.value != IntegrityDecision.ALLOWED) {
            Log.w(TAG, "Cihaz bütünlük uyarısı: ${_integrityDecision.value}")
        }

        // Cihaz kimligi RevenueCat'e verilir ki satin alma webhook'u
        // sunucuda dogru cihaza baglanabilsin (bkz. RevenueCatManager).
        revenueCatManager.configure(
            debugLogsEnabled = BuildConfig.DEBUG,
            appUserId = deviceRegistrationRepository.deviceId(),
        )

        applicationScope.launch {
            revenueCatManager.refreshCustomerInfo()

            // İmzalama anahtarı üretimi + Gateway'e kayıt — yalnızca ilk
            // açılışta gerçek bir ağ çağrısı yapar (DeviceRegistrationRepository
            // zaten kayıtlıysa Gateway idempotent upsert ile no-op döner).
            // Bu, sonraki her API isteğinin X-Eva-Signature header'ı
            // taşıyabilmesi için ön koşuldur.
            val registrationState = deviceRegistrationRepository.ensureRegistered()
            Log.i(TAG, "Cihaz kayıt durumu: $registrationState")
        }
    }
}
