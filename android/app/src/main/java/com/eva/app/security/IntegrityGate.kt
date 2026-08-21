// android/app/src/main/java/com/eva/app/security/IntegrityGate.kt
package com.eva.app.security

import android.content.Context
import android.util.Log

private const val TAG = "IntegrityGate"

enum class IntegrityDecision {
    /** Cihaz/uygulama bütünlüğü doğrulandı — normal akışa devam. */
    ALLOWED,
    /** Play Store dışından yüklenmiş ya da imza uyuşmuyor. */
    UNRECOGNIZED_APP,
    /** Cihaz rootlanmış / kurcalanmış / emülatör. */
    COMPROMISED_DEVICE,
    /** Play Integrity API'ye ulaşılamadı (ağ yok vb.) — geçici. */
    UNKNOWN_TRANSIENT,
}

/**
 * Play Integrity token'ının GERÇEK doğrulaması Gateway'de (Google'ın
 * sunucusuna karşı) yapılır — bkz. PlayIntegrityVerifierService.kt.
 * Bu sınıf, istemci tarafında yalnızca "token alınabildi mi, temel
 * formatı doğru mu" kontrolünü yapar ve DeviceIntegrityChecker'ın
 * heuristic sonucuyla birleştirerek bir UX kararı üretir (örn.
 * "rootlu görünen cihazda abonelik ekranını gösterme, kullanıcıyı
 * uyar"). Nihai yetkilendirme kararı HER ZAMAN Gateway'de verilir —
 * istemci tarafındaki bu karar yalnızca kullanıcı deneyimini iyileştirir,
 * güvenlik sınırı DEĞİLDİR (istemci tarafı kod her zaman atlatılabilir).
 */
class IntegrityGate(private val context: Context) {

    fun evaluateLocalHeuristics(): IntegrityDecision {
        val hasRoot = DeviceIntegrityChecker.hasRootIndicators(context)
        val isEmulator = DeviceIntegrityChecker.hasEmulatorIndicators()

        return when {
            hasRoot || isEmulator -> {
                Log.w(TAG, "Yerel heuristic uyarısı: root=$hasRoot, emulator=$isEmulator")
                IntegrityDecision.COMPROMISED_DEVICE
            }
            else -> IntegrityDecision.ALLOWED
        }
    }

    /**
     * PlayIntegrityManager'dan alınan ham token'ı, Gateway'e göndermeden
     * önce temel bir yapı kontrolünden geçirir. Play Integrity token'ları
     * şifreli/imzalı JWE'ler olduğu için istemci bunları ÇÖZEMEZ (bu
     * kasıtlı bir Google tasarımı — istemci sonucu göremez, yalnızca
     * Gateway Google'ın sunucusuna göndererek çözebilir). Burada yapılan
     * yalnızca "token boş değil, makul uzunlukta" kontrolüdür.
     */
    fun isTokenStructurallyValid(token: String): Boolean {
        return token.isNotBlank() && token.length > 32
    }
}
