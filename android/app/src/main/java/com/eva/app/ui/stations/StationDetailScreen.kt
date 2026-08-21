// android/app/src/main/java/com/eva/app/ui/stations/StationDetailScreen.kt
package com.eva.app.ui.stations

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import android.widget.Toast
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material3.Button
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.eva.app.R
import com.eva.app.navigation.NavigationLauncher
import com.eva.app.navigation.NavigationResult
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Tek bir istasyonun detay ekrani.
 *
 * Bu ekran daha once YOKTU: hem panelde hem istasyon listesinde bir
 * istasyona dokunmak hicbir sey yapmiyordu (`onStationSelected = { }`).
 * Surucunun "buraya gideyim mi" karari icin gereken bilgiler burada
 * toplaniyor: fiyat, mesafe, guc ve arac uyumlulugu.
 */
// FlowRow (foundation) ve TopAppBar (material3) hala deneysel API'ler.
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun StationDetailScreen(
    station: StationDto?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(station?.name ?: "İstasyon") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Geri")
                    }
                },
            )
        },
    ) { padding ->
        if (station == null) {
            // Yapilandirma degisikliginden (orn. ekran dondurme) sonra secim
            // kaybolabilir; bos ekran yerine acik bir mesaj gosteriyoruz.
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    stringResource(R.string.station_not_found),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onBack) { Text(stringResource(R.string.action_back_to_list)) }
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
        ) {
            PriceHeadline(station)

            Spacer(Modifier.height(20.dp))

            // NAVIGASYON TELEFONA DEVREDILIYOR.
            // Kendi rotamizi cizmek, Haritalar'in yillardir yaptigi isi
            // bastan yazmak olurdu: donus donus sesli yonlendirme, canli
            // trafik, yoldan cikinca yeniden hesaplama. Bizim isimiz dogru
            // istasyonu bulmak; oraya nasil gidilecegi telefonun isi.
            NavigateButton(station)

            Spacer(Modifier.height(20.dp))

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(20.dp)) {
                    DetailRow(
                        icon = Icons.Filled.NearMe,
                        label = "Mesafe",
                        value = formatDistance(station.distanceMeters, unitSystemFor()),
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 12.dp),
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                    DetailRow(
                        icon = Icons.Filled.Bolt,
                        label = stringResource(R.string.label_max_power),
                        value = "${station.maxPowerKw.toInt()} kW",
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 12.dp),
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                    DetailRow(
                        icon = Icons.Filled.Payments,
                        label = stringResource(R.string.label_operator),
                        value = station.cpoDisplayName.ifBlank { "Bilinmiyor" },
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            Text("Soketler", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))

            if (station.connectors.isEmpty()) {
                Text(
                    "Bu istasyon için soket bilgisi yok.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    station.connectors.forEach { connector ->
                        ConnectorChip(connector)
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            Text(
                // Guven skoru istasyon verisinin ne kadar dogrulanmis
                // oldugunu gosterir; kullaniciya ham sayi degil, anlami
                // sunuluyor.
                confidenceLabel(station.dataConfidenceScore),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun PriceHeadline(station: StationDto) {
    Column {
        if (station.pricePerKwh != null) {
            Text(
                formatPricePerKwh(station.pricePerKwh, station.currency),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
        } else {
            // Fiyat bilinmiyorsa UYDURULMAZ; durum acikca yazilir.
            Text(
                "Fiyat bilgisi yok",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            station.status.let(::statusLabel),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DetailRow(icon: ImageVector, label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(
            value,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun ConnectorChip(connector: StationConnectorDto) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Box(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(
                "${connectorDisplayLabel(connector.connectorType)} · ${connector.powerKw.toInt()} kW",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

private fun statusLabel(status: String): String = when (status) {
    "OPERATIONAL" -> "Çalışıyor"
    "DEGRADED" -> "Kısmen çalışıyor"
    "OFFLINE" -> "Hizmet dışı"
    "PLANNED" -> "Planlanıyor"
    else -> "Durum bilinmiyor"
}

private fun confidenceLabel(score: Double): String = when {
    score >= 0.85 -> "Bu istasyonun verileri doğrulanmış kaynaklardan geliyor."
    score >= 0.5 -> "Bu istasyonun verileri kısmen doğrulanmış."
    else -> "Bu istasyonun verileri düşük güvenilirlikte — yerinde teyit edin."
}

/**
 * Yol tarifini telefonun harita uygulamasinda acar.
 *
 * Harita uygulamasi yoksa buton sessizce hicbir sey YAPMAZ demek yerine
 * kullaniciya durumu soyler -- calismayan bir buton, bozuk bir uygulama
 * izlenimi birakir.
 */
@Composable
private fun NavigateButton(station: StationDto) {
    val context = LocalContext.current
    val launcher = remember(context) { NavigationLauncher(context) }

    Button(
        onClick = {
            when (launcher.navigateTo(station.lat, station.lon, station.name)) {
                is NavigationResult.Launched -> Unit
                is NavigationResult.NoMapsApp -> Toast.makeText(
                    context,
                    context.getString(R.string.navigation_no_maps_app),
                    Toast.LENGTH_LONG,
                ).show()
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .height(54.dp),
        shape = RoundedCornerShape(16.dp),
    ) {
        Icon(
            Icons.Filled.Navigation,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            stringResource(R.string.navigation_start),
            style = MaterialTheme.typography.titleMedium,
        )
    }
}
