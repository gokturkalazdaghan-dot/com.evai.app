#!/usr/bin/env bash
# dev-up.sh — Yorgun olduğunda tek komutla ortamı ayağa kaldır.
# Kullanım: ./dev-up.sh
set -euo pipefail

cd "$(dirname "$0")"

if [ ! -f .env ]; then
  echo "HATA: .env dosyası yok. Önce şunu çalıştır: cp .env.example .env"
  echo "Sonra .env içindeki degerleri doldur (OPENCHARGEMAP_API_KEY, OCPI_*)."
  exit 1
fi

echo "Eva yerel ortamı başlatılıyor (Postgres+PostGIS, Redis, Gateway, AI Ajanı, Mock CPO)..."
docker compose --profile mock up -d --build

echo ""
echo "Hazır. Kontrol için:"
echo "  Gateway sağlık:      curl http://localhost:3000/v1/stations/nearby?lat=41.0082\\&lon=28.9784\\&radiusMeters=15000"
echo "  AI Ajanı sağlık:     curl http://localhost:8000/health"
echo "  Mock CPO sağlık:     curl http://localhost:9999/health"
echo "  Logları izle:        ./dev-logs.sh"
echo "  Ortamı durdur:       ./dev-down.sh"
