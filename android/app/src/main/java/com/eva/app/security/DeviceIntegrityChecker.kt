// android/app/src/main/java/com/eva/app/security/DeviceIntegrityChecker.kt
package com.eva.app.security

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import java.io.File

/**
 * Cihaz üstü (yerel, hızlı) root/emülatör HEURISTIC'leri. Bu sınıfın
 * DÜRÜST sınırı: yerel heuristic kontroller kararlı bir saldırgan
 * tarafından atlatılabilir (Magisk Hide, root gizleme modülleri vb.) —
 * bu yüzden bu kontroller TEK BAŞINA güvenlik kararı vermek için
 * KULLANILMAMALI. Gerçek, sunucu tarafından doğrulanabilir güvenlik
 * sinyali Play Integrity API'sinin deviceRecognitionVerdict alanıdır
 * (bkz. IntegrityGate.kt) — çünkü o doğrulama Google'ın kendi
 * sunucusunda yapılır, cihazın kendi beyanına dayanmaz.
 *
 * Bu sınıf, Play Integrity sonucunu beklemeden ANINDA (ağ çağrısı
 * olmadan) kaba bir ön-filtre sağlar — örn. "açıkça rootlu bir cihazda
 * abonelik ekranını hiç gösterme" gibi UX kararları için. Kritik
 * yetkilendirme kararları (satın alma, Gateway isteği) HER ZAMAN
 * IntegrityGate'in Play Integrity tabanlı sonucuna dayanmalı.
 */
object DeviceIntegrityChecker {

    private val KNOWN_ROOT_APPS = listOf(
        "com.topjohnwu.magisk",
        "eu.chainfire.supersu",
        "com.noshufou.android.su",
        "com.koushikdutta.superuser",
        "com.thirdparty.superuser",
        "com.yellowes.su",
        "com.kingroot.kinguser",
        "com.kingo.root",
        "com.smedialink.oneclickroot",
    )

    private val KNOWN_ROOT_BINARY_PATHS = listOf(
        "/system/app/Superuser.apk",
        "/sbin/su",
        "/system/bin/su",
        "/system/xbin/su",
        "/data/local/xbin/su",
        "/data/local/bin/su",
        "/system/sd/xbin/su",
        "/system/bin/failsafe/su",
        "/data/local/su",
        "/su/bin/su",
    )

    fun hasRootIndicators(context: Context): Boolean {
        return hasKnownRootApps(context) ||
            hasRootBinaries() ||
            hasTestKeysBuildTag() ||
            hasWritableSystemPartition()
    }

    fun hasEmulatorIndicators(): Boolean {
        val fingerprint = Build.FINGERPRINT
        val model = Build.MODEL
        val manufacturer = Build.MANUFACTURER
        val brand = Build.BRAND
        val device = Build.DEVICE
        val product = Build.PRODUCT
        val hardware = Build.HARDWARE

        return fingerprint.startsWith("generic") ||
            fingerprint.startsWith("unknown") ||
            fingerprint.contains("emulator", ignoreCase = true) ||
            model.contains("google_sdk", ignoreCase = true) ||
            model.contains("Emulator", ignoreCase = true) ||
            model.contains("Android SDK built for", ignoreCase = true) ||
            manufacturer.contains("Genymotion", ignoreCase = true) ||
            (brand.startsWith("generic") && device.startsWith("generic")) ||
            product == "google_sdk" ||
            hardware.contains("goldfish", ignoreCase = true) ||
            hardware.contains("ranchu", ignoreCase = true) ||
            hardware.contains("vbox86", ignoreCase = true)
    }

    private fun hasKnownRootApps(context: Context): Boolean {
        val packageManager = context.packageManager
        return KNOWN_ROOT_APPS.any { packageName ->
            try {
                packageManager.getPackageInfo(packageName, PackageManager.GET_ACTIVITIES)
                true
            } catch (e: PackageManager.NameNotFoundException) {
                false
            }
        }
    }

    private fun hasRootBinaries(): Boolean {
        return KNOWN_ROOT_BINARY_PATHS.any { path -> File(path).exists() }
    }

    private fun hasTestKeysBuildTag(): Boolean {
        val tags = Build.TAGS
        return tags != null && tags.contains("test-keys")
    }

    private fun hasWritableSystemPartition(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("/system/xbin/which", "su"))
            val result = process.inputStream.bufferedReader().readLine()
            !result.isNullOrEmpty()
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Geliştirici seçenekleri açık mı — tek başına root/emülatör kanıtı
     * değil ama şüphe skoru için ek bir sinyal olarak kullanılabilir.
     */
    fun isDeveloperModeEnabled(context: Context): Boolean {
        return Settings.Global.getInt(
            context.contentResolver,
            Settings.Global.DEVELOPMENT_SETTINGS_ENABLED,
            0,
        ) != 0
    }
}
