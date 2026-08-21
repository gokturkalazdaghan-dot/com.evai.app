// android/app/src/test/java/com/eva/app/ui/stations/StationsCacheTest.kt
package com.eva.app.ui.stations

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Çevrimdışı önbelleğin iki kuralı sessizce bozulmaya çok müsait:
 *  - Yakınlık: bozulursa kullanıcıya BAŞKA BİR ŞEHRİN fiyatları
 *    "yakınındaki istasyonlar" diye gösterilir — düzelttiğimiz
 *    San Francisco hatasının aynısı, bu sefer önbellek üzerinden.
 *  - Bayatlama: bozulursa saatler önceki tarife güncel sanılır.
 */
class StationsCacheTest {

    // İpsala merkez
    private val ipsalaLat = 40.9189
    private val ipsalaLon = 26.3723

    @Test
    fun `ayni noktada onbellek kullanilir`() {
        assertTrue(isCacheUsableFor(ipsalaLat, ipsalaLon, ipsalaLat, ipsalaLon))
    }

    @Test
    fun `birkac kilometre otede onbellek kullanilir`() {
        // ~2 km kuzey: kullanici sehir icinde hareket etmis.
        assertTrue(isCacheUsableFor(ipsalaLat + 0.018, ipsalaLon, ipsalaLat, ipsalaLon))
    }

    @Test
    fun `baska sehirdeki onbellek KULLANILMAZ`() {
        // Istanbul (~200 km): buradaki fiyatlar Ipsala icin gecersiz.
        assertFalse(isCacheUsableFor(41.0082, 28.9784, ipsalaLat, ipsalaLon))
    }

    @Test
    fun `baska kitadaki onbellek KULLANILMAZ`() {
        // San Francisco onbellegi Turkiye'de asla kullanilmamali.
        assertFalse(isCacheUsableFor(ipsalaLat, ipsalaLon, 37.7749, -122.4194))
    }

    @Test
    fun `esik civari - 20 km icerde kullanilir 30 km disarida kullanilmaz`() {
        // 1 derece enlem ~= 111 km. 0.18 derece ~= 20 km, 0.27 ~= 30 km.
        assertTrue(isCacheUsableFor(ipsalaLat + 0.18, ipsalaLon, ipsalaLat, ipsalaLon))
        assertFalse(isCacheUsableFor(ipsalaLat + 0.27, ipsalaLon, ipsalaLat, ipsalaLon))
    }

    @Test
    fun `taze onbellek bayat sayilmaz`() {
        val fresh = CachedStations(emptyList(), System.currentTimeMillis() - 60_000)
        assertFalse(fresh.isStale)
    }

    @Test
    fun `alti saatten eski onbellek bayat sayilir`() {
        val old = CachedStations(
            stations = emptyList(),
            fetchedAtEpochMs = System.currentTimeMillis() - CACHE_STALE_AFTER_MS - 60_000,
        )
        assertTrue(old.isStale)
        // Bayat olmasi GOSTERILMEYECEGI anlamina gelmez: gosterilir ama
        // banner'da yasi yazar.
    }
}
