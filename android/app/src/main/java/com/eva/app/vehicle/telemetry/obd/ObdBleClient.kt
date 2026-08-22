// android/app/src/main/java/com/eva/app/vehicle/telemetry/obd/ObdBleClient.kt
package com.eva.app.vehicle.telemetry.obd

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

private const val TAG = "ObdBleClient"

/**
 * ELM327 BLE klonlarinda en yaygin servis/karakteristik ucluleri.
 *
 * Tek bir UUID'ye baglanmak, piyasadaki dongle'larin cogunda calismamak
 * demek: uretici birligi yok, her klon farkli bir set kullaniyor.
 * Sirayla denenir.
 */
private val KNOWN_SERVICE_PROFILES = listOf(
    // En yaygin ucuz ELM327 BLE klonu
    BleProfile(
        service = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb"),
        notify = UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb"),
        write = UUID.fromString("0000fff2-0000-1000-8000-00805f9b34fb"),
    ),
    // Nordic UART (Vgate iCar, OBDLink LX gibi)
    BleProfile(
        service = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e"),
        notify = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e"),
        write = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e"),
    ),
    // Bazi Carista/Konnwei modelleri
    BleProfile(
        service = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb"),
        notify = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb"),
        write = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb"),
    ),
)

private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

/** Tek bir komutun yanit bekleme suresi. */
private const val COMMAND_TIMEOUT_MS = 3_000L

/** ELM327 baslatma komutlari arasi bekleme. */
private const val INIT_SETTLE_MS = 200L

data class BleProfile(val service: UUID, val notify: UUID, val write: UUID)

/**
 * OBD-II dongle'i ile BLE uzerinden konusur.
 *
 * NEDEN TELEFONDA (SUNUCUDA DEGIL)
 * --------------------------------
 * BLE menzili ~10 metredir ve dongle araca takilidir. Sunucudaki bir
 * servis ona ULASAMAZ. Veriyi okuyabilecek tek sey aracin icindeki
 * cihazdir: telefon. Telefon okur, gateway'e gonderir; panel gateway'den
 * dinler.
 */
class ObdBleClient(private val context: Context) {

    private var gatt: BluetoothGatt? = null
    private var profile: BleProfile? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null

    /** Dongle'dan gelen satirlar. ELM327 yaniti '>' ile biter. */
    private val responses = Channel<String>(Channel.BUFFERED)
    private val buffer = StringBuilder()

    private val connectionResult = Channel<Boolean>(Channel.CONFLATED)

    fun hasPermissions(): Boolean {
        val required = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        } else {
            // Android 11 ve oncesinde BLE taramasi KONUM izni ister --
            // konum kullanmasak bile sistem bunu zorunlu tutar.
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        return required.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    /** Daha once eslesmis OBD dongle'lari. */
    @SuppressLint("MissingPermission")
    fun pairedObdDevices(): List<BluetoothDevice> {
        if (!hasPermissions()) return emptyList()
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return emptyList()
        return adapter.bondedDevices.orEmpty().filter { device ->
            val name = runCatching { device.name }.getOrNull()?.uppercase() ?: return@filter false
            // Isim eslemesi kaba ama pratik: OBD dongle'lari standart bir
            // BLE hizmet sinifi ilan etmez.
            OBD_NAME_HINTS.any { hint -> name.contains(hint) }
        }
    }

    @SuppressLint("MissingPermission")
    suspend fun connect(device: BluetoothDevice): Boolean {
        if (!hasPermissions()) {
            Log.w(TAG, "Bluetooth izinleri yok.")
            return false
        }

        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)

        val connected = withTimeoutOrNull(10_000L) { connectionResult.receive() } ?: false
        if (!connected) {
            disconnect()
            return false
        }

        return initializeElm327()
    }

