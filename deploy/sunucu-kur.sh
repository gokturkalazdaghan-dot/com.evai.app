#!/usr/bin/env bash
# deploy/sunucu-kur.sh
#
# Sifirdan bir Ubuntu sunucusunu EVA AI'yi calistirir hale getirir.
#
# KULLANIM (sunucuda, root olarak):
#   git clone <depo> /opt/eva && cd /opt/eva
#   cp .env.production.example .env && nano .env      # doldur
#   bash deploy/sunucu-kur.sh
#
# NE YAPAR
#   1. Docker yoksa kurar.
#   2. .env icindeki ZORUNLU degerleri kontrol eder (eksikse durur).
#   3. Alan adinin bu sunucuya isaret ettigini dogrular.
#   4. Guvenlik duvarini 22/80/443 ile sinirlar.
#   5. Yigini ayaga kaldirir ve HTTPS'in gercekten calistigini test eder.
#
# NEDEN KONTROLLER ONDE
# ---------------------
# Bu adimlarin cogu eksik yapilandirmayla BASLAR ama bir sure sonra
# bozulur: DNS yanlissa Let's Encrypt sertifika vermez ve bunu ancak
# uygulama baglanamayinca fark edersiniz. Hatanin kurulum aninda ve
# okunur bir mesajla cikmasi, saatler sonra "neden calismiyor" diye
# aramaktan iyidir.

set -euo pipefail

cd "$(dirname "$0")/.."
ROOT="$(pwd)"

kirmizi() { printf '\033[31m%s\033[0m\n' "$1"; }
yesil()   { printf '\033[32m%s\033[0m\n' "$1"; }
bilgi()   { printf '\033[36m%s\033[0m\n' "$1"; }

hata_var=0
dur() { kirmizi "HATA: $1"; hata_var=1; }

# ---------------------------------------------------------------- 1
bilgi "1/5  Docker kontrol ediliyor"

if ! command -v docker >/dev/null 2>&1; then
    bilgi "     Docker bulunamadi, kuruluyor..."
    curl -fsSL https://get.docker.com | sh
fi

if ! docker compose version >/dev/null 2>&1; then
    dur "docker compose eklentisi yok. 'apt-get install docker-compose-plugin' deneyin."
fi

# ---------------------------------------------------------------- 2
bilgi "2/5  .env dosyasi kontrol ediliyor"

if [ ! -f "$ROOT/.env" ]; then
    dur ".env yok. Once: cp .env.production.example .env && nano .env"
    exit 1
fi

# shellcheck disable=SC1091
set -a; . "$ROOT/.env"; set +a

zorunlu=(
    EVA_DOMAIN ACME_EMAIL
    POSTGRES_USER POSTGRES_PASSWORD POSTGRES_DB
    REDIS_PASSWORD
    ADMIN_API_KEY INTERNAL_SERVICE_MASTER_SECRET
    REVENUECAT_WEBHOOK_AUTH_SECRET
    OPENCHARGEMAP_API_KEY
    CORS_ALLOWED_ORIGINS
    PLAY_INTEGRITY_KEY_FILE
)

for ad in "${zorunlu[@]}"; do
    if [ -z "${!ad:-}" ]; then
        dur "$ad bos. .env dosyasini doldurun."
    fi
done

# Gelistirme parolalari uretime KACMAMALI.
case "${POSTGRES_PASSWORD:-}" in
    *eva_dev_password*) dur "POSTGRES_PASSWORD gelistirme parolasi. Degistirin: openssl rand -base64 32" ;;
esac

# Sirlarin uzunlugu, imza ve HMAC guvenliginin dogrudan belirleyicisi.
for ad in ADMIN_API_KEY INTERNAL_SERVICE_MASTER_SECRET; do
    deger="${!ad:-}"
    if [ "${#deger}" -lt 32 ]; then
        dur "$ad en az 32 karakter olmali (su an ${#deger})."
    fi
done

if [ -n "${PLAY_INTEGRITY_KEY_FILE:-}" ] && [ ! -f "$PLAY_INTEGRITY_KEY_FILE" ]; then
    dur "Play Integrity anahtari bulunamadi: $PLAY_INTEGRITY_KEY_FILE"
fi

# ---------------------------------------------------------------- 3
bilgi "3/5  Alan adi kontrol ediliyor"

if [ -n "${EVA_DOMAIN:-}" ]; then
    sunucu_ip="$(curl -fsS --max-time 10 https://api.ipify.org || true)"
    alan_ip="$(getent hosts "$EVA_DOMAIN" | awk '{print $1}' | head -1 || true)"

    if [ -z "$alan_ip" ]; then
        dur "$EVA_DOMAIN cozumlenmiyor. DNS A kaydini $sunucu_ip adresine yonlendirin."
    elif [ -n "$sunucu_ip" ] && [ "$alan_ip" != "$sunucu_ip" ]; then
        # Cloudflare proxy'si arkasindaysa IP'ler farkli olur; bu yuzden
        # durdurmuyor, uyariyoruz.
        kirmizi "UYARI: $EVA_DOMAIN -> $alan_ip, bu sunucu -> $sunucu_ip"
        kirmizi "       Cloudflare proxy kullaniyorsaniz normaldir."
        kirmizi "       Kullanmiyorsaniz Let's Encrypt sertifika VEREMEZ."
    else
        yesil "     $EVA_DOMAIN -> $alan_ip (bu sunucu)"
    fi
fi

if [ "$hata_var" -ne 0 ]; then
    kirmizi ""
    kirmizi "Kurulum durduruldu. Yukaridaki hatalari giderip tekrar calistirin."
    exit 1
fi

# ---------------------------------------------------------------- 4
bilgi "4/5  Guvenlik duvari"

if command -v ufw >/dev/null 2>&1; then
    # SSH ONCE acilir: once 'deny incoming' verip sonra SSH acmak,
    # uzaktan baglanan birini kendi sunucusundan atar.
    ufw allow 22/tcp  >/dev/null
    ufw allow 80/tcp  >/dev/null
    ufw allow 443/tcp >/dev/null
    ufw --force enable >/dev/null
    yesil "     22, 80, 443 acik; geri kalani kapali"
else
    kirmizi "UYARI: ufw yok, guvenlik duvari yapilandirilmadi."
fi

# ---------------------------------------------------------------- 5
bilgi "5/5  Yigin baslatiliyor"

docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d --build

bilgi "     Sertifika alinmasi ve servislerin hazir olmasi bekleniyor..."

saglik=""
for _ in $(seq 1 30); do
    sleep 5
    saglik="$(curl -fsS --max-time 5 "https://$EVA_DOMAIN/health" 2>/dev/null || true)"
    [ -n "$saglik" ] && break
done

echo ""
if [ -n "$saglik" ]; then
    yesil "KURULUM TAMAM"
    yesil "  https://$EVA_DOMAIN/health -> $saglik"
    echo ""
    bilgi "Sirada: Android local.properties icinde"
    bilgi "  EVA_GATEWAY_BASE_URL_RELEASE=https://$EVA_DOMAIN"
    bilgi "yazip yeniden derleyin."
else
    kirmizi "Servis 150 saniyede yanit vermedi."
    kirmizi "Loglar:  docker compose -f docker-compose.yml -f docker-compose.prod.yml logs --tail 50"
    kirmizi ""
    kirmizi "En sik iki sebep:"
    kirmizi "  - DNS bu sunucuya isaret etmiyor (Let's Encrypt sertifika veremez)"
    kirmizi "  - 80/443 baska bir servis tarafindan kullaniliyor"
    exit 1
fi
