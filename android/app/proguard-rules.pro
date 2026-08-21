# android/app/proguard-rules.pro
#
# HAD SAFHA (Hardened) R8/ProGuard yapılandırması. Amaç: dekompile edilen
# APK'da sınıf/metot isimlerinin ve iş mantığının okunamaz hale gelmesi.
#
# DÜRÜSTLÜK NOTU: ProGuard/R8 kod OBFUSCATION (isim karartma) yapar, kod
# ŞİFRELEME değil — kararlı bir saldırgan dekompile edilmiş, karartılmış
# Kotlin/Java bytecode'unu yine de okuyabilir (değişken isimleri anlamsız
# olur ama mantık akışı görünür kalır). Gerçek "tersine mühendisliğe karşı
# tam bağışıklık" hiçbir mobil platformda YOKTUR — Android'de native (NDK/
# C++) kritik mantığı taşımak veya ticari bir DexGuard/tersine-mühendislik
# önleme paketi kullanmak dışında bir üst seviye yoktur. Bu dosya,
# "makul, endüstri standardı sertleştirme" sağlar; "kırılamaz" değil.

# ------------------------------------------------------------
# Genel R8 davranışı
# ------------------------------------------------------------
-optimizationpasses 5
-allowaccessmodification
-repackageclasses ''
-flattenpackagehierarchy ''
-overloadaggressively

# Satır numaralarını sakla (crash raporlarını okuyabilmek için) ama
# gerçek dosya adını gizle.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ------------------------------------------------------------
# Eva'nın kendi kodu: domain/iş mantığı sınıflarının İSİMLERİ karartılsın,
# ama serileştirme/reflection'a ihtiyaç duyan tipler korunsun.
# ------------------------------------------------------------

# kotlinx.serialization — @Serializable sınıfların serializer() metodu
# reflection ile bulunuyor, bu yüzden bu sınıfların yapısı (alan adları
# JSON key'i olarak kullanıldığından) korunmalı. Sınıf adının kendisi
# yine de karartılabilir; yalnızca serileştirme meta verisi korunuyor.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
# TUM @Serializable siniflari kapsa.
#
# Onceki hali yalnizca *Dto / *Request / *Response desenlerini tutuyordu;
# VehicleProfile ve NearbyStationsEmptyBody bu desenlerin HICBIRINE uymuyor.
# VehicleProfile diske yazilan arac profilidir (SecureTokenStore +
# json.encodeToString) -- serializer'i R8 tarafindan atilirsa profil
# kaydedilemez ve kullanici RELEASE build'de onboarding ekranindan hic
# cikamaz. Debug'da minify kapali oldugu icin bu hata yalnizca yayinlanan
# surumde ortaya cikardi.
-keepclasseswithmembers class com.eva.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.eva.app.**$$serializer { *; }
-keepclassmembers class com.eva.app.** {
    ** Companion;
}

# ------------------------------------------------------------
# RevenueCat SDK
# ------------------------------------------------------------
-keep class com.revenuecat.purchases.** { *; }
-dontwarn com.revenuecat.purchases.**

# ------------------------------------------------------------
# Play Integrity / Play Core
# ------------------------------------------------------------
-keep class com.google.android.play.core.integrity.** { *; }

# ------------------------------------------------------------
# Hilt / Dagger — DI graph'ının çalışması için jenerik yapı korunmalı
# ------------------------------------------------------------
-keep class dagger.hilt.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager$FragmentContextWrapper
-keepclasseswithmembers class * {
    @dagger.hilt.android.lifecycle.HiltViewModel <init>(...);
}

# ------------------------------------------------------------
# Android Keystore ile imzalama (RequestSigner) — kritik güvenlik kodu,
# isim karartma buradaki mantığı GİZLEMEZ (bytecode akışı görünür kalır)
# ama en azından sınıf/metot isimlerini okunaksız hale getirir.
# Fonksiyonel olarak dokunulmaması gereken JCA (Java Cryptography
# Architecture) sınıfları hariç tutuluyor.
# ------------------------------------------------------------
-dontwarn java.security.**
-dontwarn javax.crypto.**

# ------------------------------------------------------------
# Genel Kotlin coroutine / reflection uyumluluğu
# ------------------------------------------------------------
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}
-dontwarn kotlinx.coroutines.**

# ------------------------------------------------------------
# API endpoint string'lerinin GİZLENMESİ hakkında dürüst not:
# ProGuard/R8, String literal'leri OKUNAKLI bırakır (obfuscation string
# içeriğini şifrelemez, yalnızca sembol isimlerini değiştirir). Yani
# "https://api.evaapp.com/v1/stations/nearby" gibi bir string, APK'ı
# `strings` veya `jadx` ile incelendiğinde HALA görünür.
#
# Bu string'leri gerçekten gizlemek için (a) base URL'i BuildConfig'te
# tutup çalışma zamanında basit bir XOR/AES ile çözmek, ya da (b) NDK
# (native C++) tarafında saklamak gerekir. Bu, mevcut kapsamın (ProGuard
# kuralları) ötesinde ayrı bir iş — istersen RuntimeStringObfuscator.kt
# adında ayrı bir yardımcı sınıf olarak ekleyebilirim; burada bilinçli
# olarak dışarıda bırakıldı çünkü yanlış uygulanan "string şifreleme"
# genelde AES anahtarını APK içine gömüp sorunu çözmüş gibi görünüp
# aslında çözmemiş oluyor (anahtar da APK'da, sadece bir adım geriye
# itildi). Gerçek koruma DeviceAttestationGuard + RequestSignatureGuard
# (backend tarafı doğrulama) ile sağlanıyor — istemcinin URL'i bilmesi
# önemli değil, sunucunun isteği doğrulaması önemli.
# ------------------------------------------------------------
