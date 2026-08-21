// ios/EvaApp/EdgeAI/Routing/RouteSegment.swift
import Foundation

struct RouteSegment: Codable, Equatable {
    let segmentId: String
    let distanceKm: Double
    let averageSpeedKmh: Double
    let elevationGainM: Double
    let elevationLossM: Double
    let hvacActive: Bool
    let startGeohash7: String
    let endGeohash7: String
}
