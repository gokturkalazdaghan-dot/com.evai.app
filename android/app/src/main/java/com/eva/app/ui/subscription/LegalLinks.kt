// android/app/src/main/java/com/eva/app/ui/subscription/LegalLinks.kt
package com.eva.app.ui.subscription

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.eva.app.BuildConfig
import com.eva.app.R

/**
 * Gizlilik politikası ve kullanım şartlarına uygulama içi bağlantılar.
 *
 * NEDEN ZORUNLU
 * -------------
 * Google Play, abonelik satan uygulamalarda bu iki belgenin uygulama
 * İÇİNDEN erişilebilir olmasını şart koşuyor. Yalnızca mağaza sayfasında
 * bağlantı vermek yeterli değil; incelemede reddedilme sebebi.
 *
 * Adresler derleme yapılandırmasından gelir (BuildConfig) — koda gömülü
 * değil. Alan adı değiştiğinde uygulamayı yeniden yazmak gerekmez.
 */
@Composable
fun LegalLinks(modifier: Modifier = Modifier) {
    val context = LocalContext.current

    fun open(url: String) {
        if (url.isBlank()) {
            // Adres yapılandırılmamışsa sessizce çalışmayan bir bağlantı
            // göstermektense kullanıcıya durumu söylüyoruz.
            Toast.makeText(
                context,
                context.getString(R.string.legal_link_unavailable),
                Toast.LENGTH_SHORT,
            ).show()
            return
        }

        try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(
                context,
                context.getString(R.string.legal_link_no_browser),
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
    ) {
        TextButton(onClick = { open(BuildConfig.PRIVACY_POLICY_URL) }) {
            Text(
                stringResource(R.string.legal_privacy_policy),
                style = MaterialTheme.typography.labelMedium,
            )
        }
        TextButton(onClick = { open(BuildConfig.TERMS_OF_SERVICE_URL) }) {
            Text(
                stringResource(R.string.legal_terms_of_service),
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}
