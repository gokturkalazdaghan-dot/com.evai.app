# backend/ai-services/price_saving_agent/verify_llm.py
"""
LLM saglayici dogrulama araci.

Calistirma:
    docker compose exec price-saving-agent python verify_llm.py

Ne yapar:
  1. .env'den cozulen saglayici/model yapilandirmasini gosterir (anahtarlar
     MASKELENIR).
  2. Saglayicinin sundugu model listesini ceker ve sectiginiz model adinin
     GERCEKTEN mevcut olup olmadigini soyler.
  3. Voice yolu icin kati-JSON duman testi yapar ve gecikmeyi olcer.

NOT: Fiyat toplama hatti artik LLM kullanmadigi icin burada test edilmez;
onu dogrulamak icin POST /v1/agent/run cagrilir.

Neden gerekli: model ID'leri saglayicilarda sik degisir. Yanlis bir ID,
uygulama icinde ancak ilk gercek istekte 404 olarak ortaya cikar.
"""
import json
import sys
import time

import httpx

from config import settings
from services.llm_factory import (
    GEMINI,
    GROQ,
    OPENAI_COMPATIBLE,
    build_chat_llm,
)

FENCE = "```"


def _mask(secret: str) -> str:
    if not secret:
        return "(bos)"
    return f"{secret[:4]}...{secret[-4:]} ({len(secret)} karakter)"


def _api_key_for(provider: str) -> str:
    return {
        "anthropic": settings.anthropic_api_key,
        GROQ: settings.groq_api_key,
        GEMINI: settings.gemini_api_key,
        OPENAI_COMPATIBLE: settings.openai_compatible_api_key,
    }.get(provider, "")


def list_models(provider: str) -> list[str]:
    """Saglayicidan kullanilabilir model listesini ceker."""
    key = _api_key_for(provider)
    if not key:
        return []

    try:
        if provider == GROQ:
            r = httpx.get(
                "https://api.groq.com/openai/v1/models",
                headers={"Authorization": f"Bearer {key}"},
                timeout=20,
            )
            r.raise_for_status()
            return sorted(m["id"] for m in r.json().get("data", []))

        if provider == GEMINI:
            # Gemini'nin OpenAI uyumluluk ucunda /models YOKTUR (404 doner).
            # Model listesi icin native uc kullanilir.
            r = httpx.get(
                "https://generativelanguage.googleapis.com/v1beta/models",
                params={"key": key},
                timeout=20,
            )
            r.raise_for_status()
            return sorted(
                m["name"].removeprefix("models/") for m in r.json().get("models", [])
            )

        if provider == OPENAI_COMPATIBLE:
            r = httpx.get(
                f"{settings.openai_compatible_base_url.rstrip('/')}/models",
                headers={"Authorization": f"Bearer {key}"},
                timeout=20,
            )
            r.raise_for_status()
            return sorted(m["id"] for m in r.json().get("data", []))
    except Exception as exc:
        print(f"    ! Model listesi alinamadi: {type(exc).__name__}: {exc}")
        return []

    return []


def check_voice() -> bool:
    """Voice yolu: kati JSON ciktisi + gecikme olcumu."""
    print("")
    print("[2] VOICE yolu -- kati JSON testi")
    print(
        f"    saglayici={settings.voice_llm_provider} "
        f"model={settings.voice_agent_model_name}"
    )

    try:
        llm = build_chat_llm(
            provider=settings.voice_llm_provider,
            model=settings.voice_agent_model_name,
            temperature=0.2,
            max_tokens=200,
        )
        prompt = (
            "Sadece su JSON semasina uygun yanit ver, baska hicbir metin ekleme: "
            '{"spoken_reply": "kisa bir selamlama", "follow_up_suggested": false}'
        )
        started = time.monotonic()
        reply = llm.invoke(prompt)
        elapsed_ms = int((time.monotonic() - started) * 1000)
        text = str(reply.content if hasattr(reply, "content") else reply)

        print(f"    gecikme: {elapsed_ms} ms")
        print(f"    ham yanit: {text[:200]}")

        cleaned = text.strip()
        if cleaned.startswith(FENCE):
            cleaned = cleaned[len(FENCE):]
            if cleaned.lower().startswith("json"):
                cleaned = cleaned[4:]
            cleaned = cleaned.rsplit(FENCE, 1)[0]

        json.loads(cleaned.strip())
        print("    [OK] Gecerli JSON dondu.")
        return True
    except json.JSONDecodeError:
        print("    [!] Yanit JSON olarak ayristirilamadi. Model JSON modunu")
        print("        desteklemiyor olabilir -- daha guclu bir model deneyin.")
        return False
    except Exception as exc:
        print(f"    [X] BASARISIZ: {type(exc).__name__}: {exc}")
        return False


def main() -> int:
    line = "=" * 62
    print(line)
    print(" EVA AI -- LLM saglayici dogrulamasi")
    print(line)

    print("")
    print("[1] Cozulen yapilandirma")
    workloads = (
        ("VOICE", settings.voice_llm_provider, settings.voice_agent_model_name),
    )
    for label, provider, model in workloads:
        print(f"    {label}: provider={provider}  model={model}")
        print(f"             anahtar={_mask(_api_key_for(provider))}")

    for label, provider, model in workloads:
        models = list_models(provider)
        if not models:
            continue
        found = model in models
        status = "[OK] mevcut" if found else "[X] BU MODEL LISTEDE YOK"
        print("")
        print(f"    {label} modeli '{model}': {status}")
        if not found:
            print(f"    Kullanilabilir ilk 15 model ({provider}):")
            for m in models[:15]:
                print(f"      - {m}")

    voice_ok = check_voice()

    print("")
    print(line)
    print(f" VOICE (JSON): {'BASARILI' if voice_ok else 'BASARISIZ'}")
    print(line)
    print(" NOT: Fiyat toplama hatti LLM KULLANMAZ (deterministik).")
    print("      Onu dogrulamak icin: POST /v1/agent/run")
    print(line)
    return 0 if voice_ok else 1


if __name__ == "__main__":
    sys.exit(main())