    /**
     * ELM327'yi konusulabilir hale getirir.
     *
     * ATE0 (yanki kapatma) KRITIK: acik kalirsa dongle her komutu geri
     * yankilar ve yanit ayristirmasi komutun kendisini veri saniyordu.
     */
    private suspend fun initializeElm327(): Boolean {
        ELM327_INIT_COMMANDS.forEach { command ->
            val response = sendCommand(command)
            if (response == null) {
                Log.w(TAG, "Baslatma komutu yanitsiz kaldi: $command")
                return false
            }
            kotlinx.coroutines.delay(INIT_SETTLE_MS)
        }
        return true
    }

    /** Bir PID sorar ve ayristirilmis okumayi doner. */
    suspend fun read(pid: ObdPid): ObdReading? {
        val raw = sendCommand(pid.command) ?: return null
        return parseObdResponse(pid, raw)
    }

    @SuppressLint("MissingPermission")
    private suspend fun sendCommand(command: String): String? {
        val characteristic = writeCharacteristic ?: return null
        val activeGatt = gatt ?: return null

        buffer.clear()
        // Eski yanitlar yeni komuta atfedilmesin diye kanal bosaltilir.
        while (responses.tryReceive().isSuccess) { /* bosalt */ }

        val payload = "$command\r".toByteArray()

        val written = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activeGatt.writeCharacteristic(
                characteristic,
                payload,
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE,
            ) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                characteristic.value = payload
                characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                activeGatt.writeCharacteristic(characteristic)
            }
        }

        if (!written) return null

        return withTimeoutOrNull(COMMAND_TIMEOUT_MS) { responses.receive() }
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null
        writeCharacteristic = null
        profile = null
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connectionResult.trySend(false)
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                connectionResult.trySend(false)
                return
            }

            // Bilinen profilleri sirayla dene; ilk eslesen kullanilir.
            val matched = KNOWN_SERVICE_PROFILES.firstOrNull { candidate ->
                gatt.getService(candidate.service)?.getCharacteristic(candidate.notify) != null
            }

            if (matched == null) {
                Log.w(TAG, "Taninan bir OBD BLE profili bulunamadi.")
                connectionResult.trySend(false)
                return
            }

            profile = matched
            val service = gatt.getService(matched.service)
            val notifyCharacteristic = service.getCharacteristic(matched.notify)
            writeCharacteristic = service.getCharacteristic(matched.write)

            gatt.setCharacteristicNotification(notifyCharacteristic, true)

            // CCCD yazmadan bildirim GELMEZ -- setCharacteristicNotification
            // yalnizca yerel tarafi acar, uzak tarafa haber vermez.
            notifyCharacteristic.getDescriptor(CCCD_UUID)?.let { descriptor ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeDescriptor(
                        descriptor,
                        BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE,
                    )
                } else {
                    @Suppress("DEPRECATION")
                    run {
                        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        gatt.writeDescriptor(descriptor)
                    }
                }
            }

            connectionResult.trySend(writeCharacteristic != null)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) = handleIncoming(value)

        @Deprecated("Android 12 ve oncesi icin gerekli")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            handleIncoming(characteristic.value ?: return)
        }
    }

    /**
     * Gelen parcalari birlestirir.
     *
     * BLE bildirimleri ~20 baytlik parcalar halinde gelir; bir ELM327
     * yaniti birden fazla bildirime bolunebilir. '>' istem karakteri
     * gorulene kadar biriktirilir -- yoksa yarim yanitlar ayristirilir
     * ve sessizce null doner.
     */
    private fun handleIncoming(value: ByteArray) {
        buffer.append(String(value, Charsets.US_ASCII))
        if (buffer.contains('>')) {
            val complete = buffer.toString()
            buffer.clear()
            responses.trySend(complete)
        }
    }
}

/** Dongle isimlerinde gecen yaygin ipuclari. */
private val OBD_NAME_HINTS = listOf("OBD", "ELM", "VGATE", "VEEPEAK", "KONNWEI", "CARISTA")
