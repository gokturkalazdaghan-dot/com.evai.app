# backend/ai-services/price_saving_agent/models/voice_schemas.py
from typing import Literal

from pydantic import BaseModel, Field


class VoiceQueryRequest(BaseModel):
    transcript: str = Field(..., min_length=2)
    lat: float
    lon: float
    battery_soc_percent: int | None = Field(default=None, ge=0, le=100)
    vehicle_connector_types: list[str] = Field(default_factory=list)
    language_code: str = "tr"
    # Konusma hafizasi anahtari. Gateway, imza dogrulamasindan gecen
    # cihaz kimligini buraya koyar; boylece hafiza cihaza baglidir ve
    # kullanicilar birbirinin baglamini gormez.
    session_id: str | None = None


class VoiceQueryResponse(BaseModel):
    spoken_reply: str
    # Istemcinin yanit disinda YAPMASI gereken sey.
    #   "none"     -> yalnizca konus
    #   "navigate" -> haritada recommended_station_id'ye rota ciz
    # Ayri bir alan olmasi sart: "rotayi ciziyorum" cumlesini metinden
    # tahmin etmeye calismak kirilgan olurdu (Eva her seferinde ayni
    # cumleyi kurmuyor - bu bilincli bir tasarim).
    action: Literal["none", "navigate"] = "none"
    recommended_station_id: str | None = None
    recommended_station_name: str | None = None
    distance_meters: int | None = None
    estimated_price_per_kwh: float | None = None
    follow_up_suggested: bool = False
