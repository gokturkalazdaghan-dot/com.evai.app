// android/app/src/test/java/com/eva/app/vehicle/telemetry/BatteryAlertMonitorTest.kt
package com.eva.app.vehicle.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Esik davranisi sessizce bozulmaya cok musait: bir kosul degisir,
 * uyari ya hic gelmez ya da dakikada bir tekrarlar. Ikisi de sahada
 * fark edilmesi zor, sonucu agir hatalardir.
 */
class BatteryAlertMonitorTest {

    private fun reading(
        percent: Int?,
        charging: Boolean? = false,
        ageMinutes: Long = 0,
        source: TelemetrySource = TelemetrySource.OEM_CLOUD,
    ) = VehicleTelemetry(
        batteryPercent = percent,
        rangeKm = null,
        isCharging = charging,
        source = source,
        capturedAt = Instant.now().minusSeconds(ageMinutes * 60),
    )

    @Test
    fun `esik ustunde uyari vermez`() {
        val monitor = BatteryAlertMonitor()
        assertEquals(BatteryAlertMonitor.Decision.Nothing, monitor.onTelemetry(reading(80)))
        assertEquals(BatteryAlertMonitor.Decision.Nothing, monitor.onTelemetry(reading(51)))
    }

    @Test
    fun `yuzde 50 altina dusunce LOW uyarisi verir`() {
        val monitor = BatteryAlertMonitor()
        monitor.onTelemetry(reading(80))

        val decision = monitor.onTelemetry(reading(49))

        assertEquals(
            BatteryAlertMonitor.Decision.Alert(BatteryAlertLevel.LOW, 49),
            decision,
        )
    }

    @Test
    fun `ayni esik ikinci kez uyari vermez`() {
        val monitor = BatteryAlertMonitor()
        monitor.onTelemetry(reading(80))
        monitor.onTelemetry(reading(49))

        // Ayni esigin altinda kalmaya devam: sessiz olmali.
        assertEquals(BatteryAlertMonitor.Decision.Nothing, monitor.onTelemetry(reading(48)))
        assertEquals(BatteryAlertMonitor.Decision.Nothing, monitor.onTelemetry(reading(45)))
    }

    @Test
    fun `esik civarindaki salinim tekrar tekrar uyarmaz`() {
        val monitor = BatteryAlertMonitor()
        monitor.onTelemetry(reading(80))
        monitor.onTelemetry(reading(49))

        // 49 -> 51 -> 49: histerezis marji 5 oldugu icin 51 yeniden
        // kurmaya YETMEZ.
        monitor.onTelemetry(reading(51))
        assertEquals(BatteryAlertMonitor.Decision.Nothing, monitor.onTelemetry(reading(49)))
    }

    @Test
    fun `yuzde 30 altina dusunce CRITICAL uyarisi verir`() {
        val monitor = BatteryAlertMonitor()
        monitor.onTelemetry(reading(80))
        monitor.onTelemetry(reading(49)) // LOW

        val decision = monitor.onTelemetry(reading(29))

        assertEquals(
            BatteryAlertMonitor.Decision.Alert(BatteryAlertLevel.CRITICAL, 29),
            decision,
        )
    }

    @Test
    fun `50 atlanip dogrudan 30 altina dusulurse yalnizca CRITICAL verir`() {
        val monitor = BatteryAlertMonitor()
        monitor.onTelemetry(reading(80))

        // Uygulama arka planda kaldi, kullanici %28'de acti.
        val first = monitor.onTelemetry(reading(28))
        assertEquals(
            BatteryAlertMonitor.Decision.Alert(BatteryAlertLevel.CRITICAL, 28),
            first,
        )

        // Gecilmis olan LOW esigi SONRADAN uyari uretmemeli.
        assertEquals(BatteryAlertMonitor.Decision.Nothing, monitor.onTelemetry(reading(27)))
    }

    @Test
    fun `sarj olurken uyari vermez`() {
        val monitor = BatteryAlertMonitor()
        monitor.onTelemetry(reading(80))

        val decision = monitor.onTelemetry(reading(25, charging = true))

        assertEquals(BatteryAlertMonitor.Decision.Nothing, decision)
    }

    @Test
    fun `sarj sonrasi tekrar dusunce yeniden uyarir`() {
        val monitor = BatteryAlertMonitor()
        monitor.onTelemetry(reading(80))
        monitor.onTelemetry(reading(49)) // LOW atesledi

        // Sarj oldu, %90'a cikti -> esikler yeniden kurulur.
        monitor.onTelemetry(reading(90, charging = true))
        monitor.onTelemetry(reading(90))

        val decision = monitor.onTelemetry(reading(48))
        assertEquals(
            BatteryAlertMonitor.Decision.Alert(BatteryAlertLevel.LOW, 48),
            decision,
        )
    }

    @Test
    fun `bilinmeyen batarya seviyesi uyari uretmez`() {
        val monitor = BatteryAlertMonitor()
        assertEquals(BatteryAlertMonitor.Decision.Nothing, monitor.onTelemetry(reading(null)))
    }

    @Test
    fun `bayat okuma uyari uretmez`() {
        val monitor = BatteryAlertMonitor()
        monitor.onTelemetry(reading(80))

        // OEM_CLOUD 30 dakikada bayatlar; 45 dakikalik okuma guvenilmez.
        val decision = monitor.onTelemetry(reading(20, ageMinutes = 45))

        assertEquals(BatteryAlertMonitor.Decision.Nothing, decision)
    }

    @Test
    fun `durum geri yuklenince atesleyen esik tekrar atesleme`() {
        val first = BatteryAlertMonitor()
        first.onTelemetry(reading(80))
        first.onTelemetry(reading(49))
        val saved = first.armedLevels()

        // Uygulama yeniden basladi.
        val restored = BatteryAlertMonitor()
        restored.restore(saved)

        assertEquals(BatteryAlertMonitor.Decision.Nothing, restored.onTelemetry(reading(47)))
    }

    @Test
    fun `uyari metni istasyon bilinmiyorsa istasyon uydurmaz`() {
        val message = batteryAlertMessage(BatteryAlertLevel.CRITICAL, 28, nearestStationName = null)

        assertTrue(message.contains("%28"))
        // Istasyon adi yoksa metinde bir istasyon iddiasi olmamali.
        assertTrue(message.contains("şarj noktası bulmamız gerek"))
    }
}
