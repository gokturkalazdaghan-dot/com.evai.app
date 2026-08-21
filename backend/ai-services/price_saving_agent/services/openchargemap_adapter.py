# backend/ai-services/price_saving_agent/services/openchargemap_adapter.py
"""
Open Charge Map (OCM) istasyon envanteri adaptoru.

NE YAPAR / NE YAPMAZ
--------------------
YAPAR : Istasyon envanteri -- konum, ad, isletmeci, soket tipleri, guc,
        AC/DC. Turkiye kapsami iyi, lisansi izin verir, anahtar ucretsiz.

YAPMAZ: ANLIK DOLULUK. OCM'de "su anda bos mu" bilgisi YOKTUR. Bu yuzden
        adapter `supports_availability = False` bildirir ve doluluk
        alanlarina HIC dokunmaz. Doluluk verisi olmadan yesil/kirmizi pin
        gostermek surucuyu yanlis istasyona yollar; "bilinmiyor" durumu
        korunmali.

        OCM'deki `StatusType.IsOperational` alani da doluluk DEGIL, cihazin
        calisir olup olmadigidir -- ve cogu Turkiye kaydinda `None` gelir.

LLM KULLANMAZ. Veri cekme ve normalizasyon tamamen deterministiktir; ayni
girdi her zaman ayni ciktiyi verir (bkz. services/tariff_pipeline.py
modul docstring'i).
"""
import logging
from dataclasses import dataclass, field
from decimal import Decimal
from typing import Any

import httpx
from tenacity import (
    retry,
    retry_if_exception_type,
    stop_after_attempt,
    wait_exponential,
)

logger = logging.getLogger(__name__)

OCM_BASE_URL = "https://api.openchargemap.io/v3/poi/"

# OCM ConnectionTypeID -> projenin connector_type enum'u.
#
# Kapsam disi kalan ID'ler BILINCLI olarak atlanir (asagidaki
# _map_connector). Taninmayan bir soketi "TYPE2" gibi bir varsayilana
# atamak, uygulamada aracina uymayan istasyonun onerilmesine yol acar.
_CONNECTION_TYPE_MAP: dict[int, str] = {
    1: "TYPE1",           # Type 1 (J1772)
    2: "CHAdeMO",
    25: "TYPE2",          # Type 2 (Socket Only)
    1036: "TYPE2",        # Type 2 (Tethered Connector)
    33: "CCS2",           # CCS (Type 2)
    32: "CCS1",           # CCS (Type 1)
    27: "TESLA_NACS",     # Tesla Supercharger
    8: "TESLA_NACS",      # Tesla (Model S/X)
    30: "TESLA_DESTINATION",
    1039: "GBT_AC",
    1040: "GBT_DC",
}

_CURRENT_TYPE_MAP: dict[str, str] = {
    "AC (Single-Phase)": "AC_SINGLE_PHASE",
    "AC (Three-Phase)": "AC_THREE_PHASE",
    "DC": "DC",
}


@dataclass(frozen=True)
class RawConnector:
    connector_type: str
    power_kw: Decimal
    current_type: str
    evse_id: str | None


@dataclass(frozen=True)
class RawStation:
    """Kaynaktan gelen, henuz veritabanina yazilmamis istasyon."""

    external_ref: str
    name: str
    lat: float
    lon: float
    country_code: str
    operator_name: str
    connectors: list[RawConnector] = field(default_factory=list)

    @property
    def max_power_kw(self) -> Decimal:
        if not self.connectors:
            return Decimal("0")
        return max(c.power_kw for c in self.connectors)

    @property
    def connector_types(self) -> list[str]:
        # Sirali ve tekrarsiz -- ayni istasyonda ayni tipten birden fazla
        # soket olabilir, ama connector_types dizisi TIPLERI tutar.
        seen: list[str] = []
        for c in self.connectors:
            if c.connector_type not in seen:
                seen.append(c.connector_type)
        return seen


