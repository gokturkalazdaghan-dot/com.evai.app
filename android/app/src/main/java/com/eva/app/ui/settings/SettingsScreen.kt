// android/app/src/main/java/com/eva/app/ui/settings/SettingsScreen.kt
package com.eva.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.eva.app.R
import com.eva.app.ui.subscription.LegalLinks

/**
 * Ayarlar — su an tek isi gizlilik.
 *
 * NEDEN VAR
 * ---------
 * Gizlilik politikamiz kullaniciya "Ayarlar → Verilerimi sil" yolunu
 * ACIKCA vaat ediyor ve Google Play, veri toplayan uygulamalarda uygulama
 * ICINDEN erisilebilen bir silme yolunu ZORUNLU tutuyor. Bu ekran olmadan
 * politika, karsiligi olmayan bir soz veriyordu.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.deletionState.collectAsStateWithLifecycle()
    var showConfirmation by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(Modifier.height(4.dp))

            LegalLinks()

            PrivacyCard(
                state = state,
                onDeleteClick = { showConfirmation = true },
                onDismissError = viewModel::dismissError,
            )

            Spacer(Modifier.height(24.dp))
        }
    }

    if (showConfirmation) {
        ConfirmDeletionDialog(
            onConfirm = {
                showConfirmation = false
                viewModel.deleteMyData()
            },
            onDismiss = { showConfirmation = false },
        )
    }
}

@Composable
private fun PrivacyCard(
    state: DeletionUiState,
    onDeleteClick: () -> Unit,
    onDismissError: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(R.string.settings_delete_data_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                stringResource(R.string.settings_delete_data_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            when (state) {
                DeletionUiState.Idle -> DeleteButton(onDeleteClick)

                DeletionUiState.InProgress -> {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    Text(
                        stringResource(R.string.settings_delete_data_progress),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                is DeletionUiState.Done -> {
                    Text(
                        stringResource(R.string.settings_delete_data_done),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    // ABONELIK KAYDI KORUNDUYSA SOYLENIR.
                    // "Verilerini sildim" deyip sessizce kayit tutmak,
                    // hic silmemekten daha kotudur.
                    if (state.subscriptionRetained) {
                        Text(
                            stringResource(R.string.settings_delete_data_subscription_kept),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                is DeletionUiState.Error -> {
                    Text(
                        stringResource(R.string.settings_delete_data_error, state.reason),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    DeleteButton(onDismissError, labelRes = R.string.retry)
                }
            }
        }
    }
}

@Composable
private fun DeleteButton(
    onClick: () -> Unit,
    labelRes: Int = R.string.settings_delete_data_action,
) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.error,
            contentColor = MaterialTheme.colorScheme.onError,
        ),
    ) {
        Text(stringResource(labelRes))
    }
}

/**
 * Onay penceresi.
 *
 * Geri alinamayan bir islem icin tek dokunus yeterli olmamali; yanlislikla
 * basilan bir buton kullanicinin arac profilini ve gecmisini siler.
 */
@Composable
private fun ConfirmDeletionDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_delete_confirm_title)) },
        text = { Text(stringResource(R.string.settings_delete_confirm_body)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    stringResource(R.string.settings_delete_data_action),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}
