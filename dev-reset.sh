#!/usr/bin/env bash
# dev-reset.sh — Her şeyi (veritabanı dahil) sıfırlar. Şema baştan yüklenir.
# DİKKAT: Yerel test verilerini siler. Production'da ASLA kullanma.
set -euo pipefail
cd "$(dirname "$0")"

read -p "Tüm yerel veriler silinecek. Emin misin? (evet/hayır): " confirm
if [ "$confirm" != "evet" ]; then
  echo "İptal edildi."
  exit 0
fi

docker compose --profile mock down -v
docker compose --profile mock up -d --build
echo "Sıfırlandı ve yeniden başlatıldı."
