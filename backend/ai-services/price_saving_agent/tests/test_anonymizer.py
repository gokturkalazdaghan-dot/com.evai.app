# backend/ai-services/price_saving_agent/tests/test_anonymizer.py
"""
PII maskeleme.

Bu bilesen gizlilik politikamizin teknik karsiligi: disaridan gelen ham
veri (CPO API yanitlari) loglara ve isleme hattina girmeden once
temizleniyor. Bir desenin sessizce bozulmasi, kisisel verinin loglara
sizmasi demek -- ve bu, fark edilmesi en zor hata turlerinden.

Testler maskelemenin YAPILDIGINI degil, MASKELENEN SEYIN ARTIK ORADA
OLMADIGINI dogruluyor. "Etiket eklendi mi" sormak yetmez; asil soru
"orijinal deger metinde kaldi mi".
"""
import pytest

from services.anonymizer import (
    Anonymizer,
    PiiCategory,
    PiiDetectedError,
)


@pytest.fixture
def anonymizer():
    return Anonymizer()


class TestSanitizeText:
    def test_eposta_maskelenir_ve_orijinali_kalmaz(self, anonymizer):
        result = anonymizer.sanitize_text("Bana ali.veli@example.com adresinden yaz")

        assert "ali.veli@example.com" not in result.sanitized_text
        assert "[REDACTED_EMAIL]" in result.sanitized_text
        assert PiiCategory.EMAIL in result.detected_categories
        assert result.had_pii is True

    def test_ip_adresi_maskelenir(self, anonymizer):
        result = anonymizer.sanitize_text("Istek 192.168.1.42 adresinden geldi")

        assert "192.168.1.42" not in result.sanitized_text
        assert PiiCategory.IP_ADDRESS in result.detected_categories

    def test_temiz_metne_dokunulmaz(self, anonymizer):
        # Yanlis pozitif de gercek bir maliyet: istasyon adini maskelemek
        # kullaniciya "[REDACTED]" gostermek demek.
        original = "Karakoy Hizli Sarj - 150 kW - CCS"
        result = anonymizer.sanitize_text(original)

        assert result.sanitized_text == original
        assert result.had_pii is False
        assert result.redaction_count == 0

    def test_bos_metin_cokmez(self, anonymizer):
        result = anonymizer.sanitize_text("")

        assert result.sanitized_text == ""
        assert result.had_pii is False

    def test_birden_fazla_pii_ayni_metinde(self, anonymizer):
        result = anonymizer.sanitize_text(
            "iletisim: ali@example.com, sunucu: 10.0.0.7"
        )

        assert "ali@example.com" not in result.sanitized_text
        assert "10.0.0.7" not in result.sanitized_text
        assert result.redaction_count >= 2
        assert len(result.detected_categories) >= 2

    def test_ayni_pii_birden_fazla_kez_gecerse_hepsi_maskelenir(self, anonymizer):
        # subn tum eslesmeleri degistirmeli; yalnizca ilkini degistiren bir
        # regresyon, ikinci adresi oldugu gibi birakirdi.
        result = anonymizer.sanitize_text("a@example.com ve b@example.com")

        assert "a@example.com" not in result.sanitized_text
        assert "b@example.com" not in result.sanitized_text
        assert result.redaction_count == 2

    def test_skip_categories_o_kategoriyi_atlar(self, anonymizer):
        text = "sunucu 10.0.0.7"
        result = anonymizer.sanitize_text(
            text, skip_categories={PiiCategory.IP_ADDRESS}
        )

        assert result.sanitized_text == text
        assert PiiCategory.IP_ADDRESS not in result.detected_categories


class TestSanitizeDict:
    def test_ic_ice_sozlukler_de_taranir(self, anonymizer):
        data = {
            "station_id": "TR-IST-001",
            "operator": {"contact": "destek@cpo.example.com"},
        }

        sanitized, result = anonymizer.sanitize_dict(data)

        assert "destek@cpo.example.com" not in str(sanitized)
        assert PiiCategory.EMAIL in result.detected_categories

    def test_listelerdeki_metinler_de_taranir(self, anonymizer):
        data = {"notes": ["normal not", "yaz: kisi@example.com"]}

        sanitized, result = anonymizer.sanitize_dict(data)

        assert "kisi@example.com" not in str(sanitized)
        assert result.redaction_count == 1

    def test_metin_olmayan_degerler_korunur(self, anonymizer):
        # Fiyat ve guc degerleri sayidir; tipleri degisirse boru hatti
        # ilerideki bir adimda patlar.
        data = {"price": 7.8, "power_kw": 150, "active": True, "unknown": None}

        sanitized, _ = anonymizer.sanitize_dict(data)

        assert sanitized == data

    def test_bilinen_guvenli_alanlar_maskelenmez(self, anonymizer):
        # Istasyon koordinati "hassas GPS" desenine uyuyor ama istasyonun
        # konumu kisisel veri DEGIL -- maskelenirse harita bozulur.
        data = {"lat": "41.008238", "lon": "28.978359"}

        sanitized, _ = anonymizer.sanitize_dict(data)

        assert sanitized["lat"] == "41.008238"
        assert sanitized["lon"] == "28.978359"


class TestAssertCleanOrRaise:
    def test_pii_varsa_islemi_durdurur(self, anonymizer):
        with pytest.raises(PiiDetectedError):
            anonymizer.assert_clean_or_raise("kisi@example.com")

    def test_temiz_metinde_sessiz_gecer(self, anonymizer):
        anonymizer.assert_clean_or_raise("Karakoy Hizli Sarj 150 kW")

    def test_hata_hangi_kategorilerin_bulundugunu_tasir(self, anonymizer):
        with pytest.raises(PiiDetectedError) as exc:
            anonymizer.assert_clean_or_raise("kisi@example.com")

        assert PiiCategory.EMAIL in exc.value.categories
