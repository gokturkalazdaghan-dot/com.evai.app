// android/app/src/main/java/com/eva/app/vehicle/telemetry/obd/ObdTelemetryProvider.kt
package com.eva.app.vehicle.telemetry.obd

import android.content.Context
import android.util.Log
import com.eva.app.vehicle.telemetry.TelemetrySource
import com.eva.app.vehicle.telemetry.VehicleTelemetry
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.time.Instant

private const val TAG = "ObdTelemetryProvider"

/**
 * Dongle sorgulama araligi.
 *
 * 3 saniye: batarya yuzdesi bundan hizli degismez, ama hiz degisir.
 * Daha sik sorgu ELM327'yi tikar (tek kanal, sirali komut) ve telefonun
 * bataryasini gereksiz yere tuketir.
 */
private const val POLL_INTERVAL_MS = 3_000L

/** Ust uste bu kadar basarisiz okumadan sonra baglanti kopmus sayilir. */
private const val MAX_CONSECUTIVE_FAILURES = 5

/**
 * OBD-II dongle'ini periyodik sorgulayip telemetri uretir.
 *
 * NEDEN URETICI API'SINE GEREK YOK: dongle aracin kendi CAN veri
 * yolundan okur. Kullanicinin uretici hesabi acmasi, OAuth izni vermesi
 * ya da desteklenen bir marka kullanmasi gerekmez -- 20 dolarlik bir
 * dongle yeter.
 *
 * SINIR: standart OBD-II her degeri vermez. Paket voltaji, lastik
 * basinci ve batarya sicakligi ureticiye ozel PID'lerdedir ve burada
 * OKUNMAZ -- null kalirlar, uydurulmazlar.
 */
class ObdTelemetryProvider(context: Context) {

    private val client = ObdBleClient(context)

    fun hasPermissions(): Boolean = client.hasPermissions()

    /** Eslesmis dongle var mi? */
    fun hasPairedDongle(): Boolean = client.pairedObdDevices().isNotEmpty()

    /**
     * Baglanir ve okumaya baslar.
     *
     * Akis, baglanti kurulamazsa BOS biter -- cagiran taraf bir sonraki
     * kaynaga duser. Hata firlatmak yerine bos akis: dongle'i olmayan
     * kullanici icin bu bir hata degil, normal durum.
     */
    fun telemetryFlow(batteryCapacityKwh: Double?): Flow<VehicleTelemetry> = flow {
        val device = client.pairedObdDevices().firstOrNull()
        if (device == null) {
            Log.i(TAG, "Eslesmis OBD dongle yok.")
            return@flow
        }

        if (!client.connect(device)) {
            Log.w(TAG, "Dongle'a baglanilamadi.")
            client.disconnect()
            return@flow
        }

        var failures = 0

        try {
            while (true) {
                val soc = readStateOfCharge()
                val voltage = client.read(ObdPid.CONTROL_MODULE_VOLTAGE)?.value

                if (soc == null && voltage == null) {
                    failures++
                    if (failures >= MAX_CONSECUTIVE_FAILURES) {
                        Log.w(TAG, "Dongle yanit vermiyor, akis kapatiliyor.")
                        return@flow
                    }
                } else {
                    failures = 0
                }

                emit(
                    VehicleTelemetry(
                        batteryPercent = soc?.toInt(),
                        // Menzil OBD'de YOK: kapasite ve kaba bir verimlilik
                        // varsayimiyla hesaplaniyor. Kapasite bilinmiyorsa
                        // menzil de bilinmiyor -- tahmin uretilmez.
                        rangeKm = estimateRange(soc, batteryCapacityKwh),
                        isCharging = inferCharging(voltage),
                        source = TelemetrySource.OBD_DONGLE,
                        capturedAt = Instant.now(),
                    ),
                )

                delay(POLL_INTERVAL_MS)
            }
        } finally {
            client.disconnect()
        }
    }

    /**
     * SOC okur; 0x5B desteklenmiyorsa 0x2F'e duser.
     *
     * Neden iki PID: EV'lerin bir kismi SOC'yi standart hibrit PID'inde
     * (0x5B) yayinlar, bir kismi yakit seviyesi PID'ini (0x2F) yeniden
     * kullanir. Tek PID denemek, araclarin yarisinda "veri yok" demekti.
     */
    private suspend fun readStateOfCharge(): Double? =
        client.read(ObdPid.BATTERY_STATE_OF_CHARGE)?.value
            ?: client.read(ObdPid.FUEL_LEVEL)?.value

    /**
     * Kaba menzil tahmini.
     *
     * Gercek menzil surus tarzina, hava sicakligina ve yol egimine
     * baglidir; bu yalnizca bir buyukluk mertebesi. Kapasite bilinmiyorsa
     * null doner -- "yaklasik" bile olsa bir sayi uydurmuyoruz.
     */
    private fun estimateRange(socPercent: Double?, capacityKwh: Double?): Double? {
        if (socPercent == null || capacityKwh == null || capacityKwh <= 0) return null
        val usableKwh = (socPercent / 100.0) * capacityKwh
        return usableKwh * AVERAGE_KM_PER_KWH
    }
}

/**
 * Ortalama verimlilik (km/kWh).
 *
 * 6.2: orta sinif bir elektrikli otomobilin karisik cevrim ortalamasi.
 * Aracin gercek verimliligi olculdukce bu deger kisisellestirilmeli.
 */
private const val AVERAGE_KM_PER_KWH = 6.2
