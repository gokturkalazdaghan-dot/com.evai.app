# deploy/imza-parmakizi.ps1
#
# Aday anahtar depolarinin SHA-256 sertifika parmak izini yazdirir.
#
# NEDEN GEREKLI
# -------------
# Play Console, EVA AI icin BELIRLI bir yukleme sertifikasi bekliyor.
# Yanlis anahtarla imzalanan AAB "Yuklediginiz APK yanlis sertifikayla
# imzalanmis" hatasiyla reddedilir. Elimizde bes aday var; hangisinin
# dogru oldugunu ancak parmak izini Play Console'daki degerle
# karsilastirarak bilebiliriz.
#
# PAROLA GUVENLIGI
# ----------------
# Parola bu betikte SAKLANMAZ, ekrana YAZILMAZ ve komut gecmisine
# girmez -- Read-Host -AsSecureString ile alinir.
#
# KULLANIM
#   powershell -ExecutionPolicy Bypass -File deploy\imza-parmakizi.ps1
#
# Play Console'da bakilacak yer:
#   Uygulamaniz > Test ve yayinlama > Uygulama butunlugu >
#   Uygulama imzalama > Yukleme anahtari sertifikasi > SHA-256

$ErrorActionPreference = 'Stop'

function Find-Keytool {
    $candidates = @(
        "$env:JAVA_HOME\bin\keytool.exe",
        "C:\Program Files\Java\jdk-17\bin\keytool.exe",
        "C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe"
    )
    foreach ($c in $candidates) { if (Test-Path $c) { return $c } }
    $cmd = Get-Command keytool -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    throw "keytool bulunamadi. Java JDK ya da Android Studio kurulu olmali."
}

$keytool = Find-Keytool
Write-Host "keytool: $keytool" -ForegroundColor DarkGray
Write-Host ""

$keystores = @(
    "C:\dev\ai-guard-rn\ai-guard-rn\android\app\ai-guard-release.keystore",
    "C:\dev\ai-guard-rn\ai-guard-rn\android\app\ai-guard-upload.keystore",
    "$env:USERPROFILE\AndroidKeystores\eva-upload-app.jks",
    "$env:USERPROFILE\AndroidKeystores\release-key-app.jks",
    "$env:USERPROFILE\AndroidKeystores\release-key-android.jks"
) | Where-Object { Test-Path $_ }

foreach ($ks in $keystores) {
    $name = Split-Path $ks -Leaf
    Write-Host "===== $name =====" -ForegroundColor Cyan
    Write-Host "  $ks" -ForegroundColor DarkGray

    $secure = Read-Host "  Parola (bilmiyorsan bos birakip Enter'a bas)" -AsSecureString
    $plain = [Runtime.InteropServices.Marshal]::PtrToStringAuto(
        [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
    )

    if ([string]::IsNullOrWhiteSpace($plain)) {
        Write-Host "  atlandi" -ForegroundColor DarkYellow
        Write-Host ""
        continue
    }

    $out = & $keytool -list -v -keystore $ks -storepass $plain 2>&1
    # Parolayi hemen bellekten dusur.
    $plain = $null

    if ($LASTEXITCODE -ne 0) {
        Write-Host "  PAROLA YANLIS ya da depo okunamadi" -ForegroundColor Red
        Write-Host ""
        continue
    }

    $out | Select-String -Pattern "Alias name|Diger ad|SHA256:|SHA-256:" |
        ForEach-Object { Write-Host ("  " + $_.Line.Trim()) -ForegroundColor Green }
    Write-Host ""
}

Write-Host "Bu SHA-256 degerlerinden HANGISI Play Console'daki" -ForegroundColor Yellow
Write-Host "'Yukleme anahtari sertifikasi' ile ayni? Onu kullanacagiz." -ForegroundColor Yellow
