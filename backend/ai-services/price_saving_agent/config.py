# backend/ai-services/price_saving_agent/config.py
from pydantic import model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", env_file_encoding="utf-8")

    # Veritabanı — sıfır-PII: bu servis yalnızca istasyon/tarife tablolarına erişir
    postgres_dsn: str
    redis_url: str

    # ------------------------------------------------------------------
    # LLM sağlayıcı seçimi
    # ------------------------------------------------------------------
    # LLM'i YALNIZCA Voice Co-pilot kullanır: tek adım, katı JSON, sürücü
    # yanıtı beklerken duruyor -> gecikme kritik.
    #
    # Fiyat toplama hattı LLM KULLANMAZ (bkz. services/tariff_pipeline.py):
    # zincir tamamen deterministiktir, bu yüzden eskiden burada bulunan
    # PRICING_LLM_PROVIDER / AGENT_MODEL_NAME ayarları KALDIRILDI. Ölü
    # yapılandırma bırakmak, "hangi model kullanılıyor?" sorusuna yanlış
    # cevap veren bir tuzak olurdu.
    #
    # Geçerli değerler: anthropic | groq | gemini | openai_compatible
    voice_llm_provider: str = "anthropic"

    # API anahtarları — YALNIZCA seçilen sağlayıcınınki zorunludur
    # (bkz. aşağıdaki _require_selected_provider_keys doğrulayıcısı).
    anthropic_api_key: str = ""
    groq_api_key: str = ""
    gemini_api_key: str = ""

    # Listede olmayan herhangi bir OpenAI-uyumlu sağlayıcı için kaçış kapısı
    # (OpenRouter, Cerebras, LLM7.io, yerel vLLM/Ollama ...).
    openai_compatible_api_key: str = ""
    openai_compatible_base_url: str = ""

    # 429 (rate limit) durumunda litellm/LangChain kac kez ustel geri
    # cekilmeyle yeniden denesin. Ucretsiz katmanlarda TPM tavani dusuk
    # oldugu icin varsayilan bilincli olarak yuksek tutuldu.
    llm_max_retries: int = 5

    # Model adları — sağlayıcı ÖNEKİ OLMADAN yazılır ("gemini-2.0-flash"),
    # önek llm_factory tarafından eklenir.
    voice_agent_model_name: str = "claude-sonnet-4-6"

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

    # Voice Co-pilot ajanı parametreleri
    voice_agent_max_response_chars: int = 280

    @model_validator(mode="after")
    def _require_selected_provider_keys(self) -> "Settings":
        """
        Yalnızca GERÇEKTEN kullanılan sağlayıcının anahtarını zorunlu kılar.
        Böylece Gemini'ye geçtiğinizde ANTHROPIC_API_KEY tanımlamak zorunda
        kalmazsınız; ama seçtiğiniz sağlayıcının anahtarını unutursanız
        servis sessizce değil, AÇILIŞTA net bir hatayla durur.
        """
        required_key_by_provider = {
            "anthropic": ("anthropic_api_key", "ANTHROPIC_API_KEY"),
            "groq": ("groq_api_key", "GROQ_API_KEY"),
            "gemini": ("gemini_api_key", "GEMINI_API_KEY"),
            "openai_compatible": (
                "openai_compatible_api_key",
                "OPENAI_COMPATIBLE_API_KEY",
            ),
        }

        for workload, provider in (
            ("VOICE_LLM_PROVIDER", self.voice_llm_provider),
        ):
            if provider not in required_key_by_provider:
                raise ValueError(
                    f"{workload}='{provider}' geçersiz. Geçerli değerler: "
                    f"{', '.join(required_key_by_provider)}"
                )

            attr, env_name = required_key_by_provider[provider]
            if not getattr(self, attr):
                raise ValueError(
                    f"{workload}='{provider}' seçildi ancak {env_name} boş. "
                    f".env dosyasında bu değeri doldurun."
                )

            if provider == "openai_compatible" and not self.openai_compatible_base_url:
                raise ValueError(
                    f"{workload}='openai_compatible' seçildi ancak "
                    f"OPENAI_COMPATIBLE_BASE_URL boş."
                )

        return self


settings = Settings()
