// android/app/src/main/java/com/eva/app/network/APIClient.kt
package com.eva.app.network

import android.util.Log
import com.eva.app.BuildConfig
import com.eva.app.security.PlayIntegrityManager
import com.eva.app.security.RequestSigner
import com.eva.app.security.DeviceRegistrationGate
import com.eva.app.security.SecureTokenStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.CertificatePinner
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit

// inline fonksiyonlardan (executeRequest vb.) Log cagrilarinda
// kullanildigi icin private OLAMAZ; bkz. asagidaki @PublishedApi notu.
@PublishedApi
internal const val TAG = "APIClient"

sealed class APIClientException(message: String) : Exception(message) {
    data class HttpStatus(val code: Int, val bodySnippet: String?) :
        APIClientException("Sunucu hata döndürdü (HTTP $code)")

    data class DecodingFailed(val reason: String) :
        APIClientException("Sunucu yanıtı çözümlenemedi: $reason")

    data class Network(val reason: String) :
        APIClientException("Ağ hatası: $reason")
}

/**
 * Cihaz kayıt akışı (bkz. DeviceRegistrationRepository) henüz tamamlanmamış
 * olabileceği için bu path'ler imza header'ı OLMADAN, yalnızca
 * DeviceAttestationGuard (Play Integrity) korumasıyla gönderilir.
 */
private val PATHS_EXEMPT_FROM_SIGNATURE = setOf("/v1/devices/register")

/**
 * Eva Android uygulamasının tüm Gateway iletişimini yöneten merkezi HTTP
 * istemcisi. Her istek üç bağımsız güvenlik katmanı taşır:
 *  1. Certificate Pinning (OkHttp) — yalnızca kendi sunucu sertifikamıza
 *     güvenir, araya giren proxy'lerin (Charles/Burp) MITM yapmasını
 *     engeller.
 *  2. x-eva-platform / x-eva-attestation — Play Integrity token'ı
 *     (DeviceAttestationGuard tarafından Google'a karşı doğrulanır).
 *  3. X-Eva-Signature / X-Eva-Signature-Timestamp / X-Eva-Device-Id —
 *     Android Keystore ile üretilmiş imza (RequestSignatureGuard
 *     tarafından doğrulanır, replay koruması dahil).
 */
