// ios/EvaApp/EdgeAI/Routing/RouteCandidate.swift
import Foundation

struct ChargeStopManifestEntry: Codable, Equatable {
    let stationId: String
    let geohash7: String
    let lat: Double
    let lon: Double
    let maxPowerKw: Double
    let connectorTypes: [String]
    let status: String
}

struct ScoredRouteCandidate: Equatable {
    let segments: [RouteSegment]
    let plannedChargeStops: [ChargeStopManifestEntry]
    let totalDistanceKm: Double
    let totalEnergyKwh: Double
    let finalProjectedSocPercent: Double
    let score: Double
}
