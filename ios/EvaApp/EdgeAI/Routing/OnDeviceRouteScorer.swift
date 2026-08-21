// ios/EvaApp/EdgeAI/Routing/OnDeviceRouteScorer.swift
import Foundation

/// Tamamen cihaz üstünde çalışan rota puanlama motoru. İnternet bağlantısı
/// kesildiğinde (tünel, dağ yolu vb.) dahi önceden senkronize edilmiş
/// manifest ile kesintisiz çalışmaya devam eder.
final class OnDeviceRouteScorer {
    private let batteryModel: BatteryModel
    private let manifestStore: GeohashManifestStore

    init(batteryModel: BatteryModel, manifestStore: GeohashManifestStore) {
        self.batteryModel = batteryModel
        self.manifestStore = manifestStore
    }

    func scoreRoute(
        segments: [RouteSegment],
        startingBatteryState: BatteryState,
        minimumArrivalSocPercent: Double = 10.0,
        chargeStopTargetSocPercent: Double = 80.0
    ) throws -> ScoredRouteCandidate {
        guard !segments.isEmpty else {
            throw InvalidRouteSegmentException(reason: "Segment listesi boş olamaz")
        }

        var plannedStops: [ChargeStopManifestEntry] = []
        var currentSoc = startingBatteryState.stateOfChargePercent
        var totalEnergyKwh = 0.0
        var totalDistanceKm = 0.0

        for segment in segments {
            totalDistanceKm += segment.distanceKm
            let segmentEnergyKwh = try batteryModel.estimateSegmentEnergyKwh(segment)
            totalEnergyKwh += segmentEnergyKwh

            do {
                currentSoc = try batteryModel.projectStateOfCharge(
                    currentState: BatteryState(
                        stateOfChargePercent: currentSoc,
                        batteryTemperatureCelsius: startingBatteryState.batteryTemperatureCelsius,
                        measuredAt: Date()
                    ),
                    segments: [segment],
                    minimumArrivalSocPercent: minimumArrivalSocPercent
                )
            } catch is InsufficientRangeException {
                guard let stop = try findBestChargeStop(
                    nearGeohash7: segment.startGeohash7,
                    vehicleConnectorTypes: batteryModel.vehicleProfile.supportedConnectorTypes
                ) else {
                    throw InsufficientRangeException(
                        requiredKwh: segmentEnergyKwh,
                        availableKwh: (currentSoc / 100.0) * batteryModel.vehicleProfile.usableCapacityKwh
                    )
                }

                plannedStops.append(stop)
                currentSoc = chargeStopTargetSocPercent
            }
        }

        let score = computeRouteScore(
            totalDistanceKm: totalDistanceKm,
            chargeStopCount: plannedStops.count,
            finalSocPercent: currentSoc
        )

        return ScoredRouteCandidate(
            segments: segments,
            plannedChargeStops: plannedStops,
            totalDistanceKm: totalDistanceKm,
            totalEnergyKwh: totalEnergyKwh,
            finalProjectedSocPercent: currentSoc,
            score: score
        )
    }

    private func findBestChargeStop(
        nearGeohash7: String,
        vehicleConnectorTypes: [String]
    ) throws -> ChargeStopManifestEntry? {
        let geohash5 = String(nearGeohash7.prefix(min(5, nearGeohash7.count)))

        let candidates: [ChargeStopManifestEntry]
        do {
            candidates = try manifestStore.getStations(forRegion: geohash5)
        } catch is ManifestUnavailableException {
            // Bu bölge için offline manifest henüz senkronize edilmemiş.
            // nil dönüyoruz — çağıran taraf bunu kullanıcıya "bu bölgede
            // offline veri yok" olarak iletmeli.
            return nil
        }

        let compatible = candidates.filter { candidate in
            let hasConnector = candidate.connectorTypes.contains { vehicleConnectorTypes.contains($0) }
            let isUsable = candidate.status == "OPERATIONAL" || candidate.status == "DEGRADED"
            return hasConnector && isUsable
        }

        return compatible.max { $0.maxPowerKw < $1.maxPowerKw }
    }

    /// Düşük skor = daha iyi rota. Süre yaklaşık şarj molası sayısıyla,
    /// risk ise varış SoC'sinin ne kadar dar olduğuyla temsil edilir.
    private func computeRouteScore(
        totalDistanceKm: Double,
        chargeStopCount: Int,
        finalSocPercent: Double
    ) -> Double {
        let avgChargeStopPenaltyMinutes = 25.0
        let baseDriveTimeMinutes = (totalDistanceKm / 90.0) * 60.0
        let chargeTimePenalty = Double(chargeStopCount) * avgChargeStopPenaltyMinutes
        let riskPenalty = finalSocPercent < 20 ? (20 - finalSocPercent) * 3.0 : 0.0

        return baseDriveTimeMinutes + chargeTimePenalty + riskPenalty
    }
}
