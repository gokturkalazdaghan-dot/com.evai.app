# backend/ai-services/price_saving_agent/tests/test_anonymizer_phone_pattern.py
"""
Telefon deseninin sinirlari.

NEDEN AYRI BIR DOSYA
--------------------
Telefon deseni bu moduldeki en genis desen ve iki yonlu bir riski var:

  - Cok darsa gercek bir telefon numarasi loglara sizar.
  - Cok genisse YAPISAL VERIYI yer.

Ikincisi olculdu: desen "41.008238" (istasyon enlemi), "1250.50" (ucret)
ve "2026-08-22" (ISO tarih) dizgelerini telefon sanip maskeliyordu.
Tarife boru hatti sanitize_dict'ten gecen sozlukten valid_from alanini
okuyup ayristirdigi icin, ISO zaman damgasi kullanan gercek bir CPO'da
tarifeler BOZULARAK islenirdi.

Bu yuzden desen artik iki tarafli sabitleniyor: asagidaki gercek
numaralar YAKALANMAK, yapisal degerler ise KORUNMAK zorunda.
"""
import pytest

from services.anonymizer import Anonymizer, PiiCategory


@pytest.fixture
def anonymizer():
    return Anonymizer()


def _redacted(anonymizer, text: str) -> bool:
    return PiiCategory.PHONE in anonymizer.sanitize_text(text).detected_categories


class TestGercekNumaralarYakalanir:
    @pytest.mark.parametrize(
        "phone",
        [
            "+90 555 123 45 67",
            "+90 555 123 4567",
            "0555 123 45 67",
            "05551234567",
            "(212) 555-1234",
            "212-555-1234",
            "555.123.4567",          # ABD'de yaygin noktali yazim
            "+44 20 7946 0958",
            "+1 415 555 2671",
        ],
    )
    def test_telefon_maskelenir(self, anonymizer, phone):
        assert _redacted(anonymizer, f"teknisyen: {phone}") is True

    def test_serbest_metnin_icinden_yakalanir(self, anonymizer):
        result = anonymizer.sanitize_text(
            "Ariza icin 0555 123 45 67 numarasini arayin"
        )

        assert "0555 123 45 67" not in result.sanitized_text
        assert "[REDACTED_PHONE]" in result.sanitized_text


class TestYapisalVeriKorunur:
    @pytest.mark.parametrize(
        "value",
        [
            "41.008238",             # istasyon enlemi
            "28.978359",             # istasyon boylami
            "-33.868820",            # negatif enlem
            "1250.50",               # ucret
            "7.80",                  # kWh basi fiyat
            "0.4523",                # oran
            "150",                   # guc (kW)
        ],
    )
    def test_sayisal_degerler_telefon_sayilmaz(self, anonymizer, value):
        assert _redacted(anonymizer, value) is False

    @pytest.mark.parametrize(
        "timestamp",
        [
            "2026-08-22T10:00:00Z",
            "2026-08-22",
            "2026-12-31T23:59:59+03:00",
        ],
    )
    def test_iso_zaman_damgalari_telefon_sayilmaz(self, anonymizer, timestamp):
        assert _redacted(anonymizer, timestamp) is False

    def test_zaman_damgasi_oldugu_gibi_kalir(self, anonymizer):
        # Boru hatti bu alani ayristirmaya calisiyor; icine
        # "[REDACTED_PHONE]" girerse tarife islenemez.
        original = "2026-08-22T10:00:00Z"

        assert anonymizer.sanitize_text(original).sanitized_text == original


class TestGercekTarifeYaniti:
    def test_tipik_cpo_yaniti_bozulmadan_gecer(self, anonymizer):
        # Tarife boru hattinin gercekten okudugu alanlar. Bu test
        # kirilirsa fiyat toplama sessizce bozulur.
        raw = {
            "station_external_ref": "TR-IST-00042",
            "price_per_kwh": "7.80",
            "session_fee": "1250.50",
            "currency": "TRY",
            "valid_from": "2026-08-22T10:00:00Z",
            "valid_until": "2026-08-23T10:00:00Z",
            "lat": "41.008238",
            "lon": "28.978359",
        }

        sanitized, result = anonymizer.sanitize_dict(raw)

        assert sanitized == raw
        assert result.had_pii is False

    def test_serbest_metindeki_pii_YINE_de_yakalanir(self, anonymizer):
        # Yapisal alanlari korumak, gercek PII'yi kacirmak anlamina
        # gelmemeli.
        raw = {
            "price_per_kwh": "7.80",
            "notes": "sorun olursa 0555 123 45 67 numarasini arayin",
        }

        sanitized, result = anonymizer.sanitize_dict(raw)

        assert sanitized["price_per_kwh"] == "7.80"
        assert "0555 123 45 67" not in sanitized["notes"]
        assert result.had_pii is True
