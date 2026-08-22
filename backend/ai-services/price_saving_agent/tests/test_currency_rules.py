# backend/ai-services/price_saving_agent/tests/test_currency_rules.py
"""
Para birimi dogrulamasi.

Bu kural GERCEK bir hatadan dogdu: toplayici, istasyonun ulkesinden
bagimsiz olarak her tarifeye "TRY" yaziyordu ve San Francisco'daki
istasyonlar uygulamada "8,28 TRY/kWh" olarak gorunuyordu.

Hatanin tehlikesi sessiz olmasiydi -- 8,28 makul bir rakam, kullanici
para birimini fark etmeden karsilastirma yapar. Bu yuzden testler
yalnizca "calisiyor mu"yu degil, kuralin IKI YONUNU de koruyor:
reddedilmesi gerekeni reddediyor, kabul edilmesi gerekeni de kabul
ediyor.
"""
import pytest

from services.currency_rules import (
    CurrencyMismatchError,
    expected_currency_for,
    is_currency_plausible,
)


class TestExpectedCurrencyFor:
    @pytest.mark.parametrize(
        "country,currency",
        [
            ("TR", "TRY"),
            ("US", "USD"),
            ("GB", "GBP"),
            ("CH", "CHF"),
            ("DE", "EUR"),
            ("FR", "EUR"),
            ("NL", "EUR"),
        ],
    )
    def test_bilinen_ulkeler_dogru_para_birimini_verir(self, country, currency):
        assert expected_currency_for(country) == currency

    @pytest.mark.parametrize("raw", ["tr", " TR ", "Tr", "\ttr\n"])
    def test_buyuk_kucuk_harf_ve_bosluk_onemsizdir(self, raw):
        # CPO API'lerinden gelen ulke kodlari tutarsiz bicimlerde geliyor;
        # bicim yuzunden dogrulamayi atlamak, hic dogrulamamak demektir.
        assert expected_currency_for(raw) == "TRY"

    @pytest.mark.parametrize("unknown", ["JP", "BR", "ZZ", "XX"])
    def test_bilinmeyen_ulke_None_doner(self, unknown):
        # None = "dogrulanamaz", "gecersiz" DEGIL.
        assert expected_currency_for(unknown) is None

    @pytest.mark.parametrize("empty", [None, "", "   "])
    def test_bos_ulke_kodu_None_doner(self, empty):
        assert expected_currency_for(empty) is None


class TestIsCurrencyPlausible:
    def test_asil_hatayi_yakalar_amerikan_istasyonuna_TRY(self):
        # Bu senaryo uretimde YASANDI. Bir daha gecmemeli.
        assert is_currency_plausible("TRY", "US") is False

    @pytest.mark.parametrize(
        "currency,country",
        [
            ("TRY", "TR"),
            ("USD", "US"),
            ("GBP", "GB"),
            ("EUR", "DE"),
            ("EUR", "NL"),
            ("CHF", "CH"),
        ],
    )
    def test_dogru_eslesmeler_kabul_edilir(self, currency, country):
        assert is_currency_plausible(currency, country) is True

    @pytest.mark.parametrize(
        "currency,country",
        [
            ("EUR", "GB"),   # Brexit sonrasi klasik hata
            ("USD", "TR"),
            ("GBP", "US"),
            ("TRY", "DE"),
        ],
    )
    def test_yanlis_eslesmeler_reddedilir(self, currency, country):
        assert is_currency_plausible(currency, country) is False

    @pytest.mark.parametrize("raw", ["try", " TRY ", "Try"])
    def test_para_birimi_bicimi_onemsizdir(self, raw):
        assert is_currency_plausible(raw, "TR") is True

    @pytest.mark.parametrize("unknown_country", [None, "", "JP", "BR"])
    def test_bilinmeyen_ulkede_HER_para_birimi_kabul_edilir(self, unknown_country):
        # Dogrulayamadigimiz bir seyi reddetmek, yeni bir pazara acilirken
        # TUM fiyatlari sessizce dusururdu. Kasitli bir taviz.
        assert is_currency_plausible("JPY", unknown_country) is True
        assert is_currency_plausible("TRY", unknown_country) is True


class TestCurrencyMismatchError:
    def test_mesaj_uc_bilgiyi_de_icerir(self):
        # Log'a dusen mesaj tek basina teshis edilebilir olmali; hangi
        # ulke, ne geldi, ne bekleniyordu.
        err = CurrencyMismatchError(currency="TRY", country_code="US", expected="USD")

        text = str(err)
        assert "TRY" in text
        assert "US" in text
        assert "USD" in text

    def test_alanlar_programatik_olarak_okunabilir(self):
        err = CurrencyMismatchError(currency="EUR", country_code="GB", expected="GBP")

        assert err.currency == "EUR"
        assert err.country_code == "GB"
        assert err.expected == "GBP"

    def test_ValueError_alt_sinifidir(self):
        # Boru hatti ValueError yakaliyor; hiyerarsi bozulursa hata
        # sessizce yukari kacar.
        assert issubclass(CurrencyMismatchError, ValueError)
