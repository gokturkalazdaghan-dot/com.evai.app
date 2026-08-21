// ios/EvaApp/EdgeAI/Battery/BatteryModel.swift
import Foundation

/// Tamamen cihaz üstünde çalışan batarya tüketim/rejenerasyon simülatörü.
/// İnternet bağlantısı gerektirmez — tünel/dağ yolu senaryolarında dahi
/// kesintisiz çalışır.
final class BatteryModel {
    let vehicleProfile: VehicleProfile

    init(vehicleProfile: VehicleProfile) {
        self.vehicleProfile = vehicleProfile
    }

    /// Bir rota segmentini kat etmek için gereken net enerjiyi (kWh) hesaplar.
    /// Pozitif değer tüketim, negatif değer net rejenerasyon anlamına gelir.
    func estimateSegmentEnergyKwh(_ segment: RouteSegment) throws -> Double {
        guard segment.distanceKm > 0 else {
            throw InvalidRouteSegmentException(reason: "distanceKm sıfır veya negatif olamaz")
        }

        let baseKwh = (segment.distanceKm / 100.0) * vehicleProfile.baseConsumptionKwhPer100Km
        let elevationFactorKwh = elevationEnergyDeltaKwh(segment)
        let speedFactor = speedEfficiencyMultiplier(segment.averageSpeedKmh)
        let hvacFactor = hvacLoadKwh(segment)

        let rawTotal = (baseKwh * speedFactor) + elevationFactorKwh + hvacFactor

        if rawTotal < 0 {
            return rawTotal * vehicleProfile.regenEfficiencyRatio
        }
        return rawTotal
    }

    private func elevationEnergyDeltaKwh(_ segment: RouteSegment) -> Double {
        let assumedVehicleMassKg = 1900.0
        let gravity = 9.81
        let deltaElevationM = segment.elevationGainM - segment.elevationLossM
        let rawJoules = assumedVehicleMassKg * gravity * deltaElevationM
        return rawJoules / 3.6e6
    }

    private func speedEfficiencyMultiplier(_ averageSpeedKmh: Double) -> Double {
        guard averageSpeedKmh > 90 else { return 1.0 }
        let excessSpeed = averageSpeedKmh - 90
        return 1.0 + (excessSpeed * 0.008)
    }

    private func hvacLoadKwh(_ segment: RouteSegment) -> Double {
        guard segment.hvacActive else { return 0.0 }
        let hours = segment.distanceKm / max(segment.averageSpeedKmh, 1.0)
        let hvacDrawKw = 1.8
        return hours * hvacDrawKw
    }

    /// Verilen batarya durumundan, bir dizi segment kat edildiğinde varış
    /// noktasındaki tahmini SoC yüzdesini hesaplar. Yetersiz menzil
    /// durumunda InsufficientRangeException fırlatır.
    func projectStateOfCharge(
        currentState: BatteryState,
        segments: [RouteSegment],
        minimumArrivalSocPercent: Double = 10.0
    ) throws -> Double {
        let thermalPenalty = currentState.thermalEfficiencyPenaltyRatio()
        var remainingKwh = currentState.socRatio() * vehicleProfile.usableCapacityKwh
        remainingKwh -= vehicleProfile.usableCapacityKwh * thermalPenalty

        var totalConsumedKwh = 0.0

        for segment in segments {
            let segmentKwh = try estimateSegmentEnergyKwh(segment)
            totalConsumedKwh += segmentKwh
            remainingKwh -= segmentKwh
        }

        let projectedSocPercent = (remainingKwh / vehicleProfile.usableCapacityKwh) * 100.0

        if projectedSocPercent < minimumArrivalSocPercent {
            throw InsufficientRangeException(
                requiredKwh: totalConsumedKwh,
                availableKwh: currentState.socRatio() * vehicleProfile.usableCapacityKwh
            )
        }

        return min(max(projectedSocPercent, 0.0), 100.0)
    }

    /// Bir sonraki şarj istasyonuna ulaşmak için gereken minimum SoC'yi,
    /// güvenlik payı (%buffer) ile birlikte hesaplar.
    func requiredDepartureSocPercent(
        forSegments segments: [RouteSegment],
        safetyBufferPercent: Double = 8.0
    ) throws -> Double {
        var totalKwh = 0.0
        for segment in segments {
            totalKwh += try estimateSegmentEnergyKwh(segment)
        }
        let requiredRatio = totalKwh / vehicleProfile.usableCapacityKwh
        return min(max((requiredRatio * 100.0) + safetyBufferPercent, 0.0), 100.0)
    }
}
