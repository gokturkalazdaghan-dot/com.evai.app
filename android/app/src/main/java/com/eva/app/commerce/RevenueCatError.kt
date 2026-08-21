// android/app/src/main/java/com/eva/app/commerce/RevenueCatError.kt
package com.eva.app.commerce

/**
 * Abonelik katmani hatalari.
 *
 * IKI AYRI MESAJ -- NEDEN
 * -----------------------
 * `message` GELISTIRICI icindir: SDK'nin ham gerekcesini tasir ve log'a
 * yazilir. `userMessage` ise EKRANDA gosterilir.
 *
 * Ayrim olmadan RevenueCat'in Ingilizce teknik metni ("There was a
 * credentials issue. Check the underlying error for more details.")
 * dogrudan kullaniciya cikiyordu -- kullanicinin yapabilecegi hicbir sey
 * olmayan, anlamadigi bir cumle. Teknik gerekce log'da kalmali.
 */
sealed class RevenueCatError(message: String) : Exception(message) {

    /** Kullaniciya gosterilecek, eyleme donuk Turkce metin. */
    abstract val userMessage: String

    data class OfferingsUnavailable(val reason: String) :
        RevenueCatError("Abonelik paketleri (offerings) yüklenemedi: $reason") {
        // "Baglantini kontrol et" DEMIYOR: bu hata cogu zaman agdan
        // degil, magaza urunlerinin yapilandirilmasindan kaynaklanir
        // (RevenueCat "no products registered" dondurur). Kullaniciyi
        // duzeltemeyecegi bir seye yonlendirmek, cikissiz bir dongu olur.
        override val userMessage: String =
            "Abonelik seçenekleri şu anda kullanılamıyor. Birazdan tekrar dener misin?"
    }

    data class PackageNotFound(val identifier: String) :
        RevenueCatError("Belirtilen paket bulunamadı: $identifier") {
        override val userMessage: String =
            "Bu abonelik seçeneği şu anda kullanılamıyor."
    }

    data class PurchaseFailed(val reason: String, val userCancelled: Boolean = false) :
        RevenueCatError("Satın alma başarısız: $reason") {
        override val userMessage: String =
            // Kullanici kendi vazgectiyse bu bir HATA DEGILDIR; kirmizi
            // bir hata kutusu gostermek onu yanlis yaptigina inandirir.
            if (userCancelled) {
                "Satın alma iptal edildi."
            } else {
                "Satın alma tamamlanamadı. Google Play hesabını kontrol edip tekrar deneyebilirsin."
            }
    }

    data class RestoreFailed(val reason: String) :
        RevenueCatError("Satın almalar geri yüklenemedi: $reason") {
        override val userMessage: String =
            "Satın alımların geri yüklenemedi. Play Store'da doğru hesapla giriş yaptığından emin ol."
    }

    data object NotConfigured :
        RevenueCatError("RevenueCat SDK'sı henüz yapılandırılmadı (Purchases.configure çağrılmadı).") {
        override val userMessage: String =
            "Abonelik servisi şu anda kullanılamıyor."
    }
}

/**
 * Herhangi bir hatayi kullaniciya gosterilebilir metne cevirir.
 *
 * Bilinmeyen bir istisnanin `message`'i de teknik olabilir (sinif adi,
 * yigin izi parcasi), o yuzden disari verilmez.
 */
fun Throwable.toUserMessage(): String = when (this) {
    is RevenueCatError -> userMessage
    else -> "Beklenmeyen bir sorun oldu. Lütfen tekrar dene."
}
