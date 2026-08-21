// android/app/src/main/java/com/eva/app/ui/feedback/FeedbackButton.kt
package com.eva.app.ui.feedback

import android.widget.Toast
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.eva.app.R
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * "Geri bildirim gönder" butonu.
 *
 * Her sonuc icin kullaniciya GERI BILDIRIM verilir -- e-posta uygulamasi
 * acilamadiginda hicbir sey olmamis gibi durmak, butonun bozuk oldugu
 * izlenimini birakir.
 */
@Composable
fun FeedbackButton(modifier: Modifier = Modifier) {
    val context = LocalContext.current

    OutlinedButton(
        onClick = {
            when (val result = sendFeedback(context)) {
                is FeedbackResult.ComposerOpened -> Unit // Ekran zaten acildi.

                is FeedbackResult.CopiedToClipboard -> Toast.makeText(
                    context,
                    context.getString(R.string.feedback_copied),
                    Toast.LENGTH_LONG,
                ).show()

                is FeedbackResult.Failed -> Toast.makeText(
                    context,
                    result.userMessage,
                    Toast.LENGTH_LONG,
                ).show()
            }
        },
        modifier = modifier.fillMaxWidth(),
    ) {
        Icon(
            Icons.AutoMirrored.Filled.Send,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.size(8.dp))
        Text(stringResource(R.string.feedback_send))
    }
}
