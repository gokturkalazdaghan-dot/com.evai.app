// ios/EvaApp/Core/AppAttestManager.swift
import DeviceCheck
import CryptoKit
import Foundation
import os.log

/// Apple App Attest tabanlı cihaz doğrulama yöneticisi.
/// Sıfır-PII: bu sınıf hiçbir zaman kullanıcı kimliği, e-posta veya ham
/// cihaz tanımlayıcısı üretmez/saklamaz — yalnızca Apple'ın kriptografik
/// olarak imzaladığı, sunucu tarafında doğrulanabilen bir "attestation"
/// (cihaz bütünlük kanıtı) üretir.
enum AppAttestError: LocalizedError {
    case attestationNotSupported
    case keyGenerationFailed(String)
    case attestationFailed(String)
    case assertionFailed(String)
    case noKeyIdAvailable
    case challengeFetchFailed(String)

    var errorDescription: String? {
        switch self {
        case .attestationNotSupported:
            return "Bu cihaz App Attest'i desteklemiyor."
        case .keyGenerationFailed(let reason):
            return "Attestation anahtarı üretilemedi: \(reason)"
        case .attestationFailed(let reason):
            return "Cihaz doğrulaması (attestation) başarısız: \(reason)"
        case .assertionFailed(let reason):
            return "İstek imzalama (assertion) başarısız: \(reason)"
        case .noKeyIdAvailable:
            return "Kayıtlı bir attestation anahtarı bulunamadı, önce attestKey() çağrılmalı."
        case .challengeFetchFailed(let reason):
            return "Sunucudan challenge alınamadı: \(reason)"
        }
    }
}

/// Sunucudan tek kullanımlık (nonce) bir challenge çekmek için minimal protokol.
/// Gerçek implementasyon APIClient üzerinden /v1/attestation/challenge uç
/// noktasına gider — burada dependency injection ile test edilebilirlik
/// sağlanıyor.
protocol AttestationChallengeProviding {
    func fetchChallenge() async throws -> Data
}

final class AppAttestManager {
    private let logger = Logger(subsystem: "com.eva.app", category: "AppAttestManager")
    private let service = DCAppAttestService.shared
    private let secureTokenStore: SecureTokenStoreProtocol
    private let challengeProvider: AttestationChallengeProviding

    init(
        secureTokenStore: SecureTokenStoreProtocol,
        challengeProvider: AttestationChallengeProviding
    ) {
        self.secureTokenStore = secureTokenStore
        self.challengeProvider = challengeProvider
    }

    var isSupported: Bool {
        service.isSupported
    }

    /// Cihaz için bir kez çalıştırılması gereken anahtar üretimi ve ilk
    /// attestation akışı. Üretilen keyId, sonraki tüm assertion'lar için
    /// yeniden kullanılır — her istekte yeni anahtar üretilmez.
    ///
    /// Dönen Data, sunucuya `x-eva-attestation` header'ı olarak base64
    /// encode edilerek iletilir (DeviceAttestationGuard bunu doğrular).
    func performInitialAttestation() async throws -> Data {
        guard service.isSupported else {
            throw AppAttestError.attestationNotSupported
        }

        let keyId: String
        do {
            keyId = try await service.generateKey()
        } catch {
            logger.error("Attestation anahtarı üretilemedi: \(error.localizedDescription, privacy: .public)")
            throw AppAttestError.keyGenerationFailed(error.localizedDescription)
        }

        let challenge: Data
        do {
            challenge = try await challengeProvider.fetchChallenge()
        } catch {
            logger.error("Challenge alınamadı: \(error.localizedDescription, privacy: .public)")
            throw AppAttestError.challengeFetchFailed(error.localizedDescription)
        }

        // Apple'ın önerdiği gibi, challenge'ın SHA-256 hash'i clientDataHash
        // olarak kullanılıyor — ham challenge'ın kendisi değil.
        let clientDataHash = Data(SHA256.hash(data: challenge))

        let attestation: Data
        do {
            attestation = try await service.attestKey(keyId, clientDataHash: clientDataHash)
        } catch {
            logger.error("attestKey başarısız: \(error.localizedDescription, privacy: .public)")
            throw AppAttestError.attestationFailed(error.localizedDescription)
        }

        // keyId, gelecekteki assertion'lar için Keychain'de saklanıyor.
        // Bu, PII DEĞİLDİR — Apple tarafından üretilen, cihaza özel ama
        // kimliksiz bir kriptografik anahtar referansıdır.
        do {
            try secureTokenStore.save(keyId, forKey: SecureTokenKey.lastAttestationNonce)
        } catch {
            logger.warning("keyId Keychain'e kaydedilemedi, bu oturumda yeniden üretilecek: \(error.localizedDescription, privacy: .public)")
        }

        return attestation
    }

    /// İlk attestation sonrası her API isteği için hafif bir "assertion"
    /// (imza) üretir. Bu, tam attestation'dan çok daha ucuzdur ve her
    /// istekte tekrar tekrar çağrılabilir.
    func generateAssertion(forRequestBody body: Data) async throws -> Data {
        guard let keyId = secureTokenStore.readIfExists(forKey: SecureTokenKey.lastAttestationNonce) else {
            throw AppAttestError.noKeyIdAvailable
        }

        let clientDataHash = Data(SHA256.hash(data: body))

        do {
            return try await service.generateAssertion(keyId, clientDataHash: clientDataHash)
        } catch {
            logger.error("generateAssertion başarısız: \(error.localizedDescription, privacy: .public)")
            throw AppAttestError.assertionFailed(error.localizedDescription)
        }
    }

    /// Kayıtlı bir attestation anahtarı var mı — uygulama açılışında
    /// performInitialAttestation'ın tekrar çağrılıp çağrılmayacağına
    /// karar vermek için kullanılır.
    var hasExistingAttestationKey: Bool {
        secureTokenStore.readIfExists(forKey: SecureTokenKey.lastAttestationNonce) != nil
    }
}