class OpenChargeMapAdapter:
    """OCM'den istasyon envanteri ceker."""

    cpo_code = "OCM"
    source = "COMMUNITY_VERIFIED"
    supports_availability = False
    supports_tariffs = False

    def __init__(self, api_key: str, timeout_seconds: float = 20.0) -> None:
        if not api_key:
            raise ValueError("Open Charge Map API anahtari zorunlu.")
        self._api_key = api_key
        self._timeout = timeout_seconds

    @retry(
        stop=stop_after_attempt(3),
        wait=wait_exponential(multiplier=1, min=2, max=10),
        retry=retry_if_exception_type(
            (httpx.ConnectTimeout, httpx.ReadTimeout, httpx.ConnectError)
        ),
        reraise=True,
    )
    async def _get(self, client: httpx.AsyncClient, params: dict[str, Any]) -> list[dict]:
        response = await client.get(OCM_BASE_URL, params=params)
        response.raise_for_status()
        payload = response.json()
        if not isinstance(payload, list):
            raise ValueError(f"OCM beklenmedik yanit tipi: {type(payload).__name__}")
        return payload

    async def fetch_stations(
        self,
        lat: float,
        lon: float,
        radius_km: int = 25,
        country_code: str = "TR",
        max_results: int = 200,
    ) -> list[RawStation]:
        """Belirtilen merkez cevresindeki istasyonlari ceker."""
        params = {
            "key": self._api_key,
            "countrycode": country_code,
            "latitude": lat,
            "longitude": lon,
            "distance": radius_km,
            "distanceunit": "KM",
            "maxresults": max_results,
            # compact=false: OperatorInfo/ConnectionType gibi ic nesneler
            # dolu gelsin; sadece ID'lerle calismak ekstra sozluk gerektirir.
            "compact": "false",
            "verbose": "false",
        }

        async with httpx.AsyncClient(timeout=self._timeout) as client:
            raw_items = await self._get(client, params)

        stations: list[RawStation] = []
        skipped = 0

        for item in raw_items:
            station = self._map_station(item, country_code)
            if station is None:
                skipped += 1
                continue
            stations.append(station)

        logger.info(
            "OCM: %s kayit alindi, %s istasyon eslendi, %s atlandi "
            "(merkez=%s,%s yaricap=%skm)",
            len(raw_items),
            len(stations),
            skipped,
            lat,
            lon,
            radius_km,
        )
        return stations

    def _map_station(self, item: dict, country_code: str) -> RawStation | None:
        address = item.get("AddressInfo") or {}
        lat = address.get("Latitude")
        lon = address.get("Longitude")

        # Koordinati olmayan kayit haritada gosterilemez; ATLANIR.
        if lat is None or lon is None:
            return None

        connectors = self._map_connectors(item.get("Connections") or [])
        # Soketi bilinmeyen istasyon, "aracima uyar mi" sorusuna cevap
        # veremez -- listede gosterip kullaniciyi bosuna yollamaktansa
        # atlamak dogru.
        if not connectors:
            return None

        # Kaynak oneki, external_ref'i GLOBAL olarak benzersiz kilar.
        # Boylece isletmeci adi kaynakta degisse bile istasyon ayni satir
        # olarak kalir (bkz. migration 004).
        raw_ref = item.get("UUID") or item.get("ID")
        if raw_ref is None:
            return None
        external_ref = f"OCM:{raw_ref}"

        operator = (item.get("OperatorInfo") or {}).get("Title") or "Bilinmeyen isletmeci"
        name = address.get("Title") or operator

        return RawStation(
            external_ref=str(external_ref),
            name=str(name).strip(),
            lat=float(lat),
            lon=float(lon),
            country_code=country_code,
            operator_name=str(operator).strip(),
            connectors=connectors,
        )

    def _map_connectors(self, raw_connections: list[dict]) -> list[RawConnector]:
        connectors: list[RawConnector] = []

        for connection in raw_connections:
            type_id = connection.get("ConnectionTypeID")
            connector_type = _CONNECTION_TYPE_MAP.get(type_id) if type_id else None

            # Taninmayan soket tipi ATLANIR, varsayilana atanmaz.
            if connector_type is None:
                continue

            power = connection.get("PowerKW")
            if power is None or float(power) <= 0:
                # Gucu bilinmeyen soket, "hizli sarj mi" sorusuna cevap
                # veremez; kaydedilse de filtrelerde yanlis davranir.
                continue

            current_title = (connection.get("CurrentType") or {}).get("Title") or ""
            current_type = _CURRENT_TYPE_MAP.get(current_title, "UNKNOWN")

            # Ayni tipten birden fazla soket varsa (Quantity), her biri
            # ayri satir olur -- doluluk soket bazinda takip edilecegi icin
            # (bkz. migration 002) tek satirda toplamak bilgi kaybi olurdu.
            quantity = connection.get("Quantity") or 1
            for index in range(int(quantity)):
                connectors.append(
                    RawConnector(
                        connector_type=connector_type,
                        power_kw=Decimal(str(power)),
                        current_type=current_type,
                        evse_id=f"{connection.get('ID')}-{index}" if connection.get("ID") else None,
                    )
                )

        return connectors
