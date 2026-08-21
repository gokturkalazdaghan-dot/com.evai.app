// ios/EvaApp/Commerce/ReceiptSyncService.swift
import StoreKit
import os.log

protocol ReceiptSyncServiceProtocol {
    func syncTransaction(jwsRepresentation: String) async throws -> SubscriptionState
}

enum ReceiptSyncError: LocalizedError {
    case networkFailure(String)
    case serverRejected(statusCode: Int)
    case malformedResponse

    var errorDescription: String? {
        switch self {
        case .networkFailure(let reason):
            return "Ağ hatası: \(reason)"
        case .serverRejected(let code):
            return "Sunucu makbuzu reddetti (HTTP \(code))"
        case .malformedResponse:
            return "Sunucu yanıtı çözümlenemedi"
        }
    }
}

final class ReceiptSyncService: ReceiptSyncServiceProtocol {
    private let logger = Logger(subsystem: "com.eva.app", category: "ReceiptSyncService")
    private let apiClient: APIClient

    init(apiClient: APIClient) {
        self.apiClient = apiClient
    }

    struct ReceiptSyncRequestBody: Encodable {
        let platform: String
        let signedTransactionInfo: String
    }

    /// jwsRepresentation: StoreKit 2'nin VerificationResult.jwsRepresentation
    /// alanından gelen, Apple tarafından imzalanmış ham JWS string'i. Bu
    /// string sunucuya olduğu gibi iletilir; sunucu App Store Server Library
    /// ile Apple'ın public key'ini kullanarak bağımsız doğrulama yapar.
    func syncTransaction(jwsRepresentation: String) async throws -> SubscriptionState {
        let body = ReceiptSyncRequestBody(platform: "ios", signedTransactionInfo: jwsRepresentation)

        do {
            let response: ReceiptSyncResponse = try await apiClient.post(
                path: "/v1/billing/receipts/sync",
                body: body,
                requiresAuth: true
            )
            return response.toSubscriptionState()
        } catch let apiError as APIClientError {
            logger.error("Makbuz senkronizasyonu API hatası: \(apiError.localizedDescription, privacy: .public)")
            throw mapApiError(apiError)
        } catch {
            logger.error("Beklenmeyen senkronizasyon hatası: \(error.localizedDescription, privacy: .public)")
            throw ReceiptSyncError.networkFailure(error.localizedDescription)
        }
    }

    private func mapApiError(_ error: APIClientError) -> ReceiptSyncError {
        switch error {
        case .httpStatus(let code, _):
            return .serverRejected(statusCode: code)
        case .decodingFailed:
            return .malformedResponse
        case .network(let underlying):
            return .networkFailure(underlying.localizedDescription)
        case .invalidURL, .missingAttestation:
            return .networkFailure(error.localizedDescription ?? "Bilinmeyen hata")
        }
    }
}

struct ReceiptSyncResponse: Codable {
    let tier: String
    let productId: String?
    let expirationDate: Date?
    let willAutoRenew: Bool

    func toSubscriptionState() -> SubscriptionState {
        SubscriptionState(
            tier: SubscriptionTier(rawValue: tier) ?? .free,
            productId: productId,
            expirationDate: expirationDate,
            willAutoRenew: willAutoRenew,
            lastVerifiedAt: Date()
        )
    }
}
