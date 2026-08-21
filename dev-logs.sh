#!/usr/bin/env bash
# dev-logs.sh — Tüm servislerin canlı loglarını gösterir.
# Belirli bir servisi izlemek için: ./dev-logs.sh gateway
set -euo pipefail
cd "$(dirname "$0")"

if [ $# -eq 0 ]; then
  docker compose --profile mock logs -f --tail=100
else
  docker compose --profile mock logs -f --tail=100 "$1"
fi
