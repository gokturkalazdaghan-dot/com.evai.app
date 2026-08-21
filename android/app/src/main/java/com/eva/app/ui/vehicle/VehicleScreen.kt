// android/app/src/main/java/com/eva/app/ui/vehicle/VehicleScreen.kt
package com.eva.app.ui.vehicle

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Power
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.eva.app.R
import com.eva.app.ui.feedback.FeedbackButton
import com.eva.app.vehicle.telemetry.TelemetryConnection
import com.eva.app.vehicle.telemetry.VehicleTelemetry
import com.eva.app.vehicle.ConnectorOption
import com.eva.app.vehicle.VehicleProfile
import kotlin.math.roundToInt

/**
 * Aracın bilgilerini gösterir ve düzenlemeye izin verir.
 *
 * Bu ekran, alt navigasyondaki "Eva" sekmesinin yerini aldı. Sebep: Eva
 * artık dokunmayla değil, "Eva" diye seslenilerek çağrılıyor — sesli
 * asistan için ayrı bir sekme gereksizdi. Buna karşılık araç bilgileri
 * yalnızca ilk açılıştaki onboarding diyaloğunda girilebiliyordu ve
 * sonradan DEĞİŞTİRİLEMİYORDU (şarj yüzdesi her yolculukta değişir).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun VehicleScreen(
    viewModel: VehicleOnboardingViewModel,
    currentVehicle: VehicleProfile?,
    modifier: Modifier = Modifier,
    telemetryConnection: TelemetryConnection = TelemetryConnection.NotConnected,
    telemetry: VehicleTelemetry? = null,
) {
    val formState by viewModel.formState.collectAsStateWithLifecycle()
    var savedNotice by remember { mutableStateOf(false) }

    // Kayıtlı araç varsa formu onunla doldur; kullanıcı "düzenleme"
    // yaptığını hissetmeli, boş bir forma yeniden veri girdiğini değil.
    // ViewModel'de hazır bir prefill metodu yok, mevcut setter'lar
    // kullanılıyor.
    LaunchedEffect(currentVehicle) {
        currentVehicle?.let { v ->
            viewModel.onBrandChanged(v.brand)
            viewModel.onModelChanged(v.model)
            viewModel.onBatteryCapacityChanged(v.batteryCapacityKwh.toString())
            viewModel.onChargePercentChanged(v.currentChargePercent.toString())
            ConnectorOption.entries
                .firstOrNull { it.backendValue == v.connectorType }
                ?.let(viewModel::onConnectorSelected)
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text(stringResource(R.string.vehicle_title)) }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
        ) {
            if (currentVehicle != null) {
                VehicleSummaryCard(currentVehicle)
                Spacer(Modifier.height(24.dp))
            }

            Text(
                if (currentVehicle == null) "Aracını tanıt" else "Bilgileri güncelle",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = formState.brand,
                onValueChange = viewModel::onBrandChanged,
                label = { Text("Marka") },
                placeholder = { Text(stringResource(R.string.vehicle_brand_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = formState.model,
                onValueChange = viewModel::onModelChanged,
                label = { Text("Model") },
                placeholder = { Text(stringResource(R.string.vehicle_model_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = formState.batteryCapacityKwhText,
                onValueChange = viewModel::onBatteryCapacityChanged,
                label = { Text("Batarya kapasitesi (kWh)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(20.dp))
            Text("Soket tipi", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))

            // Soket tipi kritik: Gateway istasyonları buna göre eliyor.
            // Yanlış seçim, aracına takamayacağın istasyonların önerilmesi
            // ya da uygun olanların gizlenmesi demektir.
            // FlowRow: "CCS2 (Avrupa Standardı)" gibi uzun etiketler tek
            // satira sigmaz, tasan chip'ler dokunulamaz hale gelirdi.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ConnectorOption.entries.forEach { option ->
                    FilterChip(
                        selected = formState.selectedConnector == option,
                        onClick = { viewModel.onConnectorSelected(option) },
                        label = { Text(option.displayLabel) },
                    )
                }
            }

            Spacer(Modifier.height(24.dp))

            Text(
                stringResource(R.string.vehicle_charge_state),
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(Modifier.height(8.dp))

            TelemetryStatusCard(
                connection = telemetryConnection,
                telemetry = telemetry,
            )

            // CANLI VERI VARKEN KAYDIRICI GIZLENIR.
            // Iki kaynak ayni anda gorunurse kullanici hangisinin gecerli
            // oldugunu bilemez ve elle girdigi deger bir sonraki okumada
            // sessizce ezilir -- girisi bosa cikmis olur.
            val hasLiveReading = telemetry?.hasUsableBattery == true
            if (!hasLiveReading) {
                Spacer(Modifier.height(16.dp))
                val charge = formState.currentChargePercentText.toIntOrNull() ?: 80
                Text(
                    "Mevcut şarj: %$charge",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Slider(
                    value = charge.toFloat(),
                    onValueChange = {
                        viewModel.onChargePercentChanged(it.roundToInt().toString())
                    },
                    valueRange = 0f..100f,
                    steps = 19,
                )
            }

            Spacer(Modifier.height(24.dp))

            Button(
                onClick = {
                    viewModel.submit { savedNotice = true }
                },
                enabled = formState.isValid,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (currentVehicle == null) "Eva'ya tanıt" else "Değişiklikleri kaydet")
            }

            if (savedNotice) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "Eva artık aracının güncel bilgilerini biliyor.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            Spacer(Modifier.height(32.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            Text(
                stringResource(R.string.feedback_prompt),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            FeedbackButton()

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun VehicleSummaryCard(vehicle: VehicleProfile) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.DirectionsCar,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(32.dp),
                )
                Spacer(Modifier.size(12.dp))
                Column {
                    Text(
                        "${vehicle.brand} ${vehicle.model}".trim(),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Text(
                        "${vehicle.batteryCapacityKwh.toInt()} kWh",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 16.dp),
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.2f),
            )

            SummaryRow(
                icon = Icons.Filled.BatteryChargingFull,
                label = stringResource(R.string.vehicle_current_charge),
                value = "%${vehicle.currentChargePercent}",
            )
            Spacer(Modifier.height(8.dp))
            SummaryRow(
                icon = Icons.Filled.Power,
                label = stringResource(R.string.label_connector),
                value = vehicle.connectorType,
            )
        }
    }
}

@Composable
private fun SummaryRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.size(10.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.weight(1f),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}
