# backend/ai-services/price_saving_agent/services/anonymizer.py
"""
Sıfır-PII mimarisinin, Fiyat Tasarruf Ajanı'nın CrewAI/LLM sınırını geçen
her veri parçası için uyguladığı son savunma katmanı.

Bu servis, ajanların (fetcher/validator) işlediği veriyi -- LLM'e prompt
olarak gönderilmeden ve loglara yazılmadan HEMEN ÖNCE -- tarar ve olası
kişisel veri sızıntılarını (e-posta, telefon, IBAN, ham GPS koordinatı,
IP adresi gibi) tespit edip ya maskeler ya da tamamen reddeder.

Bu, "veritabanında PII tutulmuyor" garantisinden farklı bir katmandır:
CPO Aggregator API'lerinden dönen ham yanıtlarda beklenmedik şekilde PII
sızmışsa (örn. bir istasyon 'notes' alanında bir teknisyenin telefon
numarasını içeriyorsa), bu sızıntının LLM'e ya da log dosyalarına
taşınmasını burada durdurmak hedeflenir.
"""
from __future__ import annotations

import logging
import re
from dataclasses import dataclass, field
from enum import Enum

logger = logging.getLogger(__name__)


class PiiCategory(str, Enum):
    EMAIL = "email"
    PHONE = "phone"
    IBAN = "iban"
    CREDIT_CARD = "credit_card"
    RAW_GPS_HIGH_PRECISION = "raw_gps_high_precision"
    IP_ADDRESS = "ip_address"
    NATIONAL_ID = "national_id"


@dataclass
class AnonymizationResult:
    sanitized_text: str
    detected_categories: list[PiiCategory] = field(default_factory=list)
    redaction_count: int = 0

    @property
    def had_pii(self) -> bool:
        return len(self.detected_categories) > 0


# Desenler bilinçli olarak muhafazakar (false-positive'e eğilimli) tutuldu —
# bir istasyon adının yanlışlıkla maskelenmesi kabul edilebilir bir maliyet,
# ama gerçek bir e-posta/telefon numarasının sızması kabul edilemez.
_PATTERNS: dict[PiiCategory, re.Pattern[str]] = {
    PiiCategory.EMAIL: re.compile(
        r"[a-zA-Z0-9_.+-]+@[a-zA-Z0-9-]+\.[a-zA-Z0-9-.]+"
    ),
    # Telefon deseni bu dosyadaki EN GENIS desen; iki tarafli sinirlandi.
    #
    # Onceki hali herhangi bir "6+ rakam + ayirac" dizisini telefon
    # sayiyordu ve YAPISAL VERIYI yiyordu: istasyon enlemi (41.008238),
    # ucret (1250.50) ve ISO zaman damgasi (2026-08-22T10:00:00Z) hepsi
    # [REDACTED_PHONE] oluyordu. Tarife boru hatti sanitize_dict'ten
    # gecen sozlukten valid_from alanini okuyup ayristirdigi icin, ISO
    # zaman damgasi donen gercek bir CPO'da tarifeler bozulurdu.
    #
    # Uc koruma eklendi:
    #   1. (?<![\d.])  ve  (?![\d.])  -> eslesme bir sayinin ORTASINDAN
    #      baslayamaz/bitemez.
    #   2. ISO tarih on-kontrolu -> 2026-08-22 telefon degildir.
    #   3. Duz ondalik sayi on-kontrolu -> "41.008238" bir sayidir.
    #      Ayirt edici olcut: duz ondalikta TEK nokta vardir; noktali
    #      yazilan bir telefonda (555.123.4567) EN AZ IKI nokta bulunur,
    #      bu yuzden o hala yakalanir.
    PiiCategory.PHONE: re.compile(
        r"(?<![\d.])"
        r"(?!\d{4}-\d{2}-\d{2})"
        r"(?!-?\d+\.\d+(?![\d.]))"
        r"(?:\+?\d{1,3}[\s.-]?)?(?:\(?\d{2,4}\)?[\s.-]?){2,4}\d{2,4}"
        r"(?![\d.])"
    ),
    PiiCategory.IBAN: re.compile(
        r"\b[A-Z]{2}\d{2}[A-Z0-9]{10,30}\b"
    ),
    PiiCategory.CREDIT_CARD: re.compile(
        r"\b(?:\d[ -]*?){13,19}\b"
    ),
    # 6+ ondalık basamaklı enlem/boylam çiftleri — istasyonun kendi genel
    # konumu (4-5 basamak yeterli) değil, olası bir kullanıcı cihazının
    # anlık, yüksek hassasiyetli konumu olabilir.
    PiiCategory.RAW_GPS_HIGH_PRECISION: re.compile(
        r"-?\d{1,3}\.\d{6,}"
    ),
    PiiCategory.IP_ADDRESS: re.compile(
        r"\b(?:\d{1,3}\.){3}\d{1,3}\b"
    ),
    # TC Kimlik No formatı (11 haneli, 0 ile başlamayan) — Türkiye pazarı
    # için ek bir koruma katmanı.
    PiiCategory.NATIONAL_ID: re.compile(
        r"\b[1-9]\d{10}\b"
    ),
}

