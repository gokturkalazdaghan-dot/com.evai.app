// android/app/src/main/java/com/eva/app/vehicle/telemetry/obd/ObdProtocol.kt
package com.eva.app.vehicle.telemetry.obd

/**
 * ELM327 / OBD-II protokol katmani.
 *
 * Neden ayri ve SAF: cerceve ayristirma ve olcek cevrimi, Bluetooth
 * yigini olmadan test edilebilmeli. Bir olcek hatasi (255'e bolmeyi
 * unutmak gibi) sessizce yanlis batarya yuzdesi uretir -- soforu bos
 * yolda birakabilecek turden bir hata.
 */

/** ELM327 baslatma komutlari; sirayla gonderilir. */
val ELM327_INIT_COMMANDS = listOf(
    "ATZ",    // Sifirla
    "ATE0",   // Yanki kapali -- yoksa her komut geri okunur ve ayristirma bozulur
    "ATL0",   // Satir beslemesi kapali
    "ATS0",   // Bosluklar kapali
    "ATH0",   // Baslik kapali
    "ATSP0",  // Protokolu otomatik sec
)

/**
 * Desteklenen OBD-II PID'leri.
 *
 * mode 01 = anlik veri. Yanit "41 <pid> <veri...>" ile gelir.
 */
enum class ObdPid(val command: String, val responsePrefix: String) {
    /**
     * Hibrit/EV batarya paketi kalan yuku (SAE J1979 PID 0x5B).
     * Tek bayt, 0-255 -> %0-100.
     */
    BATTERY_STATE_OF_CHARGE("015B", "415B"),

    /**
     * Kontrol modulu voltaji (PID 0x42). Iki bayt, mV.
     * EV'lerde 12V yardimci bataryayi gosterir -- surus bataryasini DEGIL.
     */
    CONTROL_MODULE_VOLTAGE("0142", "4142"),

    /**
     * Yakit/enerji seviyesi (PID 0x2F). Bazi EV'ler SOC'yi buraya koyar.
     * 0x5B desteklenmiyorsa yedek olarak denenir.
     */
    FUEL_LEVEL("012F", "412F"),
}

/** Ayristirilmis bir OBD okumasi. */
data class ObdReading(
    val pid: ObdPid,
    val value: Double,
)

/**
 * ELM327 yanitini ayristirir.
 *
 * Yanit ornegi (bosluklar ATS0 ile kapali): "415B64>" -> PID 0x5B,
 * veri 0x64 = 100 -> %39.2
 *
 * @return okuma; yanit gecersiz/desteklenmiyorsa null. ASLA varsayilan
 *         bir deger dondurmez -- "NO DATA" yanitini 0 saymak, bos bir
 *         bataryayi bildirmek olurdu.
 */
fun parseObdResponse(pid: ObdPid, raw: String): ObdReading? {
    val cleaned = raw
        .replace(">", "")
        .replace("\r", "")
        .replace("\n", "")
        .replace(" ", "")
        .uppercase()
        .trim()

    // Arac PID'i desteklemiyor ya da mesgul.
    if (cleaned.isEmpty() ||
        cleaned.contains("NODATA") ||
        cleaned.contains("ERROR") ||
        cleaned.contains("UNABLE") ||
        cleaned.contains("STOPPED") ||
        cleaned.contains("SEARCHING")
    ) {
        return null
    }

    val index = cleaned.indexOf(pid.responsePrefix)
    if (index < 0) return null

    val payload = cleaned.substring(index + pid.responsePrefix.length)

    return when (pid) {
        ObdPid.BATTERY_STATE_OF_CHARGE, ObdPid.FUEL_LEVEL -> {
            val byteValue = payload.take(2).toIntOrNull(16) ?: return null
            // SAE J1979: yuzde = A * 100 / 255. 100'e bolmek yaygin bir
            // hatadir ve %39 yerine %100 gosterir.
            ObdReading(pid, byteValue * 100.0 / 255.0)
        }

        ObdPid.CONTROL_MODULE_VOLTAGE -> {
            if (payload.length < 4) return null
            val a = payload.substring(0, 2).toIntOrNull(16) ?: return null
            val b = payload.substring(2, 4).toIntOrNull(16) ?: return null
            ObdReading(pid, ((a * 256) + b) / 1000.0)
        }
    }
}

/**
 * Sarj olup olmadigini 12V voltajindan CIKARIM yapar.
 *
 * NEDEN CIKARIM: standart OBD-II'de "sarj oluyor" diye bir PID YOKTUR;
 * EV'lerde bu bilgi ureticiye ozel PID'lerde durur. Kontak kapaliyken
 * 12V bataryanin DC-DC donusturucu tarafindan beslenmesi (>13.0 V) guclu
 * bir sarj gostergesidir.
 *
 * @return sarj oluyor gibi gorunuyorsa true; voltaj bilinmiyorsa null.
 *         Emin olmadigimizda false DEMIYORUZ -- yanlis bir "sarj olmuyor",
 *         sarj sirasinda gereksiz uyari uretirdi.
 */
fun inferCharging(voltage: Double?): Boolean? {
    if (voltage == null) return null
    return when {
        voltage >= CHARGING_VOLTAGE_THRESHOLD -> true
        voltage <= RESTING_VOLTAGE_THRESHOLD -> false
        // Arada kalan degerler belirsiz: motor calisiyor da olabilir.
        else -> null
    }
}

/** Uzerinde DC-DC donusturucunun calistigi kabul edilen voltaj. */
private const val CHARGING_VOLTAGE_THRESHOLD = 13.0

/** Altinda bataryanin dinlenmede oldugu kabul edilen voltaj. */
private const val RESTING_VOLTAGE_THRESHOLD = 12.6
