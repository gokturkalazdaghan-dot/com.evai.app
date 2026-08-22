// android/app/src/main/java/com/eva/app/ui/hud/VehicleHudScreen.kt
package com.eva.app.ui.hud

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.eva.app.R
import com.eva.app.ui.dashboard.components.VehicleTurntable
import com.eva.app.ui.stations.UnitSystem
import com.eva.app.ui.stations.unitSystemFor
import com.eva.app.ui.theme.NeonCyan
import com.eva.app.ui.vehicle.VehicleMonitorViewModel
import com.eva.app.vehicle.VehicleProfile
import com.eva.app.vehicle.estimateRangeKm
import com.eva.app.vehicle.telemetry.TelemetrySource
import com.eva.app.vehicle.telemetry.VehicleTelemetry
import java.time.Duration
import java.time.Instant
import kotlin.math.roundToInt

/** HUD zemini — haritanin gri tonundan daha koyu, "kabin" hissi icin. */
private val HudBackground = Color(0xFF04080B)

/**
 * Aracin donen 3B gorunumu ve cevresinde canli telemetri.
 *
 * DORT KOSE NEDEN
 * ---------------
 * Surucu tek bakista dort seyi bilmek ister: ne kadar sarj kaldi, ne
 * kadar gidebilir, hangi arac, ve bu sayilar GUVENILIR mi. Dorduncu
 * kose (kaynak + tazelik) sussuz bir detay gibi gorunur ama en
 * onemlisidir: 42 km yaziyorsa, o 42'nin iki saniye once mi yoksa iki
 * saat once mi olculdugu kararı degistirir.
 *
 * VERI YOKSA
 * ----------
 * Hicbir kose uydurma deger gostermez. Okuma yoksa "—", bayatsa
 * bayat oldugu yazar. Bu ekranin tamami "arac bana ne diyor" sorusuna
 * cevap; olmayan bir cevabi varmis gibi sunmak, hic gostermemekten
 * kotudur.
 */
@Composable
fun VehicleHudScreen(
    vehicle: VehicleProfile?,
    onBack: () -> Unit,
    vehicleMonitorViewModel: VehicleMonitorViewModel = hiltViewModel(),
) {
    val telemetry by vehicleMonitorViewModel.telemetry.collectAsStateWithLifecycle()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(HudBackground),
    ) {
        MatrixRain(
            modifier = Modifier.fillMaxSize(),
            color = NeonCyan,
        )

        Column(modifier = Modifier.fillMaxSize()) {
            HudTopBar(
                title = vehicle?.displayName ?: stringResource(R.string.hud_title),
                onBack = onBack,
            )

            // Ust kose ciftleri
            HudRow(
                left = {
                    HudReadout(
                        label = stringResource(R.string.hud_charge),
                        value = formatCharge(telemetry, vehicle),
                        alignEnd = false,
                    )
                },
                right = {
                    HudReadout(
                        label = stringResource(R.string.hud_range),
                        value = formatRange(telemetry, vehicle),
                        alignEnd = true,
                    )
                },
            )

            // Arac, ekranin ortasinda ve kalan alani doldurur.
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                VehicleTurntable()
            }

            // Alt kose ciftleri
            HudRow(
                left = {
                    HudReadout(
                        label = stringResource(R.string.hud_battery),
                        value = formatCapacity(vehicle),
                        alignEnd = false,
                    )
                },
                right = {
                    HudReadout(
                        label = stringResource(R.string.hud_source),
                        value = sourceLabel(telemetry),
                        alignEnd = true,
                    )
                },
            )

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun HudTopBar(title: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.back),
                tint = NeonCyan,
            )
        }
        Text(
            text = title.uppercase(),
            color = NeonCyan,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp,
            letterSpacing = 2.sp,
        )
    }
}

/** Iki koseyi ayni satirda, kenarlara yaslayarak yerlestirir. */
@Composable
private fun HudRow(
    left: @Composable () -> Unit,
    right: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Box(Modifier.width(150.dp)) { left() }
        Box(Modifier.width(150.dp), contentAlignment = Alignment.TopEnd) { right() }
    }
}

/**
 * Tek bir kose okumasi: kucuk etiket, buyuk deger.
 *
 * Etiket kucuk ve sonuk, deger buyuk ve parlak: goz once sayiyi bulmali,
 * ne oldugunu sonra okumali. Tersi, dort kosede dort kez okumak demek.
 */
