# backend/ai-services/price_saving_agent/tests/test_internal_auth.py
"""
Dahili servis anahtari.

Bu servis dis dunyaya kapali olmali: yalnizca NestJS Gateway cagirabilir.
Koruma, iki tarafin ayni gizli anahtardan BAGIMSIZ olarak turettigi ve
gunde bir donen bir HMAC degerine dayaniyor.

Testlerin korudugu iki sey:
  1. Gecersiz/eksik anahtarla gelen istek 401 alir. Bu kirilirsa servis,
     yanlislikla internete acilirsa herkese acik olur.
  2. Gun sinirinda gecis calisir. Grace period kirilirsa servis her gece
     yarisi kisa sureligine kendi Gateway'ini reddeder -- uretimde
     bulunmasi zor, gece yarisi patlayan bir hata.
"""
import pytest
from fastapi import HTTPException

from services import internal_auth


def valid_key_now() -> str:
    return internal_auth._derive_key_for_window(internal_auth._current_window_index())


class TestKeyDerivation:
    def test_ayni_pencere_ayni_anahtari_uretir(self):
        # Deterministik olmasi sart: iki taraf ag uzerinden haberlesmeden
        # ayni degere ulasmak zorunda.
        index = internal_auth._current_window_index()

        assert internal_auth._derive_key_for_window(index) == (
            internal_auth._derive_key_for_window(index)
        )

    def test_farkli_pencereler_farkli_anahtar_uretir(self):
        index = internal_auth._current_window_index()

        assert internal_auth._derive_key_for_window(index) != (
            internal_auth._derive_key_for_window(index + 1)
        )

    def test_anahtar_sha256_hex_bicimindedir(self):
        key = valid_key_now()

        assert len(key) == 64
        assert all(c in "0123456789abcdef" for c in key)

    def test_pencere_24_saattir(self):
        # Rotasyon periyodu Gateway tarafiyla (internal-key.util.ts)
        # BIREBIR ayni olmali; degisirse iki taraf anlasamaz.
        assert internal_auth._WINDOW_SECONDS == 86_400


class TestKeyValidation:
    def test_gecerli_anahtar_kabul_edilir(self):
        assert internal_auth._is_valid_internal_key(valid_key_now()) is True

    def test_bir_onceki_pencere_de_kabul_edilir(self):
        # Grace period: gun sinirinda Gateway henuz eski anahtari
        # kullaniyor olabilir.
        previous = internal_auth._derive_key_for_window(
            internal_auth._current_window_index() - 1
        )

        assert internal_auth._is_valid_internal_key(previous) is True

    def test_gelecek_pencere_KABUL_EDILMEZ(self):
        # Ileri yonde tolerans yok: olsaydi, saati ileri alinmis bir
        # istemci gecerli anahtar uretebilirdi.
        future = internal_auth._derive_key_for_window(
            internal_auth._current_window_index() + 1
        )

        assert internal_auth._is_valid_internal_key(future) is False

    def test_iki_pencere_oncesi_reddedilir(self):
        old = internal_auth._derive_key_for_window(
            internal_auth._current_window_index() - 2
        )

        assert internal_auth._is_valid_internal_key(old) is False

    @pytest.mark.parametrize("bogus", ["", "deadbeef", "0" * 64, "gecersiz-anahtar"])
    def test_uydurma_anahtarlar_reddedilir(self, bogus):
        assert internal_auth._is_valid_internal_key(bogus) is False


class TestRequireInternalServiceKey:
    @pytest.mark.asyncio
    async def test_gecerli_anahtarla_gecer(self):
        await internal_auth.require_internal_service_key(valid_key_now())

    @pytest.mark.asyncio
    async def test_gecersiz_anahtarda_401_atar(self):
        with pytest.raises(HTTPException) as exc:
            await internal_auth.require_internal_service_key("yanlis")

        assert exc.value.status_code == 401

    @pytest.mark.asyncio
    async def test_401_gizli_bilgi_sizdirmaz(self):
        # Hata mesaji beklenen anahtari ya da gizli degeri ima etmemeli.
        with pytest.raises(HTTPException) as exc:
            await internal_auth.require_internal_service_key("yanlis")

        detail = str(exc.value.detail)
        assert valid_key_now() not in detail
        assert "secret" not in detail.lower()
