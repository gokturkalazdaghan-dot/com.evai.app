// android/app/src/main/java/com/eva/app/ui/map/PriceMarkerIcon.kt
package com.eva.app.ui.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import kotlin.math.max

// Rozet olculeri (dp degil px -- Bitmap dogrudan piksel uzerinde cizilir).
private const val TEXT_SIZE_PX = 34f
private const val PADDING_X_PX = 22f
private const val PADDING_Y_PX = 14f
private const val CORNER_RADIUS_PX = 20f
private const val TAIL_WIDTH_PX = 22f
private const val TAIL_HEIGHT_PX = 16f
private const val ACCENT_DOT_RADIUS_PX = 9f
private const val ACCENT_GAP_PX = 12f
private const val BORDER_WIDTH_PX = 3f

private const val COLOR_SURFACE = 0xFF161922.toInt()
private const val COLOR_TEXT = 0xFFFFFFFF.toInt()
/** En ucuz istasyon: en parlak mavi. */
private const val COLOR_CHEAPEST = 0xFF00F0FF.toInt()
/** Digerleri: daha soluk mavi -- en ucuzla karismasin. */
private const val COLOR_OTHER = 0xFF38BDF8.toInt()
private const val COLOR_MUTED = 0xFF6B7280.toInt()

/**
 * Fiyati DOGRUDAN harita uzerinde gosteren isaretci.
 *
 * NEDEN OZEL BITMAP
 * -----------------
 * Varsayilan Google pini yalnizca renk tasiyabilir; fiyati gormek icin
 * kullanicinin her pine tek tek dokunup balonu acmasi gerekiyordu.
 * Surucunun aradigi tek bilgi "hangisi kac para" oldugu icin fiyat
 * pinin UZERINDE yaziyor.
 *
 * FIYATI BILINMEYEN ISTASYON
 * --------------------------
 * Rozet "—" gosterir ve soluk renkte cizilir. Bos birakmak ya da sifir
 * yazmak, bilinmeyen fiyati "bedava"ya benzetirdi.
 */
@Composable
fun rememberPriceMarkerIcon(
    priceLabel: String?,
    isCheapest: Boolean,
): BitmapDescriptor = remember(priceLabel, isCheapest) {
    buildPriceMarker(priceLabel ?: UNKNOWN_PRICE_LABEL, isCheapest, priceLabel != null)
}

const val UNKNOWN_PRICE_LABEL = "—"

private fun buildPriceMarker(
    label: String,
    isCheapest: Boolean,
    isPriceKnown: Boolean,
): BitmapDescriptor {
    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (isPriceKnown) COLOR_TEXT else COLOR_MUTED
        textSize = TEXT_SIZE_PX
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    val accentColor = when {
        !isPriceKnown -> COLOR_MUTED
        isCheapest -> COLOR_CHEAPEST
        else -> COLOR_OTHER
    }

    val textWidth = textPaint.measureText(label)
    val fontMetrics = textPaint.fontMetrics
    val textHeight = fontMetrics.descent - fontMetrics.ascent

    val badgeWidth =
        PADDING_X_PX * 2 + ACCENT_DOT_RADIUS_PX * 2 + ACCENT_GAP_PX + textWidth
    val badgeHeight = max(PADDING_Y_PX * 2 + textHeight, ACCENT_DOT_RADIUS_PX * 2 + PADDING_Y_PX)

    val bitmap = Bitmap.createBitmap(
        Math.ceil(badgeWidth.toDouble()).toInt(),
        Math.ceil((badgeHeight + TAIL_HEIGHT_PX).toDouble()).toInt(),
        Bitmap.Config.ARGB_8888,
    )
    val canvas = Canvas(bitmap)

    val bodyRect = RectF(0f, 0f, badgeWidth, badgeHeight)

    // Govde
    val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = COLOR_SURFACE }
    canvas.drawRoundRect(bodyRect, CORNER_RADIUS_PX, CORNER_RADIUS_PX, bodyPaint)

    // Neon kenar: pinleri koyu harita zemininden ayirir.
    val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = BORDER_WIDTH_PX
        color = accentColor
    }
    val inset = BORDER_WIDTH_PX / 2
    canvas.drawRoundRect(
        RectF(inset, inset, badgeWidth - inset, badgeHeight - inset),
        CORNER_RADIUS_PX,
        CORNER_RADIUS_PX,
        borderPaint,
    )

    // Kuyruk: rozetin tam olarak istasyonun uzerini gosterdigini belli eder.
    val tailPath = Path().apply {
        moveTo(badgeWidth / 2 - TAIL_WIDTH_PX / 2, badgeHeight - 1f)
        lineTo(badgeWidth / 2 + TAIL_WIDTH_PX / 2, badgeHeight - 1f)
        lineTo(badgeWidth / 2, badgeHeight + TAIL_HEIGHT_PX)
        close()
    }
    canvas.drawPath(tailPath, bodyPaint)
    canvas.drawPath(tailPath, borderPaint)

    // Vurgu noktasi
    val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accentColor }
    canvas.drawCircle(
        PADDING_X_PX + ACCENT_DOT_RADIUS_PX,
        badgeHeight / 2,
        ACCENT_DOT_RADIUS_PX,
        dotPaint,
    )

    // Metin dikeyde ortalanir (baseline hesabi).
    val baseline = badgeHeight / 2 - (fontMetrics.ascent + fontMetrics.descent) / 2
    canvas.drawText(
        label,
        PADDING_X_PX + ACCENT_DOT_RADIUS_PX * 2 + ACCENT_GAP_PX,
        baseline,
        textPaint,
    )

    return BitmapDescriptorFactory.fromBitmap(bitmap)
}
