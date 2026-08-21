param(
    [Parameter(Position=0)]
    [ValidateSet("up", "down", "logs", "reset", "help")]
    [string]$Action = "help"
)

function Show-Help {
    Write-Host "==========================================" -ForegroundColor Cyan
    Write-Host "         EVA AI - CLI Yardimcisi          " -ForegroundColor Yellow
    Write-Host "==========================================" -ForegroundColor Cyan
    Write-Host "Kullanim: .\dev.ps1 [komut]" -ForegroundColor White
    Write-Host ""
    Write-Host "Komutlar:" -ForegroundColor Green
    Write-Host "  up     - Tum Docker servislerini (Backend, DB, Redis) baslatir"
    Write-Host "  down   - Calisan tum servisleri durdurur"
    Write-Host "  logs   - Tum servislerin loglarini canli takip eder"
    Write-Host "  reset  - Veritabanini ve container'lari sifirdan baslatir"
    Write-Host "  help   - Bu yardim menusunu gosterir"
    Write-Host "==========================================" -ForegroundColor Cyan
}

switch ($Action) {
    "up" {
        Write-Host "EVA AI servisleri baslatiliyor..." -ForegroundColor Green
        docker compose up -d
        Write-Host "Servisler ayakta! Gateway ve Agent'a erisebilirsiniz." -ForegroundColor Cyan
    }
    "down" {
        Write-Host "EVA AI servisleri durduruluyor..." -ForegroundColor Yellow
        docker compose down
        Write-Host "Tum servisler durduruldu." -ForegroundColor Cyan
    }
    "logs" {
        Write-Host "Canli loglar yukleniyor (Cikmak icin CTRL+C)..." -ForegroundColor Yellow
        docker compose logs -f
    }
    "reset" {
        Write-Host "Sistem sifirlaniyor (Tum veriler temizlenecek)..." -ForegroundColor Red
        docker compose down -v
        docker compose up -d --build
        Write-Host "Sistem sifirdan baslatildi." -ForegroundColor Cyan
    }
    Default {
        Show-Help
    }
}
