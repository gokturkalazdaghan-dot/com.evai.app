// ios/EvaApp/Commerce/SubscriptionState.swift
import Foundation

struct SubscriptionState: Codable, Equatable {
    let tier: SubscriptionTier
    let productId: String?
    let expirationDate: Date?
    let willAutoRenew: Bool
    let lastVerifiedAt: Date

    static let unverified = SubscriptionState(
        tier: .free,
        productId: nil,
        expirationDate: nil,
        willAutoRenew: false,
        lastVerifiedAt: .distantPast
    )

    var isPremiumActive: Bool {
        switch tier {
        case .active, .trialing, .gracePeriod:
            guard let expirationDate else { return tier == .trialing }
            return expirationDate > Date()
        case .free, .expired, .revoked:
            return false
        }
    }
}