@Composable
private fun HudReadout(
    label: String,
    value: String,
    alignEnd: Boolean,
) {
    Column(
        modifier = Modifier
            .background(
                color = HudBackground.copy(alpha = 0.72f),
                shape = RoundedCornerShape(10.dp),
            )
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start,
    ) {
        Text(
            text = label.uppercase(),
            color = NeonCyan.copy(alpha = 0.62f),
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            letterSpacing = 1.5.sp,
            textAlign = if (alignEnd) TextAlign.End else TextAlign.Start,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = value,
            color = NeonCyan,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 20.sp,
            textAlign = if (alignEnd) TextAlign.End else TextAlign.Start,
        )
    }
}

// ---------------------------------------------------------------------
// Bicimlendirme
//
// Hepsinin ortak kurali: BILINMEYEN DEGER UYDURULMAZ. Okuma yoksa ya da
// bayatsa, sayi yerine bunu soyleyen bir isaret gosterilir.
// ---------------------------------------------------------------------

private const val UNKNOWN = "—"

/**
 * Gecerli sarj yuzdesi.
 *
 * KURAL PANELLE AYNI: canli ve guvenilir bir okuma varsa o, yoksa
 * kullanicinin profilde verdigi son deger (bkz. EvStatusBar). Iki ekran
 * ayni kurali kullanmazsa ayni anda farkli yuzde gosterebilirler --
 * kullanici hangisine inanacagini bilemez.
 */
private fun currentChargePercent(
    telemetry: VehicleTelemetry?,
    vehicle: VehicleProfile?,
): Int? = telemetry?.takeIf { it.hasUsableBattery }?.batteryPercent
    ?: vehicle?.currentChargePercent

private fun formatCharge(telemetry: VehicleTelemetry?, vehicle: VehicleProfile?): String {
    val percent = currentChargePercent(telemetry, vehicle) ?: return UNKNOWN

    // Sarj oluyorsa isaretle: ayni yuzde, fisteyken bambaska bir anlam
    // tasir.
    val chargingMark = if (telemetry?.isCharging == true) " ⚡" else ""
    return "%$percent$chargingMark"
}

/**
 * Menzil.
 *
 * Araçtan gelen bir menzil okumasi VARSA o tercih edilir -- ureticinin
 * kendi hesabi, bizim ortalama tuketim varsayimimizdan iyidir. Yoksa
 * sarj + kapasiteden tahmin edilir; kapasite bilinmiyorsa sayi
 * URETILMEZ.
 */
private fun formatRange(telemetry: VehicleTelemetry?, vehicle: VehicleProfile?): String {
    val km = telemetry?.takeIf { !it.isStale() }?.rangeKm
        ?: estimateRangeKm(
            chargePercent = currentChargePercent(telemetry, vehicle),
            batteryCapacityKwh = vehicle?.batteryCapacityKwh,
        )
        ?: return UNKNOWN

    return when (unitSystemFor()) {
        UnitSystem.METRIC -> "${km.roundToInt()} km"
        UnitSystem.IMPERIAL -> "${(km / KM_PER_MILE).roundToInt()} mi"
    }
}

/** RangeEstimator ile ayni donusum. */
private const val KM_PER_MILE = 1.609344

private fun formatCapacity(vehicle: VehicleProfile?): String {
    val capacity = vehicle?.batteryCapacityKwh ?: return UNKNOWN
    if (capacity <= 0.0) return UNKNOWN
    return "${capacity.roundToInt()} kWh"
}

/**
 * Verinin kaynagi ve yasi.
 *
 * Kaynak tek basina yetmez: "OBD" yazmasi verinin TAZE oldugu anlamina
 * gelmez, dongle baglantisi kopmus olabilir. Bu yuzden ikisi birlikte.
 *
 * @Composable, cunku metinlerin tamami cevrilebilir olmali; bu ekran
 * bes dilde yayinlaniyor.
 */
@Composable
private fun sourceLabel(telemetry: VehicleTelemetry?): String {
    // Canli okuma yoksa gosterilen sayilar profilden geliyor demektir;
    // "—" yazmak, degerlerin nereden geldigini gizlerdi.
    if (telemetry == null) return stringResource(R.string.hud_source_manual)

    val source = stringResource(
        when (telemetry.source) {
            TelemetrySource.OEM_CLOUD -> R.string.hud_source_oem
            TelemetrySource.ANDROID_AUTOMOTIVE -> R.string.hud_source_vehicle
            TelemetrySource.OBD_DONGLE -> R.string.hud_source_obd
            TelemetrySource.MANUAL -> R.string.hud_source_manual
        },
    )

    if (telemetry.isStale()) {
        return "$source · ${stringResource(R.string.hud_stale)}"
    }

    val minutes = Duration.between(telemetry.capturedAt, Instant.now()).toMinutes()
    val age = if (minutes < 1) {
        stringResource(R.string.hud_live)
    } else {
        stringResource(R.string.hud_minutes_ago, minutes)
    }
    return "$source · $age"
}
