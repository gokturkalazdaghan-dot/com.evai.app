param(
    [Parameter(Position=0)]
    [ValidateSet("install", "devices", "logs", "build", "help")]
    [string]$Action = "help",
    [string]$ApkPath = "android/app/build/outputs/apk/debug/app-debug.apk"
)

function Show-Help {
    Write-Host "==========================================" -ForegroundColor Cyan
    Write-Host "         EVA AI - ADB Yardimcisi          " -ForegroundColor Yellow
    Write-Host "==========================================" -ForegroundColor Cyan
    Write-Host "Kullanim: .\adb.ps1 [komut]" -ForegroundColor White
    Write-Host ""
    Write-Host "Komutlar:" -ForegroundColor Green
    Write-Host "  devices - Bagli olan Android cihazlari listeler"
    Write-Host "  build   - Android APK (Debug) derlemesini baslatir"
    Write-Host "  install - Derlenen APK'yi bagli cihaza kurar"
    Write-Host "  logs    - Canli Android loglarini (Logcat) filtreler"
    Write-Host "  help    - Bu yardim menusunu gosterir"
    Write-Host "==========================================" -ForegroundColor Cyan
}

switch ($Action) {
    "devices" {
        Write-Host "Bagli Android cihazlar taraniyor..." -ForegroundColor Green
        adb devices
    }
    "build" {
        Write-Host "Android APK derleniyor (Gradle)..." -ForegroundColor Yellow
        Set-Location android
        .\gradlew assembleDebug
        Set-Location ..
        Write-Host "APK derleme tamamlandi!" -ForegroundColor Cyan
    }
    "install" {
        if (Test-Path $ApkPath) {
            Write-Host "APK cihaza kuruluyor: $ApkPath" -ForegroundColor Green
            adb install -r $ApkPath
            Write-Host "Kurulum basariyla tamamlandi!" -ForegroundColor Cyan
        } else {
            Write-Host "HATA: APK dosyasi bulunamadi! Once '.\adb.ps1 build' calistirin." -ForegroundColor Red
        }
    }
    "logs" {
        Write-Host "EVA AI Logcat filtrelemesi baslatiliyor (Cikmak icin CTRL+C)..." -ForegroundColor Yellow
        adb logcat -s "EvaApp", "ObdBleClient", "Telemetry"
    }
    Default {
        Show-Help
    }
}
