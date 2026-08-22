# backend/ai-services/price_saving_agent/tests/conftest.py
"""
Test kurulumu.

config.Settings, DATABASE/REDIS gibi bazi degerleri ZORUNLU tutuyor ve
modul import edilir edilmez okunuyor. Testler gercek bir veritabanina
baglanmadigi icin bu degerlere yalnizca "var olsunlar" diye ihtiyac var;
gercek baglanti kurulmuyor.

Ortam degiskenleri config import edilmeden ONCE yazilmali -- bu yuzden
fixture degil, modul seviyesinde.
"""
import os
import sys
from pathlib import Path

# Ajan kokunu import yoluna ekle: testler `services.x` diye import ediyor.
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

os.environ.setdefault("POSTGRES_DSN", "postgresql://test:test@localhost:5432/test")
os.environ.setdefault("REDIS_URL", "redis://localhost:6379")
os.environ.setdefault("OCPI_AGGREGATOR_BASE_URL", "http://localhost:9999")
os.environ.setdefault("OCPI_AGGREGATOR_API_KEY", "test-key")
os.environ.setdefault(
    "INTERNAL_SERVICE_MASTER_SECRET",
    "test_only_internal_secret_min_32_characters_xx",
)

# .env dosyasi varsa testleri gelistiricinin yerel degerleriyle
# kirletmesin: testler her makinede ayni davranmali.
os.environ.setdefault("ENV_FILE_DISABLED", "1")
