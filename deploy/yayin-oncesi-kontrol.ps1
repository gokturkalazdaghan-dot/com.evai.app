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

# TURK KLAVYESI TUZAGI -- BURAYA DIKKAT
# ------------------------------------
# Burada once `-match` kullaniliyordu. PowerShell'de `-match` BUYUK/KUCUK
# HARF DUYARSIZ ve KULTURE BAGLIDIR. Turkce kulturde (tr-TR) buyuk 'I'
# harfinin kucugu 'i' degil NOKTASIZ 'ı'dir ve bu harf [A-Za-z] araliginda
# yoktur. Sonuc: adinda 'I' gecen HER ayar sessizce atlaniyordu --
# MAPS_API_KEY, PRIVACY_POLICY_URL, EVA_KEYSTORE_FILE, EVA_KEY_ALIAS,
# REVENUECAT_PUBLIC_API_KEY...
#
# Yani bu kontrol, Turkce bir makinede yapilandirma DOGRU oldugu halde
# 7 hata bildiriyordu. Yanlis alarm veren bir uretim kapisi, bir sure
# sonra herkesin gormezden geldigi bir kapiya doner.
#
# `-cmatch` buyuk/kucuk harfe duyarlidir ve bu kulturel donusumu
# yapmaz. Karakter kumesine rakam ve nokta da eklendi: sdk.dir ve
# GATEWAY_CERT_PIN_1 gibi anahtarlar da onceden atlaniyordu.
$props = @{}
Get-Content $lp | Where-Object { $_ -cmatch '^\s*[A-Za-z_][A-Za-z0-9_.]*\s*=' } | ForEach-Object {
    $kv = $_.Split('=', 2)
    # BOM (dosyanin ilk baytlari) anahtar adina yapisir; temizlenmezse
    # ilk ayar hicbir zaman bulunamaz.
    $props[$kv[0].Trim().TrimStart([char]0xFEFF)] = $kv[1].Trim()
}

Write-Host "`n=== EVA AI yayin oncesi kontrol ===`n" -ForegroundColor Cyan

# --- 1. Sunucu adresi ---
Write-Host "1. Sunucu adresi" -ForegroundColor White
$api = $props['EVA_GATEWAY_BASE_URL_RELEASE']
Sonuc ([bool]$api) "adres tanimli" $api

if ($api) {
    # -clike: yukaridaki ayni kulturel tuzak. Sabit protokol adlari
    # her zaman ORDINAL karsilastirilmali.
    Sonuc ($api -clike 'https://*') "HTTPS kullaniyor"

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
# StartsWith varsayilan olarak KULTURE BAGLI karsilastirir. RevenueCat
# oneki sabit bir teknik dizedir; kullanicinin dilinden etkilenmemeli.
Sonuc ($rc -and $rc.StartsWith('goog_', [System.StringComparison]::Ordinal)) `
    "RevenueCat anahtari goog_ ile basliyor"
Sonuc ($rc -cnotlike '*PLACEHOLDER*') "yer tutucu degil"

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
