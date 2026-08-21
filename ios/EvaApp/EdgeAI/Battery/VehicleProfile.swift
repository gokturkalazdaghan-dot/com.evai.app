// ios/EvaApp/EdgeAI/Battery/VehicleProfile.swift
import Foundation

/// Araca özgü, tamamen cihaz üstünde saklanan profil.
/// Sıfır-PII: bu veri hiçbir zaman sunucuya ham olarak gönderilmez.
struct VehicleProfile: Codable, Equatable {
    let modelIdentifier: String
    let batteryCapacityKwh: Double
    let usableBatteryRatio: Double
    let baseConsumptionKwhPer100Km: Double
    let regenEfficiencyRatio: Double
    let maxDcChargeRateKw: Double
    let supportedConnectorTypes: [String]
    let preconditioningPenaltyKwh: Double

    init(
        modelIdentifier: String,
        batteryCapacityKwh: Double,
        usableBatteryRatio: Double,
        baseConsumptionKwhPer100Km: Double,
        regenEfficiencyRatio: Double,
        maxDcChargeRateKw: Double,
        supportedConnectorTypes: [String],
        preconditioningPenaltyKwh: Double = 0.8
    ) {
        precondition(batteryCapacityKwh > 0, "Batarya kapasitesi pozitif olmalı")
        precondition(usableBatteryRatio > 0 && usableBatteryRatio <= 1, "usableBatteryRatio (0,1] aralığında olmalı")

        self.modelIdentifier = modelIdentifier
        self.batteryCapacityKwh = batteryCapacityKwh
        self.usableBatteryRatio = usableBatteryRatio
        self.baseConsumptionKwhPer100Km = baseConsumptionKwhPer100Km
        self.regenEfficiencyRatio = regenEfficiencyRatio
        self.maxDcChargeRateKw = maxDcChargeRateKw
        self.supportedConnectorTypes = supportedConnectorTypes
        self.preconditioningPenaltyKwh = preconditioningPenaltyKwh
    }

    var usableCapacityKwh: Double {
        batteryCapacityKwh * usableBatteryRatio
    }
}
