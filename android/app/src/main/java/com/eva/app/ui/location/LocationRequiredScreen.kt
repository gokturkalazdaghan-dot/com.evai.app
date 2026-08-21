// android/app/src/main/java/com/eva/app/ui/location/LocationRequiredScreen.kt
package com.eva.app.ui.location

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOff
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.eva.app.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Konum bilinmiyorken gösterilir.
 *
 * NEDEN BİR "VARSAYILAN ŞEHİR" YOK
 * --------------------------------
 * Burada eskiden San Francisco vardı: konum izni verilmediğinde uygulama
 * sessizce başka bir kıtadaki istasyonları ve fiyatları "yakınındaki
 * istasyonlar" başlığı altında gösteriyordu. Sürücü bu fiyatlara göre
 * karar verebilir. Bilinmeyen konumu uydurmak yerine ne eksik olduğunu
 * söylemek doğru.
 */
@Composable
fun LocationRequiredScreen(
    onRequestPermission: () -> Unit,
    modifier: Modifier = Modifier,
    isPermanentlyDenied: Boolean = false,
    /**
     * Izin VAR ama cihaz fix uretemedi. Bu durumda "izin ver" butonu
     * gostermek cikissiz bir ekran olur -- kullanici zaten vermistir.
     */
    isPermissionGranted: Boolean = false,
) {
    val context = LocalContext.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Filled.LocationOff,
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.primary,
        )

        Spacer(Modifier.height(20.dp))

        Text(
            stringResource(
                if (isPermissionGranted) {
                    R.string.location_unavailable_title
                } else {
                    R.string.location_needed_title
                },
            ),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(10.dp))

        Text(
            stringResource(
                if (isPermissionGranted) {
                    R.string.location_unavailable_body
                } else {
                    R.string.location_needed_body
                },
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(28.dp))

        if (isPermissionGranted) {
            // Izin zaten var: yapilacak tek sey yeniden denemek.
            Button(onClick = onRequestPermission, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.action_retry))
            }
        } else if (isPermanentlyDenied) {
            // Kullanıcı "bir daha sorma" dediyse launchPermissionRequest
            // artık HİÇBİR ŞEY YAPMAZ — sessizce çalışmayan bir butonu
            // göstermektense doğrudan ayarlara yönlendiriyoruz.
            Button(
                onClick = {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", context.packageName, null),
                        ),
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.action_open_settings))
            }

            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.settings_permission_path),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Button(
                onClick = onRequestPermission,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.action_grant_location))
            }

            Spacer(Modifier.height(4.dp))
            TextButton(onClick = onRequestPermission) {
                Text(stringResource(R.string.action_retry))
            }
        }
    }
}
