// ios/EvaApp/EdgeAI/Exceptions/EdgeAiExceptions.swift

sealed class EdgeAiExceptionMarker {}

protocol EdgeAiException: Error, CustomStringConvertible {
    var message: String { get }
}

extension EdgeAiException {
    var description: String { "EdgeAiException: \(message)" }
}

struct InsufficientRangeException: EdgeAiException {
    let requiredKwh: Double
    let availableKwh: Double
    var message: String {
        "Menzil yetersiz: gerekli \(requiredKwh)kWh, mevcut \(availableKwh)kWh"
    }
}

struct ManifestUnavailableException: EdgeAiException {
    let geohash5: String
    var message: String {
        "Çevrimdışı istasyon manifestosu bulunamadı: bölge=\(geohash5)"
    }
}

struct InvalidRouteSegmentException: EdgeAiException {
    let reason: String
    var message: String {
        "Geçersiz rota segmenti: \(reason)"
    }
}
