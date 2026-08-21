# backend/ai-services/price_saving_agent/orchestrator.py
"""
Fiyat Tasarruf Ajanı'nın çalışma orkestrasyon katmanı. FastAPI endpoint'leri
(main.py) bu sınıfın metodlarını çağırır; hat çalıştırma, sonuç toplama ve
hata sınıflandırma mantığı burada toplanır — main.py yalnızca HTTP
sözleşmesiyle ilgilenir, iş mantığıyla ilgilenmez.

NOT: Bu katman eskiden CrewAI tabanlı bir çok-ajanlı ReAct koşusu
tetikliyor ve sonucu, ajanın ürettiği SERBEST METİN içinde "BAŞARILI:"
gibi ifadeleri SAYARAK yorumluyordu. Bu iki yönden kırılgandı: modelin
ifadeyi farklı yazması sessizce "0 güncelleme" olarak görünüyor, aracı hiç
çağırmadan uydurma yanıt üretmesi ise "başarılı koşu" gibi raporlanıyordu.
Artık sonuçlar tip güvenli StationOutcome nesneleri olarak dönüyor —
sayım metin eşleşmesine değil, gerçekleşen işleme dayanıyor.
Gerekçenin tamamı için: services/tariff_pipeline.py modül docstring'i.
"""
import logging
import time

from config import settings
from models.agent_schemas import AgentRunResult
from services.db_service import db_service
from services.tariff_pipeline import OutcomeStatus, StationOutcome, run_for_stations

logger = logging.getLogger(__name__)


def _summarize(
    geohash5: str, outcomes: list[StationOutcome], duration_ms: int
) -> AgentRunResult:
    """StationOutcome listesini HTTP sözleşmesindeki özete çevirir."""
    written = [o for o in outcomes if o.is_written]
    cache_degraded = [o for o in outcomes if o.status is OutcomeStatus.CACHE_DEGRADED]
    fetch_failed = [o for o in outcomes if o.status is OutcomeStatus.FETCH_FAILED]
    invalid = [o for o in outcomes if o.status is OutcomeStatus.INVALID_RESPONSE]
    write_failed = [o for o in outcomes if o.status is OutcomeStatus.WRITE_FAILED]

    errors: list[str] = []
    if fetch_failed:
        errors.append(f"{len(fetch_failed)} istasyon için tarife toplanamadı.")
    if invalid:
        errors.append(
            f"{len(invalid)} istasyon için yanıt geçerli bir tarifeye dönüştürülemedi."
        )
    if write_failed:
        errors.append(
            f"{len(write_failed)} istasyon için veritabanı yazımı başarısız oldu."
        )
    if cache_degraded:
        errors.append(
            f"{len(cache_degraded)} istasyon için Redis cache güncellenemedi "
            f"(DB yazımı korundu)."
        )

    return AgentRunResult(
        geohash5=geohash5,
        stations_processed=len(outcomes),
        tariffs_updated=len(written),
        errors=errors,
        duration_ms=duration_ms,
    )


class AgentOrchestrator:
    def __init__(self) -> None:
        self._is_running_batch = False

    async def run_for_region(self, geohash5: str) -> AgentRunResult:
        start_time = time.monotonic()

        try:
            stations = await db_service.get_stations_by_geohash5(
                geohash5, settings.max_stations_per_batch
            )
        except Exception as exc:
            logger.error(
                "Bölge istasyonları alınamadı: geohash5=%s", geohash5, exc_info=exc
            )
            raise

        if not stations:
            return AgentRunResult(
                geohash5=geohash5,
                stations_processed=0,
                tariffs_updated=0,
                errors=["Bu bölgede canlı fiyatlandırma destekleyen istasyon bulunamadı."],
                duration_ms=int((time.monotonic() - start_time) * 1000),
            )

        try:
            outcomes = await run_for_stations(stations)
        except Exception as exc:
            logger.error(
                "Tarife hattı çalıştırılamadı: geohash5=%s", geohash5, exc_info=exc
            )
            raise

        result = _summarize(
            geohash5, outcomes, int((time.monotonic() - start_time) * 1000)
        )

        # Yeni fiyat yazildiysa trend gorunumunu tazele. Yazilmadiysa
        # tazelemek bosa is: view'in girdisi degismemistir.
        if result.tariffs_updated > 0:
            await db_service.refresh_price_trend()
        logger.info(
            "Bölge tamamlandı: geohash5=%s islenen=%s yazilan=%s sure=%sms",
            geohash5,
            result.stations_processed,
            result.tariffs_updated,
            result.duration_ms,
        )
        return result

    async def run_for_all_active_regions(self, max_regions: int = 20) -> list[AgentRunResult]:
        """Periyodik (cron benzeri) koşular için — Gateway'deki
        PriceAgentSchedulerService bu servisi bölge bölge çağırabilir; bu
        metod ise servisin kendi kendine, harici bir orkestratör olmadan da
        çalışabilmesini sağlayan bir dahili alternatiftir (örn. tek-instance
        development/test ortamında).
        """
        if self._is_running_batch:
            logger.warning("Toplu koşu zaten devam ediyor, yeni koşu atlanıyor.")
            return []

        self._is_running_batch = True
        try:
            regions = await db_service.get_active_geohash5_regions(max_regions)
            results: list[AgentRunResult] = []

            for geohash5 in regions:
                try:
                    result = await self.run_for_region(geohash5)
                    results.append(result)
                except Exception as exc:
                    logger.error(
                        "Bölge %s işlenirken hata, sonraki bölgeye geçiliyor.",
                        geohash5,
                        exc_info=exc,
                    )
                    results.append(
                        AgentRunResult(
                            geohash5=geohash5,
                            stations_processed=0,
                            tariffs_updated=0,
                            errors=[str(exc)],
                            duration_ms=0,
                        )
                    )

            return results
        finally:
            self._is_running_batch = False


orchestrator = AgentOrchestrator()
