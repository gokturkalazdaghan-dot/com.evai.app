# backend/ai-services/price_saving_agent/models/agent_schemas.py
from pydantic import BaseModel


class AgentRunRequest(BaseModel):
    """Belirli bir bölge (geohash5) için tetiklenen ajan koşusu."""

    geohash5: str
    force_refresh: bool = False


class AgentRunResult(BaseModel):
    geohash5: str
    stations_processed: int
    tariffs_updated: int
    errors: list[str]
    duration_ms: int


class AgentHealthDetail(BaseModel):
    status: str
    active_db_pool_size: int | None
    redis_connected: bool
