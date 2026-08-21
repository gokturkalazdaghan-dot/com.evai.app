// android/app/src/main/java/com/eva/app/ui/theme/Theme.kt
package com.eva.app.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * ELEKTRO-ATOM TEMASI
 *
 * Uygulama artik tek bir gorunume commit ediyor: neon elektrik mavisi
 * uzerine derin koyu zemin. Sebep: bu bir arac ici uygulama -- surus
 * sirasinda koyu zemin gozu yormaz ve neon vurgular (fiyat, doluluk)
 * bir bakista okunur.
 *
 * DINAMIK RENK KAPATILDI: Material You, cihazin duvar kagidina gore
 * paleti degistirir. Marka kimligi ve kontrast garantisi bu tema icin
 * daha onemli -- pastel bir duvar kagidi neon vurgulari silikleştirirdi.
 */
private val ElectricDarkColorScheme = darkColorScheme(
    primary = Color(0xFF00E5FF),              // neon elektrik mavisi
    onPrimary = Color(0xFF0A0B10),
    primaryContainer = Color(0xFF00363D),
    onPrimaryContainer = Color(0xFF6FF7FF),
    // TEK AKSAN AILESI: elektrik mavisi.
    // Once mor, sonra neon yesil denendi; ikisi de palete yabanci bir
    // leke birakiyordu. Tek bir renk ailesinde kalmak, "hangi vurgu
    // onemli?" belirsizligini ortadan kaldiriyor. Kirmizi yalnizca
    // HATA icin ayrildi.
    secondary = Color(0xFF38BDF8),            // acik gok mavisi
    onSecondary = Color(0xFF04202B),
    secondaryContainer = Color(0xFF0B2E33),   // koyu teal -- secili sekme zemini
    onSecondaryContainer = Color(0xFF6FF7FF),
    tertiary = Color(0xFF7DD3FC),             // en acik mavi -- vurgu
    onTertiary = Color(0xFF0A0B10),
    background = Color(0xFF0A0B10),           // derin koyu zemin
    onBackground = Color(0xFFE0E0E0),
    surface = Color(0xFF161921),              // kart yuzeyi
    onSurface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFF1E222C),
    onSurfaceVariant = Color(0xFF9BA1AE),
    surfaceContainer = Color(0xFF161921),
    surfaceContainerHigh = Color(0xFF1E222C),
    surfaceContainerHighest = Color(0xFF262B38),
    outline = Color(0xFF3A404E),
    outlineVariant = Color(0xFF262B38),
    error = Color(0xFFFF5370),
    onError = Color(0xFF0A0B10),
    errorContainer = Color(0xFF4A0E1B),
    onErrorContainer = Color(0xFFFFB3C0),
)

@Composable
fun EvaTheme(
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = ElectricDarkColorScheme,
        typography = Typography,
        content = content,
    )
}
