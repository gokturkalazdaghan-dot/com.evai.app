// android/app/src/main/java/com/eva/app/security/RequestSigner.kt
package com.eva.app.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature

private const val TAG = "RequestSigner"
private const val ANDROID_KEYSTORE = "AndroidKeyStore"
private const val SIGNING_KEY_ALIAS = "eva_request_signing_key_v1"

sealed class RequestSignerError(message: String) : Exception(message) {
    data class KeyGenerationFailed(val reason: String) :
        RequestSignerError("İmzalama anahtarı üretilemedi: $reason")

    data class SigningFailed(val reason: String) :
        RequestSignerError("İstek imzalanamadı: $reason")
}

/**
 * Her Gateway isteğini, Android Keystore'un GÜVENLİ DONANIM alanında
 * (mümkünse StrongBox, değilse TEE) tutulan bir EC anahtarıyla imzalar.
 *
 * Bu, Play Integrity'den (Bölüm/IntegrityGate) FARKLI ve TAMAMLAYICI bir
 * mekanizmadır:
 *  - Play Integrity: "Bu APK gerçek, imzası Play Store'dan, cihaz
 *    kurcalanmamış" (uygulama+cihaz bütünlüğü, her istekte pahalı).
 *  - RequestSigner: "Bu spesifik HTTP isteği, daha önce kayıt olmuş
 *    BELLİ BİR cihazın özel anahtarıyla imzalandı, içeriği yolda
 *    değiştirilmedi" (istek bütünlüğü + replay koruması, her istekte
 *    ucuz — ağ çağrısı gerektirmez).
 *
 * Özel anahtar (private key) KESİNLİKLE dışa aktarılamaz
 * (setUserAuthenticationRequired kullanılmıyor çünkü arka planda,
 * kullanıcı etkileşimi olmadan her istekte imzalama gerekiyor — ama
 * anahtarın kendisi yine de Keystore'dan asla çıkmaz, yalnızca imzalama
 * İŞLEMİ donanımda gerçekleşir).
 */
class RequestSigner {

    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply {
        load(null)
    }

    /** İlk çalıştırmada anahtar yoksa üretir; sonraki çağrılarda mevcut anahtarı kullanır. */
    fun ensureKeyExists(): Boolean {
        if (keyStore.containsAlias(SIGNING_KEY_ALIAS)) {
            return true
        }

        return try {
            val keyPairGenerator = KeyPairGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_EC,
                ANDROID_KEYSTORE,
            )

            val specBuilder = KeyGenParameterSpec.Builder(
                SIGNING_KEY_ALIAS,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
            )
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setAlgorithmParameterSpec(java.security.spec.ECGenParameterSpec("secp256r1"))

            // StrongBox (ayrı bir güvenlik çipi) varsa kullan — daha güçlü
            // izolasyon sağlar. Desteklenmeyen cihazlarda normal TEE'ye
            // (Trusted Execution Environment) sessizce düşer.
            try {
                specBuilder.setIsStrongBoxBacked(true)
                keyPairGenerator.initialize(specBuilder.build())
                keyPairGenerator.generateKeyPair()
            } catch (e: Exception) {
                Log.w(TAG, "StrongBox kullanılamıyor, standart TEE ile devam ediliyor.")
                val fallbackSpec = KeyGenParameterSpec.Builder(
                    SIGNING_KEY_ALIAS,
                    KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
                )
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .setAlgorithmParameterSpec(java.security.spec.ECGenParameterSpec("secp256r1"))
                    .build()
                keyPairGenerator.initialize(fallbackSpec)
                keyPairGenerator.generateKeyPair()
            }

            true
        } catch (e: Exception) {
            Log.e(TAG, "Anahtar üretimi başarısız.", e)
            false
        }
    }

    /**
     * Base64 encode edilmiş genel anahtar (public key) — cihaz kayıt
     * akışında (DeviceRegistrationRepository) Gateway'e bir kez gönderilir.
     * Bu değer PII DEĞİLDİR — kimseyi tanımlamaz, yalnızca imza
     * doğrulaması için kullanılan matematiksel bir değerdir.
     */
    fun getPublicKeyBase64(): String {
        val publicKey: PublicKey = keyStore.getCertificate(SIGNING_KEY_ALIAS).publicKey
        return Base64.encodeToString(publicKey.encoded, Base64.NO_WRAP)
    }

    /**
     * payload: genellikle "METHOD|PATH|TIMESTAMP|BODY_SHA256_HEX" formatında
     * birleştirilmiş bir string'in UTF-8 byte'ları (bkz. APIClient).
     * Dönen imza Base64 encode edilmiş olarak header'a konur.
     */
    fun sign(payload: ByteArray): String {
        val privateKey = keyStore.getKey(SIGNING_KEY_ALIAS, null) as? PrivateKey
            ?: throw RequestSignerError.SigningFailed("Özel anahtar bulunamadı.")

        return try {
            val signature = Signature.getInstance("SHA256withECDSA").apply {
                initSign(privateKey)
                update(payload)
            }
            Base64.encodeToString(signature.sign(), Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e(TAG, "İmzalama işlemi başarısız.", e)
            throw RequestSignerError.SigningFailed(e.message ?: "Bilinmeyen hata")
        }
    }

    val hasSigningKey: Boolean
        get() = keyStore.containsAlias(SIGNING_KEY_ALIAS)
}
