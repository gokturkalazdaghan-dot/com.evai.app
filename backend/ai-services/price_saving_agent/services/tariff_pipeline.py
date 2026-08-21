# backend/ai-services/price_saving_agent/services/tariff_pipeline.py
"""
Fiyat toplama hattı — DETERMINISTIK.

NEDEN LLM YOK?
--------------
Bu hat daha önce CrewAI tabanlı iki ajanlı bir ReAct döngüsüyle
çalışıyordu. Ölçüm ve inceleme sonucunda LLM'in bu zincirde hiçbir
muhakeme yapmadığı ortaya çıktı:

  * fetch adımı  : sabit parametrelerle HTTP GET -- girdiler zaten görev
                   tanımında yazılıydı, model yalnızca onları araca
                   kopyalıyordu.
  * validate adımı: bilinen bir alanı (station_id) eklemek + bir eşik
                   kuralıyla confidence_score atamak + fonksiyon çağırmak.

Yani model, pahalı ve güvenilmez bir alan eşleyici olarak kullanılıyordu.
Somut sonuçları:

  1) GÜVENİLİRLİK: Ölçümde bir model aracı hiç çağırmadan fiyat UYDURDU
     ("0.1524 TL/kWh") ve koşu "başarılı" göründü. Eski görev tanımı bunu
     prompt'la engellemeye çalışıyordu ("hiçbir sayıyı kendin tahmin
     etme") -- yapısal olması gereken bir garanti için yanlış katman.
     Kod aracı çağırdığında bu risk tamamen ortadan kalkar.
  2) MALİYET: ReAct döngüsü istasyon başına ~8-10k token tüketiyordu
     (araç tanımları + backstory + biriken görev geçmişi her turda
     yeniden gönderiliyordu). Şimdi sıfır.
  3) HIZ: ~5 saniyelik döngü yerine tek bir HTTP çağrısı.

Eski tasarımın "fiyat toplama ve doğrulama ayrı olsun ki hatalı fiyat tek
bir kararla yazılamasın" gerekçesi KORUNDU -- ama artık iki LLM ajanıyla
değil, iki ayrı deterministik aşamayla (fetch / validate) sağlanıyor. Bir
LLM ikna edilebilir; bir eşik kuralı edilemez.
"""
import asyncio
import logging
from dataclasses import dataclass
from decimal import Decimal
from enum import Enum

import httpx
from tenacity import (
    retry,
    retry_if_exception_type,
    stop_after_attempt,
    wait_exponential,
)

from config import settings
from models.tariff_schemas import RawTariffQuote, ResolvedTariff
from services.anonymizer import anonymizer
from services.currency_rules import expected_currency_for, is_currency_plausible
from services.db_service import db_service
from services.redis_publisher import redis_publisher

logger = logging.getLogger(__name__)

# Aynı anda kaç istasyonun tarifesi çekilsin. CPO Aggregator'ı boğmamak
# için sınırlı; LLM olmadığından tek darboğaz karşı tarafın kapasitesi.
_MAX_CONCURRENT_FETCHES = 8

# --- Güven skoru eşikleri -------------------------------------------
# Eski LLM prompt'u "normal fiyatlar için 0.9, şüpheli/aşırı yüksek için
# 0.3" diyordu. Aynı niyet, artık ölçülebilir bir kural olarak: yeni fiyat
# istasyonun SON kaydedilmiş fiyatından ne kadar saptı?
_DEVIATION_TRUSTED = Decimal("0.25")   # %25'e kadar sapma normal dalgalanma
_DEVIATION_SUSPECT = Decimal("0.60")   # %60'a kadar şüpheli ama olası
_SCORE_TRUSTED = 0.9
_SCORE_UNCERTAIN = 0.6
_SCORE_SUSPECT = 0.3
# Karşılaştırılacak geçmiş kayıt yoksa ne kendinden emin ne de şüpheci
# olabiliriz -- nötr bir taban.
_SCORE_NO_BASELINE = 0.7


class OutcomeStatus(str, Enum):
    PERSISTED = "PERSISTED"
    CACHE_DEGRADED = "CACHE_DEGRADED"  # DB'ye yazıldı, Redis güncellenemedi
    FETCH_FAILED = "FETCH_FAILED"
    INVALID_RESPONSE = "INVALID_RESPONSE"
    # Veri geldi ama guvenilmez oldugu icin YAZILMADI (orn. para birimi
    # istasyonun ulkesiyle uyusmuyor).
    VALIDATION_FAILED = "VALIDATION_FAILED"
    WRITE_FAILED = "WRITE_FAILED"