_REDACTION_LABEL = {
    PiiCategory.EMAIL: "[REDACTED_EMAIL]",
    PiiCategory.PHONE: "[REDACTED_PHONE]",
    PiiCategory.IBAN: "[REDACTED_IBAN]",
    PiiCategory.CREDIT_CARD: "[REDACTED_CARD]",
    PiiCategory.RAW_GPS_HIGH_PRECISION: "[REDACTED_PRECISE_COORDINATE]",
    PiiCategory.IP_ADDRESS: "[REDACTED_IP]",
    PiiCategory.NATIONAL_ID: "[REDACTED_ID]",
}

# Bu alanlar istasyon/tarife verisinin normal, beklenen parçalarıdır ve
# yanlışlıkla PII deseniyle eşleşse bile (örn. "42.123456, 29.987654" gibi
# bir istasyon koordinatı) maskelenmemelidir. Anonymizer bu alan adlarını
# GÖRMEZ -- yalnızca çağıran taraf hangi alanların "bilinen-güvenli" olduğunu
# `skip_categories` ile belirtebilir.
KNOWN_SAFE_FIELD_CATEGORIES: dict[str, set[PiiCategory]] = {
    "lat": {PiiCategory.RAW_GPS_HIGH_PRECISION},
    "lon": {PiiCategory.RAW_GPS_HIGH_PRECISION},
    "station_id": set(),
    "cpo_code": set(),
}


class Anonymizer:
    """CrewAI ajanlarına giden/gelen serbest metni tarayan, PII tespit eden
    ve maskeleyen servis. Durumsuzdur (stateless) — her çağrı bağımsızdır."""

    def sanitize_text(
        self,
        text: str,
        skip_categories: set[PiiCategory] | None = None,
    ) -> AnonymizationResult:
        if not text:
            return AnonymizationResult(sanitized_text=text)

        skip = skip_categories or set()
        sanitized = text
        detected: list[PiiCategory] = []
        total_redactions = 0

        for category, pattern in _PATTERNS.items():
            if category in skip:
                continue

            matches = pattern.findall(sanitized)
            if not matches:
                continue

            sanitized, count = pattern.subn(_REDACTION_LABEL[category], sanitized)
            if count > 0:
                detected.append(category)
                total_redactions += count

        if detected:
            logger.warning(
                "Anonymizer PII tespit etti ve maskeledi: categories=%s, count=%d",
                [c.value for c in detected],
                total_redactions,
            )

        return AnonymizationResult(
            sanitized_text=sanitized,
            detected_categories=detected,
            redaction_count=total_redactions,
        )

    def sanitize_dict(
        self,
        data: dict,
        field_skip_map: dict[str, set[PiiCategory]] | None = None,
    ) -> tuple[dict, AnonymizationResult]:
        """Sözlük yapısındaki (örn. CPO API'den dönen ham JSON) her string
        değeri tarar. field_skip_map, alan adı bazında hangi kategorilerin
        atlanacağını belirtir (örn. {'lat': {RAW_GPS_HIGH_PRECISION}})."""
        skip_map = field_skip_map or KNOWN_SAFE_FIELD_CATEGORIES

        sanitized_data: dict = {}
        all_detected: list[PiiCategory] = []
        total_redactions = 0

        for key, value in data.items():
            skip_categories = skip_map.get(key, set())

            if isinstance(value, str):
                result = self.sanitize_text(value, skip_categories=skip_categories)
                sanitized_data[key] = result.sanitized_text
                all_detected.extend(result.detected_categories)
                total_redactions += result.redaction_count
            elif isinstance(value, dict):
                nested_sanitized, nested_result = self.sanitize_dict(value, skip_map)
                sanitized_data[key] = nested_sanitized
                all_detected.extend(nested_result.detected_categories)
                total_redactions += nested_result.redaction_count
            elif isinstance(value, list):
                sanitized_list = []
                for item in value:
                    if isinstance(item, str):
                        result = self.sanitize_text(item)
                        sanitized_list.append(result.sanitized_text)
                        all_detected.extend(result.detected_categories)
                        total_redactions += result.redaction_count
                    else:
                        sanitized_list.append(item)
                sanitized_data[key] = sanitized_list
            else:
                sanitized_data[key] = value

        return sanitized_data, AnonymizationResult(
            sanitized_text="",
            detected_categories=list(set(all_detected)),
            redaction_count=total_redactions,
        )

    def assert_clean_or_raise(self, text: str) -> None:
        """LLM'e gönderilecek son prompt üzerinde çalıştırılır. PII tespit
        edilirse, maskelemek yerine tüm işlemi durdurur -- bu, "riskli veriyi
        LLM'e gönderme, önce insan gözden geçirsin" ilkesini uygular."""
        result = self.sanitize_text(text)
        if result.had_pii:
            raise PiiDetectedError(result.detected_categories)


class PiiDetectedError(Exception):
    def __init__(self, categories: list[PiiCategory]) -> None:
        self.categories = categories
        super().__init__(
            f"Metinde olası kişisel veri tespit edildi ve işlem durduruldu: "
            f"{[c.value for c in categories]}"
        )


anonymizer = Anonymizer()
