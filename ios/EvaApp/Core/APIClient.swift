// ios/EvaApp/Core/APIClient.swift
import Foundation
import os.log

enum APIClientError: LocalizedError {
    case httpStatus(Int, Data?)
    case decodingFailed(Error)
    case network(Error)
    case invalidURL
    case missingAttestation

    var errorDescription: String? {
        switch self {
        case .httpStatus(let code, _):
            return "Sunucu hata döndürdü (HTTP \(code))."
        case .decodingFailed:
            return "Sunucu yanıtı çözümlenemedi."
        case .network(let underlying):
            return "Ağ hatası: \(underlying.localizedDescription)"
        case .invalidURL:
            return "Geçersiz istek adresi."
        case .missingAttestation:
            return "Cihaz doğrulama bilgisi eksik, istek gönderilemedi."
        }
    }
}

/// Eva mobil uygulamasının tüm Gateway iletişimini yöneten merkezi HTTP
/// istemcisi. Her istek, DeviceAttestationGuard'ın (backend) beklediği
/// x-eva-platform / x-eva-attestation header'larını otomatik ekler.
final class APIClient {
    private let logger = Logger(subsystem: "com.eva.app", category: "APIClient")
    private let baseURL: URL
    private let session: URLSession
    private let appAttestManager: AppAttestManager
    private let secureTokenStore: SecureTokenStoreProtocol

    init(
        baseURL: URL,
        appAttestManager: AppAttestManager,
        secureTokenStore: SecureTokenStoreProtocol,
        session: URLSession = .shared
    ) {
        self.baseURL = baseURL
        self.appAttestManager = appAttestManager
        self.secureTokenStore = secureTokenStore
        self.session = session
    }

    func post<Response: Decodable, Body: Encodable>(
        path: String,
        body: Body,
        requiresAuth: Bool
    ) async throws -> Response {
        guard let url = URL(string: path, relativeTo: baseURL) else {
            throw APIClientError.invalidURL
        }

        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        let bodyData: Data
        do {
            bodyData = try encoder.encode(body)
        } catch {
            throw APIClientError.decodingFailed(error)
        }

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.httpBody = bodyData
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue(Locale.current.language.languageCode?.identifier ?? "en", forHTTPHeaderField: "x-eva-locale")

        if requiresAuth {
            try await attachAttestationHeaders(to: &request, body: bodyData)
        }

        return try await execute(request)
    }

    func get<Response: Decodable>(
        path: String,
        queryItems: [URLQueryItem] = [],
        requiresAuth: Bool
    ) async throws -> Response {
        guard var components = URLComponents(url: baseURL.appendingPathComponent(path), resolvingAgainstBaseURL: true) else {
            throw APIClientError.invalidURL
        }
        components.queryItems = queryItems.isEmpty ? nil : queryItems

        guard let url = components.url else {
            throw APIClientError.invalidURL
        }

        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        request.setValue(Locale.current.language.languageCode?.identifier ?? "en", forHTTPHeaderField: "x-eva-locale")

        if requiresAuth {
            try await attachAttestationHeaders(to: &request, body: Data())
        }

        return try await execute(request)
    }

    private func attachAttestationHeaders(to request: inout URLRequest, body: Data) async throws {
        request.setValue("ios", forHTTPHeaderField: "x-eva-platform")

        guard appAttestManager.hasExistingAttestationKey else {
            logger.warning("Attestation anahtarı yok — istek doğrulama olmadan gönderiliyor (yalnızca DEV ortamında kabul edilir).")
            return
        }

        do {
            let assertion = try await appAttestManager.generateAssertion(forRequestBody: body)
            request.setValue(assertion.base64EncodedString(), forHTTPHeaderField: "x-eva-attestation")
        } catch {
            logger.error("Assertion üretilemedi, istek attestation olmadan devam ediyor: \(error.localizedDescription, privacy: .public)")
            throw APIClientError.missingAttestation
        }

        if let accessToken = secureTokenStore.readIfExists(forKey: SecureTokenKey.sessionAccessToken) {
            request.setValue("Bearer \(accessToken)", forHTTPHeaderField: "Authorization")
        }
    }

    private func execute<Response: Decodable>(_ request: URLRequest) async throws -> Response {
        let data: Data
        let response: URLResponse

        do {
            (data, response) = try await session.data(for: request)
        } catch {
            logger.error("Ağ isteği başarısız: \(error.localizedDescription, privacy: .public)")
            throw APIClientError.network(error)
        }

        guard let httpResponse = response as? HTTPURLResponse else {
            throw APIClientError.network(URLError(.badServerResponse))
        }

        guard (200...299).contains(httpResponse.statusCode) else {
            logger.warning("Sunucu hata döndürdü: status=\(httpResponse.statusCode, privacy: .public)")
            throw APIClientError.httpStatus(httpResponse.statusCode, data)
        }

        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601

        do {
            return try decoder.decode(Response.self, from: data)
        } catch {
            logger.error("Yanıt çözümlenemedi: \(error.localizedDescription, privacy: .public)")
            throw APIClientError.decodingFailed(error)
        }
    }
}