@dataclass(frozen=True)
class StationOutcome:
    station_id: str
    status: OutcomeStatus
    message: str

    @property
    def is_written(self) -> bool:
        """DB'ye yazıldı mı (cache bozuk olsa bile)."""
        return self.status in (OutcomeStatus.PERSISTED, OutcomeStatus.CACHE_DEGRADED)


class TariffFetchError(Exception):
    """Tarife çekilemedi; bu istasyon atlanmalı."""


@retry(
    stop=stop_after_attempt(3),
    wait=wait_exponential(multiplier=1, min=1, max=6),
    retry=retry_if_exception_type(
        (httpx.ConnectTimeout, httpx.ReadTimeout, httpx.ConnectError)
    ),
    reraise=True,
)
async def _get_tariff(client: httpx.AsyncClient, external_ref: str, cpo_code: str) -> dict:
    response = await client.get(
        f"{settings.ocpi_aggregator_base_url}/tariffs",
        params={"station_ref": external_ref, "cpo": cpo_code},
        headers={"Authorization": f"Bearer {settings.ocpi_aggregator_api_key}"},
    )
    response.raise_for_status()
    return response.json()


async def fetch_raw_tariff(
    client: httpx.AsyncClient, external_ref: str, cpo_code: str
) -> RawTariffQuote:
    """
    AŞAMA 1 -- Toplama. CPO Aggregator'dan ham tarifeyi çeker, PII'den
    arındırır ve şemaya karşı doğrular. Buradan dönen her değer API'den
    gelmiştir; hiçbir alan tahmin edilmez.
    """
    try:
        raw = await _get_tariff(client, external_ref, cpo_code)
    except httpx.HTTPStatusError as exc:
        raise TariffFetchError(
            f"{cpo_code}/{external_ref}: HTTP {exc.response.status_code}"
        ) from exc
    except (httpx.ConnectTimeout, httpx.ReadTimeout, httpx.ConnectError) as exc:
        raise TariffFetchError(f"{cpo_code}: servis erişilemez (3 deneme sonrası)") from exc
    except Exception as exc:
        raise TariffFetchError(f"{cpo_code}/{external_ref}: {type(exc).__name__}") from exc

    # Sıfır-PII güvenlik ağı: bir CPO'nun serbest metin alanına yanlışlıkla
    # teknisyen telefonu/e-postası sızdırmış olma ihtimaline karşı.
    sanitized, anonymization = anonymizer.sanitize_dict(raw)
    if anonymization.had_pii:
        logger.warning(
            "CPO yanıtında PII tespit edilip maskelendi: ref=%s cpo=%s categories=%s",
            external_ref,
            cpo_code,
            [c.value for c in anonymization.detected_categories],
        )

    try:
        return RawTariffQuote(
            station_external_ref=external_ref,
            cpo_code=cpo_code,
            price_per_kwh=sanitized["price_per_kwh"],
            currency=sanitized["currency"],
            session_fee=sanitized.get("session_fee", 0),
            is_dynamic_pricing=sanitized.get("is_dynamic_pricing", False),
            valid_from_iso=sanitized["valid_from"],
            valid_until_iso=sanitized.get("valid_until"),
        )
    except (KeyError, ValueError) as exc:
        raise TariffFetchError(f"{cpo_code}/{external_ref}: yanıt şeması geçersiz") from exc


def score_confidence(new_price: Decimal, previous_price: Decimal | None) -> float:
    """
    AŞAMA 2 -- Doğrulama. Yeni fiyatın son bilinen fiyattan sapmasına göre
    güven skoru üretir. Saf fonksiyon: aynı girdi her zaman aynı çıktıyı
    verir, bu yüzden test edilebilir ve denetlenebilir.
    """
    # Bedava şarj gerçek olabilir ama bir API hatası da aynı görünür;
    # düşük güvenle işaretlenip yazılır, sessizce kabul edilmez.
    if new_price <= 0:
        return _SCORE_SUSPECT

    if previous_price is None or previous_price <= 0:
        return _SCORE_NO_BASELINE

    deviation = abs(new_price - previous_price) / previous_price
    if deviation <= _DEVIATION_TRUSTED:
        return _SCORE_TRUSTED
    if deviation <= _DEVIATION_SUSPECT:
        return _SCORE_UNCERTAIN
    return _SCORE_SUSPECT


