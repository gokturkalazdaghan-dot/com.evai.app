// android/app/src/test/java/com/eva/app/vehicle/telemetry/obd/ObdProtocolTest.kt
package com.eva.app.vehicle.telemetry.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Bir olcek hatasi sessizce yanlis batarya yuzdesi uretir; sofor bunu
 * ekranda dogru sanir. Bu yuzden ayristirma testsiz birakilmamali.
 */
class ObdProtocolTest {

    @Test
    fun `SOC yaniti J1979 olcegiyle cevrilir`() {
        // 0xFF = 255 -> %100
        val full = parseObdResponse(ObdPid.BATTERY_STATE_OF_CHARGE, "415BFF")
        assertEquals(100.0, full!!.value, 0.01)

        // 0x80 = 128 -> %50.2
        val half = parseObdResponse(ObdPid.BATTERY_STATE_OF_CHARGE, "415B80")
        assertEquals(50.196, half!!.value, 0.01)

        // 0x00 -> %0
        val empty = parseObdResponse(ObdPid.BATTERY_STATE_OF_CHARGE, "415B00")
        assertEquals(0.0, empty!!.value, 0.01)
    }

    @Test
    fun `100e bolme hatasi yakalanir`() {
        // 0x64 = 100. Yanlis uygulama %100 der; dogrusu 100*100/255 = %39.2
        val reading = parseObdResponse(ObdPid.BATTERY_STATE_OF_CHARGE, "415B64")
        assertEquals(39.215, reading!!.value, 0.01)
    }

    @Test
    fun `bosluklu ve istem karakterli yanit ayristirilir`() {
        val reading = parseObdResponse(ObdPid.BATTERY_STATE_OF_CHARGE, "41 5B 80\r\r>")
        assertEquals(50.196, reading!!.value, 0.01)
    }

    @Test
    fun `voltaj iki bayttan mV olarak cevrilir`() {
        // 0x36 0x8C = 14 0 0? -> (54*256 + 140)/1000 = 13.964 V
        val reading = parseObdResponse(ObdPid.CONTROL_MODULE_VOLTAGE, "4142368C")
        assertEquals(13.964, reading!!.value, 0.001)
    }

    @Test
    fun `desteklenmeyen PID icin null doner`() {
        // "NO DATA" 0 SAYILMAMALI -- bos batarya bildirmek olurdu.
        assertNull(parseObdResponse(ObdPid.BATTERY_STATE_OF_CHARGE, "NO DATA"))
        assertNull(parseObdResponse(ObdPid.BATTERY_STATE_OF_CHARGE, "?"))
        assertNull(parseObdResponse(ObdPid.BATTERY_STATE_OF_CHARGE, "SEARCHING..."))
        assertNull(parseObdResponse(ObdPid.BATTERY_STATE_OF_CHARGE, ""))
    }

    @Test
    fun `baska PIDin yaniti kabul edilmez`() {
        // Voltaj yaniti gelmisken SOC ayristirmaya calisirsak null olmali;
        // aksi halde 13.9 V bir sekilde yuzdeye cevrilirdi.
        assertNull(parseObdResponse(ObdPid.BATTERY_STATE_OF_CHARGE, "4142368C"))
    }

    @Test
    fun `eksik veri baytinda null doner`() {
        assertNull(parseObdResponse(ObdPid.CONTROL_MODULE_VOLTAGE, "414236"))
    }

    @Test
    fun `sarj durumu voltajdan cikarilir`() {
        assertEquals(true, inferCharging(14.1))
        assertEquals(false, inferCharging(12.4))
        // Belirsiz aralik: emin degilsek false DEMIYORUZ.
        assertNull(inferCharging(12.8))
        assertNull(inferCharging(null))
    }
}
