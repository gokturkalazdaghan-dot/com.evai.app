// android/app/src/test/java/com/eva/app/vehicle/RangeEstimatorTest.kt
package com.eva.app.vehicle

import com.eva.app.ui.stations.UnitSystem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RangeEstimatorTest {

    @Test
    fun `menzil sarj ve kapasiteye gore hesaplanir`() {
        // 75 kWh x %80 = 60 kWh; 60 x 6.2 = 372 km
        assertEquals(372.0, estimateRangeKm(80, 75.0)!!, 0.1)
    }

    @Test
    fun `sarj dustukce menzil orantili duser`() {
        val full = estimateRangeKm(100, 75.0)!!
        val half = estimateRangeKm(50, 75.0)!!
        assertEquals(full / 2, half, 0.1)
    }

    @Test
    fun `sifir sarjda menzil sifirdir`() {
        // Sifir, "bilinmiyor" DEGILDIR: bos batarya gercek bir durumdur.
        assertEquals(0.0, estimateRangeKm(0, 75.0)!!, 0.001)
    }

    @Test
    fun `kapasite bilinmiyorsa menzil de bilinmez`() {
        // Uydurma bir varsayilan kapasite KULLANILMAZ.
        assertNull(estimateRangeKm(80, null))
    }

    @Test
    fun `gecersiz girdiler null doner`() {
        assertNull(estimateRangeKm(null, 75.0))
        assertNull(estimateRangeKm(120, 75.0))
        assertNull(estimateRangeKm(-5, 75.0))
        assertNull(estimateRangeKm(80, 0.0))
        assertNull(estimateRangeKm(80, -10.0))
    }

    @Test
    fun `metrik ve imperial bicimlendirme`() {
        assertEquals("372 km", formatRange(372.0, UnitSystem.METRIC))
        // 372 km / 1.609344 = 231.2 mi
        assertEquals("231 mi", formatRange(372.0, UnitSystem.IMPERIAL))
    }

    @Test
    fun `bilinmeyen menzil eksigi soyler`() {
        // Sadece "—" gostermek, kullanicinin neyi duzeltmesi gerektigini
        // anlamamasina yol acardi.
        assertEquals("Menzil için kapasite gerekli", formatRange(null, UnitSystem.METRIC))
    }
}
