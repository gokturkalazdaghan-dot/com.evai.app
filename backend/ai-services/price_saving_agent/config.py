# backend/ai-services/price_saving_agent/config.py
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        # Ortamda tanimadigimiz degiskenler bulunmasi HATA DEGIL:
        # dagitim ortami baska servisler icin degisken tasiyabilir ve
        # kaldirilan ayarlarin (orn. GROQ_API_KEY) env dosyalarindan
        # temizlenmesi gecikebilir. Acikca yaziliyor cunku kutuphane
        # varsayilanina bel baglamak surum degisiminde kirilir.
        extra="ignore",
    )

    # Veritabanı — sıfır-PII: bu servis yalnızca istasyon/tarife tablolarına erişir
    postgres_dsn: str
    redis_url: str

    # ------------------------------------------------------------------
    # Bu servis LLM KULLANMAZ.
    #
    # Fiyat toplama hattı deterministiktir (bkz.
    # services/tariff_pipeline.py) ve LLM'e ihtiyaç duyan tek parça
    # olan Voice Co-pilot üründen çıkarıldı (bkz.
    # _archive/voice-assistant/). Sağlayıcı seçimi ve API anahtarı
    # ayarları bu yüzden KALDIRILDI: ölü yapılandırma bırakmak,
    # "hangi model kullanılıyor?" sorusuna yanlış cevap veren bir
    # tuzak olur. Dahası, o ayarların doğrulayıcısı ANTHROPIC_API_KEY
    # boşken servisi AÇILIŞTA durduruyordu — kimsenin kullanmadığı bir
    # özellik yüzünden fiyat ajanı hiç başlamıyordu.

    # Open Charge Map — istasyon ENVANTERI kaynagi (anlik doluluk YOK).
    # Ucretsiz anahtar: https://openchargemap.org/site/develop/api
    openchargemap_api_key: str = ""
    openchargemap_default_radius_km: int = 25
    openchargemap_max_results: int = 200

    # CPO Aggregator dış API'leri
    ocpi_aggregator_base_url: str
    ocpi_aggregator_api_key: str
    ocpi_request_timeout_seconds: float = 8.0

    # Ajan çalışma parametreleri
    max_stations_per_batch: int = 50
    tariff_cache_ttl_seconds: int = 60
    agent_run_interval_seconds: int = 300

    # Gateway <-> Python dahili servis kimlik doğrulaması (bkz.
    # services/internal_auth.py). NestJS Gateway'deki
    # INTERNAL_SERVICE_MASTER_SECRET ile BİREBİR AYNI değer olmalı.
    internal_service_master_secret: str

settings = Settings()
