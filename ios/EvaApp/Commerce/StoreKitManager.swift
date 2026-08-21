// ios/EvaApp/Commerce/StoreKitManager.swift
import StoreKit
import os.log

enum StoreKitError: LocalizedError {
    case productNotFound(String)
    case purchaseFailed(String)
    case verificationFailed
    case userCancelled
    case pending

    var errorDescription: String? {
        switch self {
        case .productNotFound(let id):
            return "Ürün bulunamadı: \(id)"
        case .purchaseFailed(let reason):
            return "Satın alma başarısız: \(reason)"
        case .verificationFailed:
            return "İşlem doğrulanamadı. Lütfen tekrar deneyin."
        case .userCancelled:
            return "Satın alma iptal edildi."
        case .pending:
            return "Satın alma onay bekliyor (örn. Ebeveyn İzni)."
        }
    }
}

@MainActor
final class StoreKitManager: ObservableObject {
    private let logger = Logger(subsystem: "com.eva.app", category: "StoreKitManager")

    @Published private(set) var availableProducts: [Product] = []
    @Published private(set) var subscriptionState: SubscriptionState = .unverified
    @Published private(set) var isLoadingProducts = false

    private var transactionListenerTask: Task<Void, Never>?
    private let receiptSyncService: ReceiptSyncService

    init(receiptSyncService: ReceiptSyncService) {
        self.receiptSyncService = receiptSyncService
        transactionListenerTask = listenForTransactionUpdates()
    }

    deinit {
        transactionListenerTask?.cancel()
    }

    // MARK: - Ürün Yükleme

    func loadProducts() async {
        isLoadingProducts = true
        defer { isLoadingProducts = false }

        let productIds = SubscriptionProduct.allCases.map(\.rawValue)

        do {
            let products = try await Product.products(for: productIds)
            self.availableProducts = products.sorted { $0.price < $1.price }
            logger.info("StoreKit ürünleri yüklendi: \(products.count) adet")
        } catch {
            logger.error("StoreKit ürün yükleme hatası: \(error.localizedDescription, privacy: .public)")
            self.availableProducts = []
        }
    }

    // MARK: - Satın Alma

    func purchase(_ product: Product) async throws {
        let result: Product.PurchaseResult

        do {
            result = try await product.purchase()
        } catch {
            logger.error("Satın alma isteği başarısız: \(error.localizedDescription, privacy: .public)")
            throw StoreKitError.purchaseFailed(error.localizedDescription)
        }

        switch result {
        case .success(let verification):
            let (transaction, jws) = try checkVerifiedWithJWS(verification)
            await handleVerifiedTransaction(transaction, jwsRepresentation: jws)
            await transaction.finish()

        case .userCancelled:
            throw StoreKitError.userCancelled

        case .pending:
            throw StoreKitError.pending

        @unknown default:
            logger.error("Bilinmeyen StoreKit satın alma sonucu")
            throw StoreKitError.purchaseFailed("Bilinmeyen sonuç türü")
        }
    }

    // MARK: - Mevcut Aboneliği Geri Yükleme

    func restorePurchases() async throws {
        do {
            try await AppStore.sync()
        } catch {
            logger.error("AppStore.sync() başarısız: \(error.localizedDescription, privacy: .public)")
            throw StoreKitError.purchaseFailed("Geri yükleme başarısız: \(error.localizedDescription)")
        }
        await refreshSubscriptionStatus()
    }

    // MARK: - Durum Yenileme

    func refreshSubscriptionStatus() async {
        for await result in Transaction.currentEntitlements {
            guard let (transaction, jws) = try? checkVerifiedWithJWS(result) else {
                logger.warning("Geçerli yetkilendirme doğrulanamadı, atlanıyor.")
                continue
            }

            if transaction.productType == .autoRenewable {
                await handleVerifiedTransaction(transaction, jwsRepresentation: jws)
                return
            }
        }

        self.subscriptionState = .unverified
    }

    // MARK: - Arka Plan Dinleyici

    private func listenForTransactionUpdates() -> Task<Void, Never> {
        Task.detached(priority: .background) { [weak self] in
            for await result in Transaction.updates {
                guard let self else { return }
                guard let (transaction, jws) = try? self.checkVerifiedWithJWS(result) else {
                    self.logger.warning("Güncelleme akışında geçersiz işlem, atlanıyor.")
                    continue
                }
                await self.handleVerifiedTransaction(transaction, jwsRepresentation: jws)
                await transaction.finish()
            }
        }
    }

    // MARK: - Doğrulama & JWS Çıkarımı

    /// VerificationResult'tan hem doğrulanmış Transaction'ı hem de Apple'ın
    /// imzaladığı ham JWS string'ini birlikte döndürür. Sunucuya YALNIZCA
    /// bu ham JWS gönderilir — Apple sunucu tarafında kendi public key'iyle
    /// bağımsız olarak yeniden doğrular.
    private func checkVerifiedWithJWS(
        _ result: VerificationResult<Transaction>
    ) throws -> (transaction: Transaction, jwsRepresentation: String) {
        switch result {
        case .unverified(_, let verificationError):
            logger.error("StoreKit yerel doğrulama başarısız: \(verificationError.localizedDescription, privacy: .public)")
            throw StoreKitError.verificationFailed
        case .verified(let safe):
            return (safe, result.jwsRepresentation)
        }
    }

    private func handleVerifiedTransaction(
        _ transaction: Transaction,
        jwsRepresentation: String
    ) async {
        do {
            let serverState = try await receiptSyncService.syncTransaction(
                jwsRepresentation: jwsRepresentation
            )
            self.subscriptionState = serverState
            logger.info("Abonelik durumu sunucuyla senkronize edildi: tier=\(serverState.tier.rawValue, privacy: .public)")
        } catch {
            logger.error("Sunucu senkronizasyonu başarısız: \(error.localizedDescription, privacy: .public)")
            self.subscriptionState = self.deriveLocalFallbackState(from: transaction)
        }
    }

    private func deriveLocalFallbackState(from transaction: Transaction) -> SubscriptionState {
        let tier: SubscriptionTier = transaction.revocationDate == nil ? .active : .revoked
        return SubscriptionState(
            tier: tier,
            productId: transaction.productID,
            expirationDate: transaction.expirationDate,
            willAutoRenew: transaction.expirationDate != nil,
            lastVerifiedAt: Date()
        )
    }
}
