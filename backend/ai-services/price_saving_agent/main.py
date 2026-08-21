# backend/ai-services/price_saving_agent/main.py
import base64
import logging
from contextlib import asynccontextmanager

from pydantic import BaseModel
from fastapi import Depends, FastAPI, HTTPException, status, File, UploadFile
from fastapi.responses import JSONResponse

from config import settings
from models.agent_schemas import AgentRunRequest, AgentRunResult, AgentHealthDetail
from models.voice_schemas import VoiceQueryRequest, VoiceQueryResponse
from services.db_service import db_service
from services.redis_publisher import redis_publisher
from services.internal_auth import require_internal_service_key
from orchestrator import orchestrator
from services.openchargemap_adapter import OpenChargeMapAdapter
from services.station_ingest import ingest_stations
from services.transcription_service import TranscriptionError, transcribe
from agents.voice_copilot_agent import VoiceCopilotAgent

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s | %(levelname)s | %(name)s | %(message)s",
)
logger = logging.getLogger("price_saving_agent")


@asynccontextmanager
async def lifespan(app: FastAPI):
    logger.info("Fiyat Tasarruf Ajanı servisi başlatılıyor...")
    await db_service.connect()
    await redis_publisher.connect()
    logger.info("Bağlantılar hazır. Servis çalışıyor.")
    yield
    logger.info("Servis kapatılıyor, bağlantılar temizleniyor...")
    await db_service.close()
    await redis_publisher.close()


class TranscribeBase64Request(BaseModel):
    audio_base64: str
    language: str = "tr"


app = FastAPI(
    title="Eva — Price Saving Agent",
    description="Çok-CPO'lu anlık tarife toplama ve doğrulama servisi (deterministik hat).",
    version="1.0.0",
    lifespan=lifespan,
)


@app.get("/health")
async def health_check() -> dict[str, str]:
    return {"status": "ok", "service": "price_saving_agent"}


@app.get("/v1/agent/health-detail", response_model=AgentHealthDetail)
async def health_detail() -> AgentHealthDetail:
    redis_ok = False
    try:
        if redis_publisher._async_client is not None:
            await redis_publisher._async_client.ping()
            redis_ok = True
    except Exception as exc:
        logger.warning("Health-detail: Redis ping başarısız.", exc_info=exc)
        redis_ok = False

    pool_size = None
    if db_service._pool is not None:
        pool_size = db_service._pool.get_size()

    return AgentHealthDetail(
        status="ok" if redis_ok and pool_size is not None else "degraded",
        active_db_pool_size=pool_size,
        redis_connected=redis_ok,
    )


@app.post("/v1/agent/run", response_model=AgentRunResult, status_code=status.HTTP_200_OK)
async def run_agent_for_region(
    request: AgentRunRequest,
    _: None = Depends(require_internal_service_key),
) -> AgentRunResult:
    try:
        return await orchestrator.run_for_region(request.geohash5)
    except Exception as exc:
        logger.error(
            "Ajan koşusu başarısız: geohash5=%s", request.geohash5, exc_info=exc
        )
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail="Fiyat toplama ajanı çalıştırılamadı.",
        ) from exc


@app.post("/v1/agent/run-batch", response_model=list[AgentRunResult])
async def run_agent_for_all_active_regions(
    max_regions: int = 20,
    _: None = Depends(require_internal_service_key),
) -> list[AgentRunResult]:
    try:
        return await orchestrator.run_for_all_active_regions(max_regions=max_regions)
    except Exception as exc:
        logger.error("Toplu ajan koşusu başarısız.", exc_info=exc)
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail="Toplu fiyat toplama koşusu başarısız oldu.",
        ) from exc


