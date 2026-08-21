// ios/EvaApp/EdgeAI/Battery/BatteryState.swift
import Foundation

struct BatteryState: Codable, Equatable {
    let stateOfChargePercent: Double
    let batteryTemperatureCelsius: Double
    let measuredAt: Date

    init(stateOfChargePercent: Double, batteryTemperatureCelsius: Double, measuredAt: Date) {
        precondition(stateOfChargePercent >= 0 && stateOfChargePercent <= 100, "SoC 0-100 aralığında olmalı")
        self.stateOfChargePercent = stateOfChargePercent
        self.batteryTemperatureCelsius = batteryTemperatureCelsius
        self.measuredAt = measuredAt
    }

    func socRatio() -> Double {
        stateOfChargePercent / 100.0
    }

    /// Soğuk hava (< 5°C) veya aşırı sıcak (> 40°C) durumunda verim düşüşü.
    /// Basit ampirik model — gerçek BMS verisiyle kalibre edilecek.
    func thermalEfficiencyPenaltyRatio() -> Double {
        if batteryTemperatureCelsius < 5 {
            let deficit = 5 - batteryTemperatureCelsius
            return min(max(deficit * 0.015, 0.0), 0.35)
        }
        if batteryTemperatureCelsius > 40 {
            let excess = batteryTemperatureCelsius - 40
            return min(max(excess * 0.01, 0.0), 0.20)
        }
        return 0.0
    }
}
