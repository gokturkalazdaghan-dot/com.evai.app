#!/usr/bin/env bash
# dev-down.sh — Ortamı durdurur (veriler kalır).
set -euo pipefail
cd "$(dirname "$0")"
docker compose --profile mock down
echo "Durduruldu. Veriler korundu (docker volume'lerde). Sıfırdan başlamak için ./dev-reset.sh kullan."
