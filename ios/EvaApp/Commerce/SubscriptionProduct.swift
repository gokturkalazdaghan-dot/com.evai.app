// ios/EvaApp/Commerce/SubscriptionProduct.swift
import Foundation

enum SubscriptionProduct: String, CaseIterable, Identifiable {
    case monthly = "com.eva.app.subscription.monthly"
    case yearly = "com.eva.app.subscription.yearly"

    var id: String { rawValue }

    var displayFallbackPriceUSD: Decimal {
        switch self {
        case .monthly: return 4.99
        case .yearly: return 49.99
        }
    }

    var trialDurationDays: Int { 3 }
}

enum SubscriptionTier: String, Codable {
    case free
    case trialing
    case active
    case expired
    case gracePeriod
    case revoked
}
