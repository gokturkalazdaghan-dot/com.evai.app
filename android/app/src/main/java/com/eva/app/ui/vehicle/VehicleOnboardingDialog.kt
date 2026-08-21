// android/app/src/main/java/com/eva/app/ui/vehicle/VehicleOnboardingDialog.kt
package com.eva.app.ui.vehicle

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import com.eva.app.R
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import com.eva.app.vehicle.ConnectorOption

/**
 * Kayıtlı araç yoksa uygulama açılışında gösterilir (bkz. DashboardScreen).
 * Kapatılamaz (dismissible) DEĞİLDİR — kullanıcı bir araç kaydetmeden
 * dashboard'un geri kalanı anlamlı veri gösteremez (soket filtresi,
 * menzil hesabı vb. hiçbiri araçsız çalışmaz), bu yüzden bilinçli olarak
 * `onDismissRequest = {}` (boş) bırakıldı.
 */
@Composable
fun VehicleOnboardingDialog(
    viewModel: VehicleOnboardingViewModel,
    onCompleted: () -> Unit,
) {
    val formState by viewModel.formState.collectAsState()

    AlertDialog(
        onDismissRequest = { /* kasıtlı olarak boş — bkz. dosya başı yorumu */ },
        title = { Text(stringResource(R.string.onboarding_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Text(
                        text = viewModel.greetingMessage,
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }

                OutlinedTextField(
                    value = formState.brand,
                    onValueChange = viewModel::onBrandChanged,
                    label = { Text(stringResource(R.string.onboarding_brand)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )

                OutlinedTextField(
                    value = formState.model,
                    onValueChange = viewModel::onModelChanged,
                    label = { Text(stringResource(R.string.onboarding_model)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )

                OutlinedTextField(
                    value = formState.batteryCapacityKwhText,
                    onValueChange = viewModel::onBatteryCapacityChanged,
                    label = { Text("Batarya Kapasitesi (kWh)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )

                Text("Soket Tipi", style = MaterialTheme.typography.labelMedium)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(ConnectorOption.entries) { option ->
                        FilterChip(
                            selected = formState.selectedConnector == option,
                            onClick = { viewModel.onConnectorSelected(option) },
                            label = { Text(option.displayLabel) },
                        )
                    }
                }

                OutlinedTextField(
                    value = formState.currentChargePercentText,
                    onValueChange = viewModel::onChargePercentChanged,
                    label = { Text(stringResource(R.string.onboarding_charge)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { viewModel.submit(onCompleted) },
                enabled = formState.isValid,
            ) {
                Text(stringResource(R.string.onboarding_start))
            }
        },
        // dismissButton kasıtlı olarak yok — bkz. dosya başı yorumu.
    )
}
