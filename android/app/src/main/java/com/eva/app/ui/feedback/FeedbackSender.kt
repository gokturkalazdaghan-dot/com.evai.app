// android/app/src/main/java/com/eva/app/ui/feedback/FeedbackSender.kt
package com.eva.app.ui.feedback

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.getSystemService
import com.eva.app.BuildConfig

/** Geri bildirimlerin gidecegi adres. */
const val FEEDBACK_EMAIL = "gokturkalazdaghan@gmail.com"

/** Geri bildirim gonderme denemesinin sonucu. */
sealed class FeedbackResult {
    /** E-posta uygulamasi acildi; gonderme karari KULLANICININ. */
    data object ComposerOpened : FeedbackResult()

    /**
     * Cihazda e-posta uygulamasi yok. Metin panoya kopyalandi ki
     * kullanici baska bir yolla iletebilsin -- sessizce basarisiz
     * olmaktansa elinde bir sey kalmali.
     */
    data object CopiedToClipboard : FeedbackResult()

    /** Ne acilabildi ne kopyalanabildi. */
    data class Failed(val userMessage: String) : FeedbackResult()
}

/**
 * Geri bildirim akisini baslatir.
 *
 * NEDEN DOGRUDAN GONDERMIYORUZ
 * ----------------------------
 * Uygulama kullanici adina e-posta GONDERMEZ; yalnizca hazir bir taslakla
 * e-posta uygulamasini acar. Gonder'e basmak kullanicinin karari olmali:
 * ne yazdigini gormeden mesaj gitmesi, kendi adresinden habersiz posta
 * cikmasi demektir.
 *
 * Sunucu uzerinden gondermek de bilincli olarak tercih edilmedi: bu,
 * uygulamaya bir e-posta kimlik bilgisi ya da acik bir "mesaj gonder"
 * ucu koymak demek olurdu -- ikisi de suistimal edilebilir.
 *
 * TESHIS BILGISI
 * --------------
 * Govdeye surum/cihaz/Android bilgisi eklenir; "calismiyor" diyen bir
 * raporun tek basina hicbir ise yaramadigi olculdu. Konum, kimlik ya da
 * hesap bilgisi EKLENMEZ.
 */
fun sendFeedback(
    context: Context,
    subject: String = "EVA AI geri bildirim",
    userNote: String = "",
): FeedbackResult {
    val body = buildFeedbackBody(userNote)

    // ACTION_SENDTO + mailto: yalnizca e-posta uygulamalarini hedefler.
    // ACTION_SEND kullanilsaydi Android, WhatsApp/Drive gibi her turlu
    // paylasim hedefini listeler ve kullanici e-posta yerine oraya
    // gonderirdi.
    val intent = Intent(Intent.ACTION_SENDTO).apply {
        data = Uri.parse("mailto:")
        putExtra(Intent.EXTRA_EMAIL, arrayOf(FEEDBACK_EMAIL))
        putExtra(Intent.EXTRA_SUBJECT, subject)
        putExtra(Intent.EXTRA_TEXT, body)
    }

    return try {
        // Android 11+ paket gorunurlugu kisitlamalari yuzunden
        // resolveActivity() e-posta uygulamasi KURULU OLSA BILE null
        // donebilir (manifest'te <queries> tanimi gerekir). Bu yuzden
        // once dogrudan baslatilir, basarisizlik yakalanir.
        context.startActivity(Intent.createChooser(intent, "Geri bildirim gönder"))
        FeedbackResult.ComposerOpened
    } catch (e: android.content.ActivityNotFoundException) {
        copyToClipboard(context, body)
    } catch (e: Exception) {
        copyToClipboard(context, body)
    }
}

private fun copyToClipboard(context: Context, body: String): FeedbackResult {
    val clipboard = context.getSystemService<ClipboardManager>()
        ?: return FeedbackResult.Failed(
            "Geri bildirim gönderilemedi. Bize $FEEDBACK_EMAIL adresinden yazabilirsin.",
        )

    return try {
        clipboard.setPrimaryClip(
            ClipData.newPlainText("EVA AI geri bildirim", "$FEEDBACK_EMAIL\n\n$body"),
        )
        FeedbackResult.CopiedToClipboard
    } catch (e: Exception) {
        FeedbackResult.Failed(
            "Geri bildirim gönderilemedi. Bize $FEEDBACK_EMAIL adresinden yazabilirsin.",
        )
    }
}

/**
 * Mesaj govdesi: once kullanicinin yazacagi bos alan, sonra teshis.
 *
 * Teshis EN ALTTA: kullanici e-posta uygulamasini actiginda imleci
 * bekleyen bos bir alan gormeli, once teknik bir blok degil.
 */
private fun buildFeedbackBody(userNote: String): String = buildString {
    if (userNote.isNotBlank()) {
        appendLine(userNote)
    } else {
        appendLine("Yaşadığın sorunu ya da öneriyi buraya yazabilirsin:")
        appendLine()
        appendLine()
    }
    appendLine()
    appendLine("---")
    appendLine("Aşağıdaki bilgiler sorunu bulmamıza yardım eder, silmezsen seviniriz.")
    appendLine("Uygulama: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
    appendLine("Cihaz: ${Build.MANUFACTURER} ${Build.MODEL}")
    appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
}