async def process_station(client: httpx.AsyncClient, station: dict) -> StationOutcome:
    """Tek bir istasyon için: topla -> doğrula -> yaz."""
    station_id = str(station["station_id"])

    try:
        quote = await fetch_raw_tariff(client, station["external_ref"], station["cpo_code"])
    except TariffFetchError as exc:
        logger.warning("Tarife çekilemedi: %s", exc)
        return StationOutcome(station_id, OutcomeStatus.FETCH_FAILED, str(exc))

    # PARA BIRIMI DOGRULAMASI -- fiyat yazilmadan ONCE.
    #
    # Yanlis para birimi, yanlis fiyattan daha tehlikelidir cunku fark
    # edilmez: "8,28" rakami makul gorunur ve kullanici para birimini
    # okumadan karsilastirma yapar. Olculdu: mock toplayici ABD'deki
    # istasyonlara TRY yaziyordu.
    #
    # Uyusmayan tarife YAZILMAZ. Fiyati "bilinmiyor" birakmak, yanlis
    # para biriminde bir fiyat gostermekten iyidir.
    country_code = station.get("country_code")
    if not is_currency_plausible(quote.currency.value, country_code):
        expected = expected_currency_for(country_code) or "?"
        message = (
            f"{country_code} ulkesindeki istasyona {quote.currency.value} "
            f"tarifesi geldi; beklenen {expected}"
        )
        logger.error("Para birimi uyusmazligi, tarife reddedildi: %s (%s)", station_id, message)
        return StationOutcome(station_id, OutcomeStatus.VALIDATION_FAILED, message)

    try:
        previous_price = await db_service.get_last_price_per_kwh(station_id)
    except Exception as exc:
        # Geçmiş okunamazsa koşuyu durdurma; taban yokmuş gibi davran.
        logger.warning(
            "Geçmiş fiyat okunamadı, nötr güven skoru kullanılıyor: station_id=%s (%s)",
            station_id,
            type(exc).__name__,
        )
        previous_price = None

    confidence = score_confidence(quote.price_per_kwh, previous_price)
    if confidence <= _SCORE_SUSPECT:
        logger.warning(
            "Şüpheli fiyat sıçraması: station_id=%s yeni=%s onceki=%s skor=%s",
            station_id,
            quote.price_per_kwh,
            previous_price,
            confidence,
        )

    try:
        resolved = ResolvedTariff(
            station_id=station_id,
            price_per_kwh=quote.price_per_kwh,
            currency=quote.currency,
            session_fee=quote.session_fee,
            is_dynamic_pricing=quote.is_dynamic_pricing,
            confidence_score=confidence,
        )
    except ValueError as exc:
        logger.error("ResolvedTariff oluşturulamadı: station_id=%s", station_id, exc_info=exc)
        return StationOutcome(station_id, OutcomeStatus.INVALID_RESPONSE, str(exc))

    try:
        await db_service.insert_tariff_snapshot(resolved)
    except Exception as exc:
        logger.error("PostgreSQL yazımı başarısız: station_id=%s", station_id, exc_info=exc)
        return StationOutcome(station_id, OutcomeStatus.WRITE_FAILED, str(exc))

    try:
        await redis_publisher.publish_live_tariff(resolved)
    except Exception as exc:
        # DB yazımı KORUNUR -- cache tutarsızlığı veri kaybından iyidir.
        logger.error(
            "Redis yayını başarısız (DB yazımı korunuyor): station_id=%s",
            station_id,
            exc_info=exc,
        )
        return StationOutcome(station_id, OutcomeStatus.CACHE_DEGRADED, str(exc))

    return StationOutcome(
        station_id,
        OutcomeStatus.PERSISTED,
        f"{quote.price_per_kwh} {quote.currency.value}/kWh (güven={confidence})",
    )


async def run_for_stations(stations: list[dict]) -> list[StationOutcome]:
    """
    Bir bölgedeki istasyonları sınırlı eşzamanlılıkla işler. LLM
    olmadığından tek sınır CPO Aggregator'ın kapasitesidir.
    """
    semaphore = asyncio.Semaphore(_MAX_CONCURRENT_FETCHES)

    async with httpx.AsyncClient(timeout=settings.ocpi_request_timeout_seconds) as client:

        async def guarded(station: dict) -> StationOutcome:
            async with semaphore:
                return await process_station(client, station)

        results = await asyncio.gather(
            *(guarded(s) for s in stations), return_exceptions=True
        )

    outcomes: list[StationOutcome] = []
    for station, result in zip(stations, results):
        if isinstance(result, BaseException):
            station_id = str(station["station_id"])
            logger.error("İstasyon işlenirken beklenmeyen hata: %s", station_id, exc_info=result)
            outcomes.append(
                StationOutcome(
                    station_id,
                    OutcomeStatus.FETCH_FAILED,
                    f"beklenmeyen hata: {type(result).__name__}",
                )
            )
        else:
            outcomes.append(result)

    return outcomes
