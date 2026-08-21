// android/app/src/main/java/com/eva/app/vehicle/telemetry/BatteryAlertMonitor.kt
package com.eva.app.vehicle.telemetry

/**
 * Batarya seviyesi uyari esikleri.
 *
 * Sirali (yuksekten alcaga) tutulur; bir okumada birden fazla esik
 * asilirsa yalnizca EN DUSUK olan bildirilir. Aksi halde aracina %55
 * ile binip %28'de uygulamayi acan kullaniciya arka arkaya iki uyari
 * gelirdi.
 */
enum class BatteryAlertLevel(val threshold: Int) {
    LOW(50),
    CRITICAL(30),
    ;

    companion object {
        /** Esikler dusen sirada; tarama bu sirayla yapilir. */
        val descending: List<BatteryAlertLevel> = entries.sortedByDescending { it.threshold }
    }
}

/**
 * Bir esigin yeniden tetiklenebilmesi icin batarya seviyesinin esigin
 * BU KADAR uzerine cikmasi gerekir.
 *
 * NEDEN GEREKLI: batarya okumasi sabit degildir; %50 civarinda 49-51
 * arasi salinir (yokus, klima, rejeneratif fren). Histerezis olmadan
 * kullaniciya dakikada birkac kez "bataryan %50'nin altinda" denirdi.
 */
private const val REARM_MARGIN_PERCENT = 5

/**
 * Uyari kararini veren SAF fonksiyon mantigi.
 *
 * Neden ayri bir sinif: bu kararin Android'e, sese, bildirime ihtiyaci
 * yok -- yalnizca "onceki durum + yeni okuma" gerekli. Ayirmak, esik
 * davranisinin cihaz olmadan test edilebilmesini saglar.
 */
class BatteryAlertMonitor(
    /** Hangi esiklerin "atesleme hakki" var. Baslangicta hepsi kurulu. */
    private var armed: MutableSet<BatteryAlertLevel> = BatteryAlertLevel.entries.toMutableSet(),
) {

    /** Monitorun disariya bildirdigi karar. */
    sealed class Decision {
        data object Nothing : Decision()
        data class Alert(val level: BatteryAlertLevel, val batteryPercent: Int) : Decision()
    }

    /**
     * Yeni bir telemetri okumasini degerlendirir.
     *
     * SARJ OLURKEN UYARI YOK: kullanici zaten sarj istasyonunda ve
     * batarya YUKSELIYOR. "%30'un altindasin" demek, sorunu cozmekte
     * olan birine sorunu haber vermektir.
     */
    fun onTelemetry(telemetry: VehicleTelemetry): Decision {
        // Bilinmeyen ya da bayat okumayla uyarı verilmez: olmayan bir
        // veriye dayanarak soforu telaslandirmak, hic uyarmamaktan kotu.
        val percent = telemetry.batteryPercent?.takeIf { !telemetry.isStale() }
            ?: return Decision.Nothing

        if (telemetry.isCharging == true) {
            // Sarj sirasinda esikleri yeniden kur: sarj bitip tekrar
            // dustugunde uyari yeniden verilebilmeli.
            rearmAbove(percent)
            return Decision.Nothing
        }

        rearmAbove(percent)

        // En dusuk asilan esik bulunur (once CRITICAL'a bakilir).
        val triggered = BatteryAlertLevel.descending
            .lastOrNull { percent <= it.threshold && it in armed }
            ?: return Decision.Nothing

        // Bu esik ve UZERINDEKI tum esikler artik atesledi sayilir:
        // %50'yi atlayip dogrudan %28'e dusen bir okuma, sonradan
        // "%50'nin altina dustun" uyarisi uretmemeli.
        BatteryAlertLevel.descending
            .filter { it.threshold >= triggered.threshold }
            .forEach { armed.remove(it) }

        return Decision.Alert(triggered, percent)
    }

    /** Seviye esigin yeterince uzerine ciktiysa esik yeniden kurulur. */
    private fun rearmAbove(percent: Int) {
        BatteryAlertLevel.entries
            .filter { percent >= it.threshold + REARM_MARGIN_PERCENT }
            .forEach { armed.add(it) }
    }

    /** Kalici saklama icin durum. */
    fun armedLevels(): Set<BatteryAlertLevel> = armed.toSet()

    /** Uygulama yeniden basladiginda onceki durumu geri yukler. */
    fun restore(levels: Set<BatteryAlertLevel>) {
        armed = levels.toMutableSet()
    }
}

/**
 * Uyari metni.
 *
 * Eva bir ASISTAN gibi konusur: durumu bildirir ve bir sonraki adimi
 * onerir. Yalniz bir sayi soylemek ("batarya %30") sofora ne yapmasi
 * gerektigini soylemez.
 *
 * @param nearestStationName Yakinda bilinen bir istasyon varsa adi;
 *        yoksa null -- olmayan bir istasyon UYDURULMAZ.
 */
fun batteryAlertMessage(
    level: BatteryAlertLevel,
    batteryPercent: Int,
    nearestStationName: String? = null,
    nearestStationPrice: String? = null,
): String = when (level) {
    BatteryAlertLevel.LOW -> buildString {
        append("Bataryan %$batteryPercent'e düştü. ")
        if (nearestStationName != null) {
            append("Acele yok ama yakınında $nearestStationName var")
            if (nearestStationPrice != null) append(", kWh başı $nearestStationPrice")
            append(".")
        } else {
            append("Acele yok, yine de yol üstünde bir şarj planlayalım mı?")
        }
    }

    BatteryAlertLevel.CRITICAL -> buildString {
        append("Bataryan %$batteryPercent'te. ")
        if (nearestStationName != null) {
            append("En yakın istasyon $nearestStationName")
            if (nearestStationPrice != null) append(", kWh başı $nearestStationPrice")
            append(". İstersen rota çizeyim.")
        } else {
            append("Artık bir şarj noktası bulmamız gerek.")
        }
    }
}