class APIClient(
    @PublishedApi internal val baseUrl: String,
    private val playIntegrityManager: PlayIntegrityManager,
    private val secureTokenStore: SecureTokenStore,
    private val requestSigner: RequestSigner,
    /**
     * Imzali isteklerin cihaz kaydini beklemesini saglar. Kayittan
     * ONCE giden bir istek imzasiz cikar ve 401 alir; bkz.
     * DeviceRegistrationGate dosya basi yorumu.
     */
    @PublishedApi internal val registrationGate: DeviceRegistrationGate,
    /**
     * Gateway sunucu sertifikanızın SHA-256 SPKI pin değeri.
     * Üretimi: `openssl s_client -connect api.armanalabs.com:443 | openssl x509
     * -pubkey -noout | openssl pkey -pubin -outform der | openssl dgst
     * -sha256 -binary | openssl enc -base64`
     * Sertifika yenilendiğinde bu değer DEĞİŞİR — bu yüzden ikinci bir
     * "backup pin" (yedek sertifikanız/gelecek sertifikanız için)
     * eklemeniz ZORUNLU, aksi halde sertifika yenileme günü uygulamanız
     * TÜM kullanıcılar için çalışmaz hale gelir (bkz. aşağıdaki backup
     * pin parametresi).
     */
    private val certificatePins: List<String> = emptyList(),
) {
    @PublishedApi internal val httpClient: OkHttpClient = run {
        val builder = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)

        if (certificatePins.isNotEmpty()) {
            val host = baseUrl.substringAfter("://").substringBefore("/").substringBefore(":")
            val pinnerBuilder = CertificatePinner.Builder()
            certificatePins.forEach { pin -> pinnerBuilder.add(host, pin) }
            builder.certificatePinner(pinnerBuilder.build())
        } else {
            Log.w(
                TAG,
                "Certificate pinning YAPILANDIRILMAMIŞ (pin listesi boş) — " +
                    "yalnızca yerel geliştirme ortamında (localhost/10.0.2.2) kabul edilebilir. " +
                    "Production build'de certificatePins boş olarak Gateway'e bağlanmaya ÇALIŞMAYIN.",
            )
        }

        builder.build()
    }

    // @PublishedApi internal: asagidaki post/get/executeRequest fonksiyonlari
    // `inline` oldugu icin govdeleri cagiran modulun icine kopyalanir; bu
    // yuzden Kotlin, public bir inline fonksiyonun private uyelere
    // erismesine izin vermez. @PublishedApi internal, uyeyi ikili duzeyde
    // erisilebilir kilar ama kaynak duzeyinde modul disina KAPALI tutar.
    @PublishedApi internal val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @PublishedApi internal val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    suspend inline fun <reified Req, reified Res> post(
        path: String,
        body: Req,
        requiresAuth: Boolean,
    ): Res = withContext(Dispatchers.IO) {
        val bodyJson = json.encodeToString(body)

        val requestBuilder = Request.Builder()
            .url("$baseUrl$path")
            .post(bodyJson.toRequestBody(jsonMediaType))
            .addHeader("Content-Type", "application/json")
            .addHeader("x-eva-locale", Locale.getDefault().language)

        if (requiresAuth) {
            awaitRegistrationIfSigned(path)
            attachAttestationHeaders(requestBuilder)
            attachAttestationTokenAsync(requestBuilder, sha256Hex(bodyJson))
            attachSignatureHeadersIfApplicable(requestBuilder, path, "POST", bodyJson)
        }

        executeRequest(requestBuilder.build())
    }

    suspend inline fun <reified Res> get(
        path: String,
        queryParams: Map<String, String> = emptyMap(),
        requiresAuth: Boolean,
    ): Res = withContext(Dispatchers.IO) {
        val urlBuilder = StringBuilder("$baseUrl$path")
        if (queryParams.isNotEmpty()) {
            urlBuilder.append("?")
            urlBuilder.append(
                queryParams.entries.joinToString("&") { (k, v) -> "$k=$v" }
            )
        }

        val requestBuilder = Request.Builder()
            .url(urlBuilder.toString())
            .get()
            .addHeader("x-eva-locale", Locale.getDefault().language)

        if (requiresAuth) {
            awaitRegistrationIfSigned(path)
            attachAttestationHeaders(requestBuilder)
            attachAttestationTokenAsync(requestBuilder, sha256Hex(urlBuilder.toString()))
            attachSignatureHeadersIfApplicable(requestBuilder, path, "GET", "")
        }

        executeRequest(requestBuilder.build())
    }

    /**
     * Imza gerektiren bir yol icin cihaz kaydinin bitmesini bekler.
     *
     * Kayit isteginin KENDISI beklemez: `/v1/devices/register` imzadan
     * muaf ve kaydi tamamlayacak olan da odur -- beklerse kendi kendini
     * kilitler.
     */
    @PublishedApi internal suspend fun awaitRegistrationIfSigned(path: String) {
        if (path in PATHS_EXEMPT_FROM_SIGNATURE) return
        registrationGate.awaitAttempted()
    }

    fun attachAttestationHeaders(requestBuilder: Request.Builder) {
        requestBuilder.addHeader("x-eva-platform", "android")

        secureTokenStore.read(SecureTokenStore.KEY_SESSION_ACCESS_TOKEN)?.let { accessToken ->
            requestBuilder.addHeader("Authorization", "Bearer $accessToken")
        }
    }

    /**
     * x-eva-attestation header'ı Play Integrity token'ını taşır — bu,
     * istek gövdesinin hash'ine bağlı olduğu için attachSignatureHeaders'tan
     * ÖNCE, ayrı bir suspend fonksiyon olarak çağrılmalı. İki header'ı
     * (attestation + signature) birbirinden bağımsız tutmak, birinin
     * geçici olarak devre dışı bırakılması gerektiğinde (örn. Play
     * Integrity API kotasını aştıysanız) diğerinin çalışmaya devam
     * etmesini sağlar.
     */
    suspend fun attachAttestationTokenAsync(requestBuilder: Request.Builder, requestHash: String) {
        try {
            val integrityToken = playIntegrityManager.requestIntegrityToken(requestHash)
            requestBuilder.addHeader("x-eva-attestation", integrityToken)
        } catch (e: Exception) {
            // Fail-closed: RELEASE build'de attestation olmadan istek
            // gonderilmez -- davranis degismedi.
            if (!BuildConfig.DEBUG) {
                Log.e(TAG, "Integrity token eklenemedi.", e)
                throw APIClientException.Network(
                    "Cihaz dogrulama bilgisi eklenemedi: ${e.message}"
                )
            }

            // DEBUG build: yerel gelistirmede gecerli bir Play Integrity
            // kurulumu (GOOGLE_CLOUD_PROJECT_NUMBER + Play Console'da
            // etkinlestirilmis API) cogu zaman YOKTUR. Header'i HIC
            // EKLEMEDEN devam ediyoruz; Gateway tarafindaki
            // DeviceAttestationGuard, header yokken ve
            // DEVICE_ATTESTATION_ENFORCED=false iken gelistirme bypass'ini
            // uygular. Header'i bos/gecersiz gondermek YANLIS olurdu --
            // guard o zaman gercek dogrulamayi dener ve reddeder.
            Log.w(
                TAG,
                "Integrity token alinamadi; DEBUG build oldugu icin istek " +
                    "attestation header'i OLMADAN gonderiliyor. Bu yapilandirma " +
                    "yalnizca yerel gelistirme icindir.",
                e,
            )
        }
    }

    fun attachSignatureHeadersIfApplicable(
        requestBuilder: Request.Builder,
        path: String,
        method: String,
        body: String,
    ) {
        if (path in PATHS_EXEMPT_FROM_SIGNATURE) return
        if (!requestSigner.hasSigningKey) {
            Log.w(TAG, "İmzalama anahtarı henüz yok — cihaz kaydı tamamlanmamış olabilir.")
            return
        }

        val timestamp = System.currentTimeMillis().toString()
        val bodyHash = sha256Hex(body)
        val payload = "$method|$path|$timestamp|$bodyHash"

        val signature = requestSigner.sign(payload.toByteArray(Charsets.UTF_8))
        val deviceId = secureTokenStore.read(SecureTokenStore.KEY_DEVICE_SIGNING_ID)

        if (deviceId == null) {
            Log.w(TAG, "Cihaz ID'si bulunamadı — imza header'ı eklenemiyor.")
            return
        }

        requestBuilder.addHeader("X-Eva-Signature", signature)
        requestBuilder.addHeader("X-Eva-Signature-Timestamp", timestamp)
        requestBuilder.addHeader("X-Eva-Device-Id", deviceId)
    }

    suspend inline fun <reified Res> executeRequest(request: Request): Res {
        val responseBody: String
        val statusCode: Int

        try {
            httpClient.newCall(request).execute().use { response ->
                statusCode = response.code
                responseBody = response.body?.string().orEmpty()
            }
        } catch (e: javax.net.ssl.SSLPeerUnverifiedException) {
            // Certificate pinning uyuşmazlığı TAM OLARAK BURADA yakalanır —
            // araya giren bir proxy/sahte sertifika tespit edildi.
            Log.e(TAG, "SSL pinning doğrulaması başarısız — olası MITM girişimi.", e)
            throw APIClientException.Network("Güvenli bağlantı doğrulanamadı.")
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Iptal bir HATA DEGILDIR; coroutine'e geri firlatilmali. Aksi
            // halde iptal edilen istek "ag hatasi" olarak raporlanir ve
            // kullaniciya teknik bir mesaj gosterilir.
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Ağ isteği başarısız: ${request.url}", e)
            throw APIClientException.Network(e.message ?: "Bilinmeyen ağ hatası")
        }

        if (statusCode !in 200..299) {
            Log.w(TAG, "Sunucu hata döndürdü: status=$statusCode")
            throw APIClientException.HttpStatus(statusCode, responseBody.take(200))
        }

        return try {
            json.decodeFromString(responseBody)
        } catch (e: Exception) {
            Log.e(TAG, "Yanıt çözümlenemedi.", e)
            throw APIClientException.DecodingFailed(e.message ?: "Bilinmeyen çözümleme hatası")
        }
    }

    fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
