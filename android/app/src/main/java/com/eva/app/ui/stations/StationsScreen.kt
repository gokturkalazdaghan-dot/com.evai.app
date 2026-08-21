// android/app/src/main/java/com/eva/app/ui/stations/StationsScreen.kt
package com.eva.app.ui.stations

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Map
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TopAppBar
import com.eva.app.core.AppConfig
import com.eva.app.ui.map.StationMap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import com.eva.app.R
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Şarj istasyonu listesi ana ekranı. StationsViewModel'in ürettiği
 * StationsUiState'e göre yükleniyor/boş/hata/liste durumlarını gösterir —
 * hiçbir durum sessizce atlanmaz, her biri kullanıcıya açık bir geri
 * bildirimle sunulur.
 */
// TopAppBar hala deneysel Material3 API'si; opt-in olmadan derlenmez.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StationsScreen(
    viewModel: StationsViewModel,
    onStationSelected: (StationDto) -> Unit,
    onRetryRequested: () -> Unit,
    userLat: Double,
    userLon: Double,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsState()
    val unitSystem by viewModel.unitSystem.collectAsState()

    // Harita/liste gorunumu. Harita yalnizca gecerli bir MAPS_API_KEY
    // varken anlamli oldugu icin dugme de yalnizca o zaman gosterilir --
    // aksi halde kullaniciya hicbir sey yapmayan bir dugme sunulurdu.
    var showMap by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.stations_title)) },
                actions = {
                    if (AppConfig.isMapEnabled) {
                        IconButton(onClick = { showMap = !showMap }) {
                            Icon(
                                imageVector = if (showMap) {
                                    Icons.AutoMirrored.Filled.List
                                } else {
                                    Icons.Filled.Map
                                },
                                contentDescription = if (showMap) "Liste görünümü" else "Harita görünümü",
                            )
                        }
                    }
                },
            )
        },
        modifier = modifier,
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
        ) {
            when (val state = uiState) {
                is StationsUiState.Loading -> {
                    StationsLoadingIndicator()
                }

                is StationsUiState.Loaded -> {
                    if (showMap) {
                        // Tam ekran harita: bu sekmede harita ANA gorunum
                        // olabilir, panelde ise yalnizca bir seritti.
                        StationMap(
                            userLat = userLat,
                            userLon = userLon,
                            stations = state.stations,
                            onStationSelected = onStationSelected,
                            modifier = Modifier.fillMaxSize(),
                            height = Dp.Unspecified,
                        )
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            // Cevrimdisi uyarisi listenin BASINDA: kullanici
                            // fiyatlari gormeden once verinin eski oldugunu
                            // bilmeli.
                            state.offlineSinceEpochMs?.let { fetchedAt ->
                                item(key = "offline-banner") {
                                    OfflineBanner(
                                        fetchedAtEpochMs = fetchedAt,
                                        onRetry = onRetryRequested,
                                        modifier = Modifier.padding(
                                            horizontal = 16.dp,
                                            vertical = 8.dp,
                                        ),
                                    )
                                }
                            }

                            items(state.stations, key = { it.stationId }) { station ->
                                StationListItem(
                                    station = station,
                                    unitSystem = unitSystem,
                                    onClick = onStationSelected,
                                )
                            }
                        }
                    }
                }

                is StationsUiState.Empty -> {
                    StationsMessageState(
                        message = state.message,
                        showRetry = false,
                        onRetryRequested = onRetryRequested,
                    )
                }

                is StationsUiState.Error -> {
                    StationsMessageState(
                        message = state.message,
                        showRetry = true,
                        onRetryRequested = onRetryRequested,
                    )
                }
            }
        }
    }
}

@Composable
private fun StationsMessageState(
    message: String,
    showRetry: Boolean,
    onRetryRequested: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        if (showRetry) {
            Button(
                onClick = onRetryRequested,
                modifier = Modifier.padding(top = 16.dp),
            ) {
                Text("Tekrar Dene")
            }
        }
    }
}
