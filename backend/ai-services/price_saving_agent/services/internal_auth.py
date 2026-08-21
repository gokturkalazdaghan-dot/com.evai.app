# backend/ai-services/price_saving_agent/services/internal_auth.py
"""
NestJS Gateway <-> Python AI servisi arasındaki dahili çağrılar için,
Gateway tarafındaki internal-key.util.ts ile BİREBİR AYNI deterministik
anahtar türetme algoritmasını uygular. İki taraf da aynı
INTERNAL_SERVICE_MASTER_SECRET'ı bildiği için, anahtar hiçbir ağ
çağrısıyla senkronize edilmeden her iki tarafta da bağımsız olarak
hesaplanır ve otomatik olarak günde bir kez değişir (rotasyon).

Bu, FastAPI dependency injection sistemine bir "Depends" olarak
bağlanır — her endpoint'in başında tek satırla kullanılabilir.
"""
import hashlib
import hmac
import time

from fastapi import Header, HTTPException, status

from config import settings

_WINDOW_SECONDS = 86400  # 24 saat


def _derive_key_for_window(window_index: int) -> str:
    message = f"eva-internal-service:{window_index}".encode("utf-8")
    return hmac.new(
        settings.internal_service_master_secret.encode("utf-8"),
        message,
        hashlib.sha256,
    ).hexdigest()


def _current_window_index() -> int:
    return int(time.time() // _WINDOW_SECONDS)


def _is_valid_internal_key(provided_key: str) -> bool:
    current = _current_window_index()
    valid_keys = {
        _derive_key_for_window(current),
        _derive_key_for_window(current - 1),  # pencere sınırı için grace period
    }
    return provided_key in valid_keys


async def require_internal_service_key(
    x_internal_service_key: str = Header(..., alias="X-Internal-Service-Key"),
) -> None:
    """
    FastAPI Depends() ile her endpoint'e eklenir. Bu servis SADECE
    Gateway'den gelen isteklere yanıt vermeli — dışarıdan (örn. servis
    yanlışlıkla internet'e açık bir portta çalışırsa) gelen hiçbir istek
    bu kontrolü geçemez.
    """
    if not _is_valid_internal_key(x_internal_service_key):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Geçersiz dahili servis anahtarı.",
        )