@app.post("/v1/stations/ingest")
async def ingest_stations_from_source(
    lat: float,
    lon: float,
    radius_km: int = 25,
    country_code: str = "TR",
    _: None = Depends(require_internal_service_key),
) -> dict:
    """
    Open Charge Map'ten istasyon envanterini ceker ve veritabanina yazar.

    NOT: Bu uc nokta ANLIK DOLULUK getirmez -- OCM'de o veri yoktur
    (bkz. services/openchargemap_adapter.py). Yalnizca envanter: konum,
    soket tipleri, guc, AC/DC.
    """
    if not settings.openchargemap_api_key:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="OPENCHARGEMAP_API_KEY tanimli degil.",
        )

    try:
        adapter = OpenChargeMapAdapter(settings.openchargemap_api_key)
        stations = await adapter.fetch_stations(
            lat=lat,
            lon=lon,
            radius_km=radius_km,
            country_code=country_code,
            max_results=settings.openchargemap_max_results,
        )
        result = await ingest_stations(
            stations,
            cpo_code=adapter.cpo_code,
            source=adapter.source,
        )
        return {"fetched": len(stations), **result.as_dict()}
    except Exception as exc:
        logger.error("Istasyon ingest basarisiz.", exc_info=exc)
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail="Istasyon envanteri alinamadi.",
        ) from exc


@app.post("/v1/voice/transcribe")
async def transcribe_audio(
    audio: UploadFile = File(...),
    language: str = "tr",
    _: None = Depends(require_internal_service_key),
) -> dict:
    """
    Ses -> metin. Uygulama mikrofon kaydini buraya yukler.

    Groq anahtari SUNUCUDA kalir; uygulamaya gomulmez (APK'dan string
    cikarmak onemsizdir). Bkz. services/transcription_service.py.
    """
    try:
        audio_bytes = await audio.read()
        text = await transcribe(
            audio_bytes,
            filename=audio.filename or "speech.wav",
            language=language,
        )
        # Bos transkript UYDURULMAZ; istemci bunu "anlasilmadi" olarak
        # ele alir.
        return {"text": text, "recognized": bool(text)}
    except TranscriptionError as exc:
        logger.warning("Transkripsiyon reddedildi: %s", exc)
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=str(exc),
        ) from exc
    except Exception as exc:
        logger.error("Transkripsiyon beklenmeyen hata.", exc_info=exc)
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail="Ses islenemedi.",
        ) from exc


@app.post("/v1/voice/transcribe-base64")
async def transcribe_audio_base64(
    request: TranscribeBase64Request,
    _: None = Depends(require_internal_service_key),
) -> dict:
    """
    Gateway'den gelen base64 sesi metne cevirir.

    Multipart yerine base64: Gateway'deki RequestSignatureGuard govdeyi
    JSON.stringify uzerinden hash'liyor, multipart bu sozlesmeyi kirardi
    (bkz. voice-transcribe.dto.ts).
    """
    try:
        audio_bytes = base64.b64decode(request.audio_base64, validate=True)
    except Exception as exc:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail="Gecersiz base64 ses verisi.",
        ) from exc

    try:
        text = await transcribe(audio_bytes, language=request.language)
        return {"text": text, "recognized": bool(text)}
    except TranscriptionError as exc:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail=str(exc)
        ) from exc


@app.post("/v1/voice/interpret", response_model=VoiceQueryResponse)
async def interpret_voice_query(
    request: VoiceQueryRequest,
    _: None = Depends(require_internal_service_key),
) -> VoiceQueryResponse:
    """
    Yalnızca NestJS Gateway'den (X-Internal-Service-Key ile doğrulanmış)
    çağrılır. Bu endpoint'e doğrudan dışarıdan erişim reddedilir.
    """
    agent = VoiceCopilotAgent(db_service=db_service)
    return await agent.answer(request)


@app.exception_handler(Exception)
async def unhandled_exception_handler(request, exc: Exception) -> JSONResponse:
    logger.error("Yakalanmamış hata: %s", request.url, exc_info=exc)
    return JSONResponse(
        status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
        content={"detail": "Beklenmeyen bir sunucu hatası oluştu."},
    )
