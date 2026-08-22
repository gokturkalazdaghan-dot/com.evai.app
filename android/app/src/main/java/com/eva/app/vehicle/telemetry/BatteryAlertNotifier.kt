// android/app/src/main/java/com/eva/app/vehicle/telemetry/BatteryAlertNotifier.kt
package com.eva.app.vehicle.telemetry

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.eva.app.MainActivity
import com.eva.app.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

private const val CHANNEL_ID = "battery_alerts"

/**
 * Bildirim kimligi SABIT: yeni uyari eskisinin YERINE gecer.
 *
 * Ayri kimlikler kullanilsaydi bildirim panelinde "%50'ye dustu",
 * "%30'a dustu" yan yana birikirdi -- ikincisi zaten birincisini
 * gecersiz kilarken.
 */
private const val NOTIFICATION_ID = 4201

/**
 * Batarya uyarilarini telefonun kendi bildirim sistemiyle verir.
 *
 * NEDEN SESLI ASISTAN DEGIL
 * -------------------------
 * Uyarilar once Eva'nin sesiyle veriliyordu. Bu, uyarinin calismasini
 * sesli asistanin calismasina bagliyordu: uygulama on planda degilse,
 * ses kapaliysa ya da asistan devre disiysa surucu hicbir sey duymuyordu.
 * Bildirim ise sistemin isi -- uygulama kapaliyken bile gorunur, sessiz
 * moda saygi duyar, kullanici kanali kendi ayarlarindan yonetebilir.
 */
@Singleton
class BatteryAlertNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /**
     * Kanal, ilk bildirimden ONCE olusturulmali; yoksa bildirim sessizce
     * dusurulur (Android 8+).
     */
    fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.battery_alert_channel_name),
            // HIGH degil DEFAULT: surus sirasinda tam ekran kesen bir
            // uyari tehlikeli olur. Sarj seviyesi acil bir durum degil,
            // zamaninda haber verilmesi gereken bir bilgi.
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.battery_alert_channel_description)
        }

        context.getSystemService(NotificationManager::class.java)
            ?.createNotificationChannel(channel)
    }

    /** Android 13+ bildirim izni verilmis mi? */
    fun hasPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Uyariyi gosterir.
     *
     * @param level esik; kritik seviye farkli renk/oncelik alir.
     * @param message kullaniciya gosterilecek metin.
     */
    @SuppressLint("MissingPermission")
    fun notify(level: BatteryAlertLevel, message: String) {
        if (!hasPermission()) return

        ensureChannel()

        val openApp = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val title = context.getString(
            when (level) {
                BatteryAlertLevel.LOW -> R.string.battery_alert_low_title
                BatteryAlertLevel.CRITICAL -> R.string.battery_alert_critical_title
            },
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_charging)
            .setContentTitle(title)
            .setContentText(message)
            // Uzun metin kesilmesin: istasyon adi ve fiyat tek satira sigmaz.
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(
                if (level == BatteryAlertLevel.CRITICAL) {
                    NotificationCompat.PRIORITY_HIGH
                } else {
                    NotificationCompat.PRIORITY_DEFAULT
                },
            )
            .build()

        // İzin bu metodun ilk satırında kontrol edildi (hasPermission());
        // lint yardımcı metodu takip edemediği için uyarıyor. Çağrı
        // ayrıca runCatching içinde: sistem yine de reddederse
        // (SecurityException) bildirim düşer ama uygulama çökmez.
        runCatching {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        }
    }
}
