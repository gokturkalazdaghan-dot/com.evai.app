// ios/EvaApp/Core/SecureTokenStore.swift
import Foundation
import Security

/// Keychain tabanlı, şifreli yerel token/kimlik bilgisi deposu.
/// Sıfır-PII kuralına uygun olarak, burada YALNIZCA oturum token'ları ve
/// kısa ömürlü attestation artefaktları saklanır — asla e-posta, ham konum
/// veya cihaz kimliği gibi kişisel veri tutulmaz.
enum SecureTokenStoreError: LocalizedError {
    case itemNotFound
    case duplicateItem
    case unexpectedStatus(OSStatus)
    case encodingFailed

    var errorDescription: String? {
        switch self {
        case .itemNotFound:
            return "İstenen anahtar Keychain'de bulunamadı."
        case .duplicateItem:
            return "Bu anahtar için zaten bir kayıt mevcut."
        case .unexpectedStatus(let status):
            return "Keychain işlemi beklenmeyen durumla sonuçlandı: \(status)"
        case .encodingFailed:
            return "Veri Keychain'e yazılmak üzere kodlanamadı."
        }
    }
}

protocol SecureTokenStoreProtocol {
    func save(_ value: String, forKey key: String) throws
    func read(forKey key: String) throws -> String
    func readIfExists(forKey key: String) -> String?
    func delete(forKey key: String) throws
    func deleteAll() throws
}

final class SecureTokenStore: SecureTokenStoreProtocol {
    private let service: String

    /// service: Keychain kayıtlarını uygulamaya özgü bir ad alanında izole eder.
    /// Birden fazla SecureTokenStore instance'ı (örn. test vs production)
    /// farklı service adlarıyla birbirinden tamamen izole çalışabilir.
    init(service: String = "com.eva.app.securetokenstore") {
        self.service = service
    }

    func save(_ value: String, forKey key: String) throws {
        guard let data = value.data(using: .utf8) else {
            throw SecureTokenStoreError.encodingFailed
        }

        // Önce var olan kaydı sil — SecItemAdd zaten var olan bir anahtar
        // için errSecDuplicateItem döndürür; upsert semantiği istiyoruz.
        let existingQuery: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: key,
        ]
        SecItemDelete(existingQuery as CFDictionary)

        let addQuery: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: key,
            kSecValueData as String: data,
            // afterFirstUnlockThisDeviceOnly: cihaz kilitliyken arka planda
            // erişim gerekmiyor (token yenileme kullanıcı uygulamayı açtığında
            // olur), ve veri yalnızca bu cihazda kalmalı — iCloud Keychain
            // senkronizasyonu ile başka bir cihaza taşınmamalı.
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
        ]

        let status = SecItemAdd(addQuery as CFDictionary, nil)
        guard status == errSecSuccess else {
            throw SecureTokenStoreError.unexpectedStatus(status)
        }
    }

    func read(forKey key: String) throws -> String {
        guard let value = readIfExists(forKey: key) else {
            throw SecureTokenStoreError.itemNotFound
        }
        return value
    }

    func readIfExists(forKey key: String) -> String? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: key,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]

        var result: AnyObject?
        let status = SecItemCopyMatching(query as CFDictionary, &result)

        guard status == errSecSuccess,
              let data = result as? Data,
              let value = String(data: data, encoding: .utf8) else {
            return nil
        }

        return value
    }

    func delete(forKey key: String) throws {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: key,
        ]

        let status = SecItemDelete(query as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else {
            throw SecureTokenStoreError.unexpectedStatus(status)
        }
    }

    /// Yalnızca oturum kapatma / hesap sıfırlama senaryolarında kullanılır.
    func deleteAll() throws {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
        ]

        let status = SecItemDelete(query as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else {
            throw SecureTokenStoreError.unexpectedStatus(status)
        }
    }
}

/// SecureTokenStore'un kullandığı standart anahtar adları — string literal
/// tekrarını ve yazım hatası riskini önlemek için tek yerde toplanıyor.
enum SecureTokenKey {
    static let sessionAccessToken = "eva.session.accessToken"
    static let sessionRefreshToken = "eva.session.refreshToken"
    static let lastAttestationNonce = "eva.attestation.lastNonce"
}
