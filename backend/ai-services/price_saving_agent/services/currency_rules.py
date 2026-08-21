# backend/ai-services/price_saving_agent/services/currency_rules.py
"""
Ulke -> para birimi eslemesi ve dogrulama.

NEDEN VAR
---------
Olculdu: mock toplayici, istasyonun ulkesinden BAGIMSIZ olarak her
tarifeye "TRY" yaziyordu. Sonuc: San Francisco'daki istasyonlar
"8,28 TRY/kWh" olarak kaydedildi ve uygulamada oyle gorundu.

Bu hatanin tehlikesi sessiz olmasi: 8,28 rakami makul gorunur, kullanici
para birimini fark etmeden fiyat karsilastirmasi yapar ve yanlis
istasyona gider. Yanlis para birimi, yanlis fiyattan daha kotudur --
cunku fark edilmez.

Gercek bir CPO da yanlis veri gonderebilir. Bu yuzden dogrulama mock'ta
degil BORU HATTINDA duruyor: kaynak ne olursa olsun, istasyonun ulkesiyle
uyusmayan para birimi REDDEDILIR.
"""
import logging

logger = logging.getLogger(__name__)

# ISO 3166-1 alpha-2 -> ISO 4217.
#
# Kapsam bilincli olarak dar: yalnizca yayina cikilacak pazarlar. Bilinmeyen
# bir ulke icin TAHMIN YAPILMAZ (bkz. expected_currency_for).
_COUNTRY_CURRENCY: dict[str, str] = {
    # Avro bolgesi
    "DE": "EUR", "FR": "EUR", "NL": "EUR", "BE": "EUR", "ES": "EUR",
    "IT": "EUR", "AT": "EUR", "PT": "EUR", "IE": "EUR", "FI": "EUR",
    "GR": "EUR", "SK": "EUR", "SI": "EUR", "EE": "EUR", "LV": "EUR",
    "LT": "EUR", "LU": "EUR", "MT": "EUR", "CY": "EUR", "HR": "EUR",
    # Diger
    "GB": "GBP",
    "US": "USD",
    "TR": "TRY",
    "CH": "CHF",
}


def expected_currency_for(country_code: str | None) -> str | None:
    """
    Ulkenin para birimi. Bilinmiyorsa None.

    None donmesi "dogrulama yapilamaz" demektir, "gecersiz" degil --
    bilmedigimiz bir ulkede tarifeyi reddetmek, o pazarda uygulamayi
    fiyatsiz birakirdi.
    """
    if not country_code:
        return None
    return _COUNTRY_CURRENCY.get(country_code.strip().upper())


def is_currency_plausible(currency: str, country_code: str | None) -> bool:
    """
    Bu para birimi bu ulkede makul mu?

    Ulke bilinmiyorsa True doner: dogrulayamadigimiz bir seyi reddetmek,
    yeni bir pazara acilirken tum fiyatlari sessizce dusururdu.
    """
    expected = expected_currency_for(country_code)
    if expected is None:
        return True
    return currency.strip().upper() == expected


class CurrencyMismatchError(ValueError):
    """Tarifenin para birimi istasyonun ulkesiyle uyusmuyor."""

    def __init__(self, currency: str, country_code: str, expected: str) -> None:
        super().__init__(
            f"{country_code} ulkesindeki bir istasyona {currency} tarifesi geldi; "
            f"beklenen {expected}."
        )
        self.currency = currency
        self.country_code = country_code
        self.expected = expected
