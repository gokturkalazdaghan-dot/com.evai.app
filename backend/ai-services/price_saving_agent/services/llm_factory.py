# backend/ai-services/price_saving_agent/services/llm_factory.py
"""
Sağlayıcıdan bağımsız LLM fabrikası.

Bu serviste LLM'i YALNIZCA Voice Co-pilot kullanır: tek adımlı, düşük
gecikmeli, katı JSON çıktılı bir çağrı. Fiyat toplama hattı artık LLM
kullanmaz (bkz. services/tariff_pipeline.py) — bu yüzden burada tek bir
fabrika fonksiyonu vardır.

Desteklenen sağlayıcılar: anthropic | groq | gemini | openai_compatible
Groq/Gemini OpenAI-uyumlu uçlar üzerinden aynı istemciyle konuşulur.
"""
import logging

from langchain_core.language_models.chat_models import BaseChatModel

from config import settings

logger = logging.getLogger(__name__)

ANTHROPIC = "anthropic"
GROQ = "groq"
GEMINI = "gemini"
OPENAI_COMPATIBLE = "openai_compatible"

SUPPORTED_PROVIDERS = (ANTHROPIC, GROQ, GEMINI, OPENAI_COMPATIBLE)

# OpenAI-uyumlu HTTP uçları. Bunlar LangChain (voice) yolu içindir;
# CrewAI/litellm yolu sağlayıcıyı model önekinden çözdüğü için base_url'e
# ihtiyaç duymaz.
_OPENAI_COMPATIBLE_BASE_URLS = {
    GROQ: "https://api.groq.com/openai/v1",
    # Gemini'nin OpenAI uyumluluk katmanı. Not: bu uç noktada /models
    # listeleme YOKTUR (404 döner), yalnızca /chat/completions çalışır.
    GEMINI: "https://generativelanguage.googleapis.com/v1beta/openai",
}

# litellm'in sağlayıcıyı çözmesi için model adına eklenen önek.
_LITELLM_PREFIXES = {
    ANTHROPIC: "anthropic",
    GROQ: "groq",
    GEMINI: "gemini",
}


def _resolve_api_key(provider: str) -> str:
    keys = {
        ANTHROPIC: settings.anthropic_api_key,
        GROQ: settings.groq_api_key,
        GEMINI: settings.gemini_api_key,
        OPENAI_COMPATIBLE: settings.openai_compatible_api_key,
    }
    key = keys.get(provider, "")
    if not key:
        raise ValueError(
            f"'{provider}' sağlayıcısı seçildi ancak API anahtarı tanımlı değil. "
            f".env dosyasında ilgili *_API_KEY değerini doldurun."
        )
    return key


def _resolve_base_url(provider: str) -> str:
    if provider == OPENAI_COMPATIBLE:
        if not settings.openai_compatible_base_url:
            raise ValueError(
                "LLM sağlayıcısı 'openai_compatible' seçildi ancak "
                "OPENAI_COMPATIBLE_BASE_URL tanımlı değil."
            )
        return settings.openai_compatible_base_url
    return _OPENAI_COMPATIBLE_BASE_URLS[provider]


def _validate(provider: str) -> None:
    if provider not in SUPPORTED_PROVIDERS:
        raise ValueError(
            f"Desteklenmeyen LLM sağlayıcısı: '{provider}'. "
            f"Geçerli değerler: {', '.join(SUPPORTED_PROVIDERS)}"
        )


def build_chat_llm(
    *,
    provider: str,
    model: str,
    temperature: float,
    max_tokens: int | None = None,
) -> BaseChatModel:
    """Voice Co-pilot gibi doğrudan LangChain kullanan yollar için chat modeli üretir."""
    _validate(provider)
    api_key = _resolve_api_key(provider)

    if provider == ANTHROPIC:
        from langchain_anthropic import ChatAnthropic

        logger.info("LangChain LLM kuruldu: provider=anthropic model=%s", model)
        return ChatAnthropic(
            model=model,
            anthropic_api_key=api_key,
            temperature=temperature,
            max_tokens=max_tokens,
            max_retries=settings.llm_max_retries,
        )

    # Groq / Gemini / diğer OpenAI-uyumlu uçlar aynı istemciyi kullanır.
    # langchain-openai zaten kurulu (crewai bağımlılığı) — yeni paket gerekmez.
    from langchain_openai import ChatOpenAI

    base_url = _resolve_base_url(provider)
    logger.info(
        "LangChain LLM kuruldu: provider=%s model=%s base_url=%s",
        provider,
        model,
        base_url,
    )
    return ChatOpenAI(
        model=model,
        api_key=api_key,
        base_url=base_url,
        temperature=temperature,
        max_tokens=max_tokens,
        max_retries=settings.llm_max_retries,
    )
