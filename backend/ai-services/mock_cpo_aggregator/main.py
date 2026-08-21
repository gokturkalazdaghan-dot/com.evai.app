# backend/ai-services/mock_cpo_aggregator/main.py
"""
Gerçek bir CPO Aggregator sözleşmesi tamamlanana kadar (bkz. ana
EVA-ROADMAP.md Faz 5b), Fiyat Tasarruf Ajanı'nın (price_saving_agent)
tariff_fetch_tool.py aracını yerelde test edebilmek için minimal bir sahte
(mock) OCPI Aggregator sunucusu.

Gerçek API'nin döneceği şemayı taklit eder: {station_ref, cpo} query
parametreleriyle gelen isteğe rastgele ama makul bir fiyat döndürür.
Kasıtlı olarak zaman zaman hata/gecikme simüle eder ki
tariff_fetch_tool.py'deki retry/hata yönetimi de gerçekçi koşullarda
test edilebilsin.

Çalıştırma: uvicorn main:app --port 9999
"""
import random
import time
from datetime import datetime, timedelta, timezone

from fastapi import FastAPI, HTTPException, Query, status

app = FastAPI(title="Mock CPO Aggregator (yalnızca yerel geliştirme için)")

# Belirli istasyon referansları için sabit taban fiyatlar — testlerin
# tekrarlanabilir olması için tamamen rastgele değil, hafif dalgalanan.
_BASE_PRICES_BY_STATION: dict[str, float] = {}


# external_ref onekinden ulke cikarimi. Gercek bir CPO bunu kendi
# yanitinda bildirir; mock'ta referans adindan turetiliyor.
_REF_CURRENCY = {
    "US": "USD",
    "GB": "GBP",
    "DE": "EUR",
}


def _currency_for(station_ref: str) -> str:
    ref = (station_ref or "").upper()
    for prefix, currency in _REF_CURRENCY.items():
        if prefix in ref:
            return currency
    return "TRY"


def _get_base_price(station_ref: str) -> float:
    if station_ref not in _BASE_PRICES_BY_STATION:
        _BASE_PRICES_BY_STATION[station_ref] = round(random.uniform(6.5, 12.5), 2)
    return _BASE_PRICES_BY_STATION[station_ref]


@app.get("/health")
async def health() -> dict[str, str]:
    return {"status": "ok", "service": "mock_cpo_aggregator"}


@app.get("/tariffs")
async def get_tariff(
    station_ref: str = Query(..., alias="station_ref"),
    cpo: str = Query(...),
):
    # %10 ihtimalle yapay gecikme (retry mekanizmasını tetiklemek için)
    if random.random() < 0.10:
        time.sleep(9)  # tariff_fetch_tool.py'deki timeout'u (8s) aşacak şekilde

    # %5 ihtimalle 503 döndür (tariff_fetch_tool.py'nin HTTP hata yönetimini
    # test etmek için)
    if random.random() < 0.05:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="Mock CPO: geçici servis kesintisi (simüle edilmiş).",
        )

    base_price = _get_base_price(station_ref)
    # Anlık tarife dalgalanması simülasyonu (+-%15)
    live_price = round(base_price * random.uniform(0.85, 1.15), 4)

    now = datetime.now(timezone.utc)

    return {
        "price_per_kwh": live_price,
        # Para birimi istasyonun ulkesine gore. Onceden sabit "TRY" idi
        # ve ABD'deki istasyonlara Turk Lirasi fiyati yaziliyordu.
        "currency": _currency_for(station_ref),
        "session_fee": round(random.choice([0, 0, 0, 2.5, 5.0]), 2),
        "is_dynamic_pricing": random.random() < 0.3,
        "valid_from": now.isoformat(),
        "valid_until": (now + timedelta(hours=1)).isoformat(),
    }
