# backend/ai-services/price_saving_agent/services/conversation_memory.py
"""
Sesli asistanin konusma hafizasi.

NEDEN GEREKLI
-------------
Hafiza olmadan her soru BAGIMSIZ bir istekti. Sonuc: kullanici ne sorarsa
sorsun asistan ayni kaliplari tekrarliyordu ("... en ucuz, ... uzaklikta")
ve "peki ya digeri?" gibi bir devam sorusunu anlayamiyordu -- cunku
"digeri"nin neye gonderme yaptigini bilmiyordu.

TASARIM
-------
- Cihaz basina son N tur Redis'te tutulur.
- TTL var: bir yolculuk bittikten sonraki konusma, gecen haftanin
  baglamiyla kirlenmez.
- Yalnizca metin saklanir; konum/batarya gibi anlik veriler her istekte
  taze gelir ve hafizaya YAZILMAZ (eskimis konum, yanlis cevap uretir).
"""
import json
import logging

from services.redis_publisher import redis_publisher

logger = logging.getLogger(__name__)

# Kac tur (kullanici + asistan cifti) hatirlansin.
MAX_TURNS = 6

# Konusma bu sure sessiz kalirsa unutulur. Bir yolculuk olcegi.
SESSION_TTL_SECONDS = 30 * 60


def _key(session_id: str) -> str:
    return f"voice:history:{session_id}"


async def load_history(session_id: str) -> list[dict[str, str]]:
    """Son turlari [{'role': ..., 'content': ...}] olarak doner."""
    if not session_id:
        return []

    client = redis_publisher._async_client
    if client is None:
        return []

    try:
        raw = await client.get(_key(session_id))
        if not raw:
            return []
        history = json.loads(raw)
        return history if isinstance(history, list) else []
    except Exception as exc:
        # Hafiza kaybi konusmayi bozmaz, yalnizca baglamsizlastirir --
        # bu yuzden hata yutulur, istek devam eder.
        logger.warning("Konusma hafizasi okunamadi: %s", exc)
        return []


async def append_turn(session_id: str, user_text: str, assistant_text: str) -> None:
    """Bir turu hafizaya ekler ve pencereyi kirpar."""
    if not session_id:
        return

    client = redis_publisher._async_client
    if client is None:
        return

    try:
        history = await load_history(session_id)
        history.append({"role": "user", "content": user_text})
        history.append({"role": "assistant", "content": assistant_text})

        # Pencereyi kirp: her tur iki mesaj.
        max_messages = MAX_TURNS * 2
        if len(history) > max_messages:
            history = history[-max_messages:]

        await client.set(
            _key(session_id),
            json.dumps(history, ensure_ascii=False),
            ex=SESSION_TTL_SECONDS,
        )
    except Exception as exc:
        logger.warning("Konusma hafizasi yazilamadi: %s", exc)


async def clear(session_id: str) -> None:
    """Kullanici 'bastan basla' derse cagrilir."""
    client = redis_publisher._async_client
    if client is None or not session_id:
        return
    try:
        await client.delete(_key(session_id))
    except Exception as exc:
        logger.warning("Konusma hafizasi silinemedi: %s", exc)
