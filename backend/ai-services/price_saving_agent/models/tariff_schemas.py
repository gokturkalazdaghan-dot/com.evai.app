# backend/ai-services/price_saving_agent/models/tariff_schemas.py
from decimal import Decimal
from enum import Enum
from typing import Optional
from pydantic import BaseModel, Field, field_validator


class CurrencyCode(str, Enum):
    USD = "USD"
    EUR = "EUR"
    GBP = "GBP"
    TRY = "TRY"
    CHF = "CHF"


class RawTariffQuote(BaseModel):
    """CPO Aggregator API'den ham gelen tarife verisi."""

    station_external_ref: str
    cpo_code: str
    price_per_kwh: Decimal = Field(..., ge=0)
    currency: CurrencyCode
    session_fee: Decimal = Field(default=Decimal("0"), ge=0)
    is_dynamic_pricing: bool = False
    valid_from_iso: str
    valid_until_iso: Optional[str] = None

    @field_validator("price_per_kwh", "session_fee", mode="before")
    @classmethod
    def coerce_decimal(cls, v: object) -> Decimal:
        if isinstance(v, (int, float, str)):
            return Decimal(str(v))
        if isinstance(v, Decimal):
            return v
        raise ValueError(f"Geçersiz sayısal değer: {v!r}")


class ResolvedTariff(BaseModel):
    """Ajanın normalize edip DB'ye/Redis'e yazacağı nihai tarife kaydı."""

    station_id: str
    price_per_kwh: Decimal
    currency: CurrencyCode
    session_fee: Decimal
    is_dynamic_pricing: bool
    confidence_score: float = Field(..., ge=0.0, le=1.0)
    source_agent: str = "price_saving_agent"
