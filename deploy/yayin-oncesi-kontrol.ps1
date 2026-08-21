# deploy/yayin-oncesi-kontrol.ps1
#
# URETIME cikmadan once calistirilir. Ic testte gecmesi gerekmez.
#
# NEDEN VAR
# ---------
# Uygulamanin sunucu adresi APK'nin ICINE derlenir. Yanlis ya da bize
# ait olmayan bir adresle uretime cikarsak:
#   - Kullanicilar hicbir zaman fiyat goremez, VE
#   - O alan adini baska biri kaydederse, uygulamanin imzali
#     isteklerini (cihaz kimligi + konum) o kisi almaya baslar ve
#     istedigi fiyati donebilir.
#
# Bu kontrol, o hatayi magazada degil burada yakalar.
#
# KULLANIM
#   powershell -ExecutionPolicy Bypass -File deploy\yayin-oncesi-kontrol.ps1

$ErrorActionPreference = 'Stop'
$hata = 0

function Sonuc($ok, $baslik, $detay) {
    if ($ok) {
        Write-Host ("  [GECTI] " + $baslik) -ForegroundColor Green
    } else {
        Write-Host ("  [KALDI] " + $baslik) -ForegroundColor Red
        $script:hata++
    }
    if ($detay) { Write-Host ("           " + $detay) -ForegroundColor DarkGray }
}

$lp = Join-Path $PSScriptRoot "..\android\local.properties"
if (-not (Test-Path $lp)) { throw "local.properties bulunamadi: $lp" }

$props = @{}
Get-Content $lp | Where-Object { $_ -match '^\s*[A-Za-z_]+\s*=' } | ForEach-Object {
    $kv = $_.Split('=', 2)
    $props[$kv[0].Trim()] = $kv[1].Trim()
}

Write-Host "`n=== EVA AI yayin oncesi kontrol ===`n" -ForegroundColor Cyan

# --- 1. Sunucu adresi ---
Write-Host "1. Sunucu adresi" -ForegroundColor White
$api = $props['EVA_GATEWAY_BASE_URL_RELEASE']
Sonuc ([bool]$api) "adres tanimli" $api

if ($api) {
    Sonuc ($api -like 'https://*') "HTTPS kullaniyor"

    $host_ = ([Uri]$api).Host
    $dns = $null
    try { $dns = [Net.Dns]::GetHostAddresses($host_) } catch {}
    Sonuc ([bool]$dns) "alan adi cozumleniyor (DNS)" $host_

    # Alan adi BIZE mi ait? Sertifika gecerliyse ve /health yanit
    # veriyorsa sunucu ayakta demektir.
    $saglik = $false
    try {
        $r = Invoke-WebRequest -Uri ($api.TrimEnd('/') + '/health') -TimeoutSec 12 -UseBasicParsing
        $saglik = ($r.StatusCode -eq 200)
    } catch {}
    Sonuc $saglik "/health yanit veriyor (sunucu ayakta + sertifika gecerli)"
}

# --- 2. Imza ---
Write-Host "`n2. Imzalama" -ForegroundColor White
$ks = $props['EVA_KEYSTORE_FILE']
Sonuc ([bool]$ks -and (Test-Path $ks)) "anahtar deposu bulundu" $ks
Sonuc ([bool]$props['EVA_KEY_ALIAS']) "alias tanimli" $props['EVA_KEY_ALIAS']
Sonuc ([bool]$props['EVA_KEYSTORE_PASSWORD']) "parola tanimli"

# --- 3. Abonelik ---
Write-Host "`n3. Abonelik" -ForegroundColor White
$rc = $props['REVENUECAT_PUBLIC_API_KEY']
Sonuc ($rc -and $rc.StartsWith('goog_')) "RevenueCat anahtari goog_ ile basliyor"
Sonuc ($rc -notlike '*PLACEHOLDER*') "yer tutucu degil"

# --- 4. Yasal ---
Write-Host "`n4. Yasal belgeler" -ForegroundColor White
foreach ($k in @('PRIVACY_POLICY_URL', 'TERMS_OF_SERVICE_URL')) {
    $u = $props[$k]
    if (-not $u) { Sonuc $false "$k tanimli"; continue }
    $acilir = $false
    try {
        $r = Invoke-WebRequest -Uri $u -TimeoutSec 12 -UseBasicParsing
        $acilir = ($r.StatusCode -eq 200)
    } catch {}
    Sonuc $acilir "$k acilir durumda" $u
}

# --- Ozet ---
Write-Host ""
if ($hata -eq 0) {
    Write-Host "TUM KONTROLLER GECTI - uretime cikilabilir" -ForegroundColor Green
} else {
    Write-Host "$hata KONTROL BASARISIZ - uretime CIKMAYIN" -ForegroundColor Red
    Write-Host "Ic test icin sorun degil; uretim yayini oncesi duzeltilmeli." -ForegroundColor Yellow
}
exit $hata
