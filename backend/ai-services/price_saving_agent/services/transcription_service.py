# backend/ai-services/price_saving_agent/services/transcription_service.py
"""
Konusmayi metne cevirir (STT).

NEDEN SUNUCUDA?
---------------
Groq API anahtari UYGULAMAYA KONMAZ. Bir APK'dan string cikarmak
onemsizdir; anahtar gomulseydi herkes kendi kotamizdan harcama yapabilirdi.
Ses telefondan gateway'e yuklenir, transkripsiyon burada yapilir.

NEDEN WHISPER?
--------------
Test cihazi dahil bircok Android telefonda `SpeechRecognizer` YOKTUR
("Bu cihazda ses tanima kullanilamiyor" - Google uygulamasi gerekir).
Groq'un whisper-large-v3-turbo modeli bu bosluğu doldurur ve Turkce'yi
iyi tanir.

LLM DEGIL: bu bir transkripsiyon adimidir, yorumlama degil. Model ne
duyduysa onu yazar; ne yapilacagina Voice Co-pilot ajani karar verir.
"""
import logging

import httpx

from config import settings

logger = logging.getLogger(__name__)

GROQ_TRANSCRIPTION_URL = "https://api.groq.com/openai/v1/audio/transcriptions"

# turbo: daha hizli ve ucuz; surucu yanit beklerken gecikme kritik.
DEFAULT_MODEL = "whisper-large-v3-turbo"

# Groq'un ses dosyasi siniri comfortably altinda; 25 sn'lik bir soru
# ~800 KB (16 kHz mono 16-bit) eder. Bunun uzerini reddetmek, yanlislikla
# baslatilmis uzun kayitlarin kotayi yakmasini onler.
MAX_AUDIO_BYTES = 2 * 1024 * 1024

# Whisper'a verilen baglam ipucu (bkz. asagidaki "prompt" kullanimi).
TRANSCRIPTION_PROMPT = (
    "Eva, elektrikli arac sarj asistani. "
    "Sarj istasyonu, kWh, fiyat, rota, menzil, CCS, Tesla."
)


class TranscriptionError(Exception):
    """Ses metne cevrilemedi."""


async def transcribe(
    audio_bytes: bytes,
    filename: str = "speech.wav",
    language: str = "tr",
) -> str:
    """
    Ses baytlarini metne cevirir.

    Bos ya da anlamsiz ses icin BOS STRING doner -- uydurma bir metin
    uretmez. Cagiran taraf bos transkripti "anlasilmadi" olarak ele almali.
    """
    if not settings.groq_api_key:
        raise TranscriptionError("GROQ_API_KEY tanimli degil; transkripsiyon yapilamaz.")

    if not audio_bytes:
        return ""

    if len(audio_bytes) > MAX_AUDIO_BYTES:
        raise TranscriptionError(
            f"Ses dosyasi cok buyuk ({len(audio_bytes)} bayt, "
            f"sinir {MAX_AUDIO_BYTES})."
        )

    try:
        async with httpx.AsyncClient(timeout=30.0) as client:
            response = await client.post(
                GROQ_TRANSCRIPTION_URL,
                headers={"Authorization": f"Bearer {settings.groq_api_key}"},
                files={"file": (filename, audio_bytes, "audio/wav")},
                data={
                    "model": DEFAULT_MODEL,
                    "language": language,
                    # Sadece metin iste; segment/zaman damgasi gereksiz yuk.
                    "response_format": "json",
                    # 0.0: belirlenimci. Transkripsiyonda yaraticilik
                    # istemiyoruz -- ne duyulduysa o yazilmali.
                    "temperature": "0",
                    # BAGLAM IPUCU: Whisper'in `prompt` alani modeli belirli
                    # bir kelime dagarcigina yonlendirir.
                    #
                    # Neden gerekli: "Eva" tek basina soylendiginde model
                    # bunu duzenli olarak "Evet." diye yaziyordu -- Turkce'de
                    # "evet" cok daha sik gectigi icin dil modeli oraya
                    # kayiyor. Prompt'ta ismi ve alan terimlerini vermek bu
                    # egilimi kiriyor; ayni zamanda "sarj", "kWh", "istasyon"
                    # gibi terimlerin dogru yazilmasini da iyilestiriyor.
                    "prompt": TRANSCRIPTION_PROMPT,
                },
            )
            response.raise_for_status()
            payload = response.json()
    except httpx.HTTPStatusError as exc:
        detail = exc.response.text[:200] if exc.response is not None else ""
        logger.error("Groq transkripsiyon HTTP hatasi: %s %s", exc.response.status_code, detail)
        raise TranscriptionError(f"Transkripsiyon servisi hata dondu ({exc.response.status_code}).") from exc
    except Exception as exc:
        logger.error("Transkripsiyon basarisiz.", exc_info=exc)
        raise TranscriptionError("Ses metne cevrilemedi.") from exc

    text = (payload.get("text") or "").strip()
    logger.info("Transkripsiyon tamamlandi: %s karakter", len(text))
    return text
