"""Internal public-reference API. Never accepts tenant/role/scope claims from an LLM."""
import hmac
import base64
from contextlib import asynccontextmanager
from typing import Annotated, Literal

from fastapi import Depends, FastAPI, Header, HTTPException, Query as QueryParam
from pydantic import BaseModel, ConfigDict, Field, field_validator

from .retrieval import Busy, Unavailable
from .catalog import CatalogChanged, catalog_page
from .graph.search import GraphSearch
from .documents import parse_document, DocumentError


class ParseDocument(BaseModel):
    model_config = ConfigDict(extra='forbid')
    filename: str = Field(min_length=1, max_length=200)
    content_base64: str = Field(min_length=1, max_length=5592408)


class Query(BaseModel):
    model_config = ConfigDict(extra='forbid')
    query: str = Field(min_length=1, max_length=2000)
    top_k: int = Field(default=5, ge=1, le=8, strict=True)
    mode: Literal['vector', 'hybrid', 'graph_assisted'] = 'vector'

    @field_validator('query')
    @classmethod
    def nonblank(cls, value):
        if not value.strip():
            raise ValueError('Query must not be blank')
        return value.strip()


class GraphTrace(BaseModel):
    model_config = ConfigDict(extra='forbid')
    template_id: Literal['DEFECT_KNOWLEDGE'] = 'DEFECT_KNOWLEDGE'
    entity_id: str = Field(pattern=r'^[A-Z][A-Z0-9_]{1,63}$')
    max_paths: int = Field(default=8, ge=1, le=8, strict=True)


class ExperienceQuery(BaseModel):
    model_config = ConfigDict(extra='forbid', str_strip_whitespace=True)
    query: str = Field(min_length=1, max_length=2000)
    domain: Literal['MAINTENANCE', 'QUALITY', 'DIAGNOSTIC']
    top_k: int = Field(default=5, ge=1, le=8, strict=True)


def create_app(store, api_key: str, graph_store=None, *, experience=None, experience_key=None,
               ingestion=None, ingestion_key=None):
    if len(api_key) < 24:
        raise ValueError('Configure a strong internal service key')
    if ingestion_key is not None and (len(ingestion_key) < 24 or ingestion_key in (api_key, experience_key)):
        raise ValueError('Private ingestion requires a distinct internal credential')

    @asynccontextmanager
    async def lifespan(_app):
        if ingestion:
            ingestion.start()
        try:
            yield
        finally:
            if ingestion:
                ingestion.close()

    app = FastAPI(title='MES knowledge retrieval', version='0.1.0', docs_url=None, redoc_url=None,
                  openapi_url=None, lifespan=lifespan)
    from .ingestion.routes import private_router
    app.include_router(private_router(ingestion, ingestion_key))
    graph_search = GraphSearch(store, graph_store)
    if experience_key is not None and (len(experience_key) < 24 or hmac.compare_digest(experience_key, api_key)):
        raise ValueError('Experience retrieval needs a distinct internal credential')

    def authorize(authorization: Annotated[str | None, Header()] = None):
        if authorization is None or not hmac.compare_digest(authorization.encode(), ('Bearer ' + api_key).encode()):
            raise HTTPException(401, 'Authentication required')

    def authorize_experience(authorization: Annotated[str | None, Header()] = None):
        if not experience_key or not authorization or not hmac.compare_digest(
                authorization.encode(), ('Bearer ' + experience_key).encode()):
            raise HTTPException(401, 'Authentication required')

    @app.post('/internal/experience/query', dependencies=[Depends(authorize_experience)])
    def experience_query(request: ExperienceQuery):
        if experience is None:
            raise HTTPException(503, 'Experience service unavailable')
        try:
            kinds = ['MAINTENANCE', 'QUALITY'] if request.domain == 'DIAGNOSTIC' else [request.domain]
            return experience.query(request.query, request.top_k, kinds=kinds)
        except Busy:
            raise HTTPException(429, 'Experience service busy') from None
        except Exception:
            raise HTTPException(503, 'Experience service unavailable') from None

    @app.get('/health')
    def health():
        return {'status': 'alive', 'scope': 'public-reference-only', 'private_experience': 'configured' if experience_key and experience else 'disabled'}

    @app.post('/internal/knowledge/documents/parse', dependencies=[Depends(authorize)])
    def parse(request: ParseDocument):
        try:
            return parse_document(request.filename, base64.b64decode(request.content_base64, validate=True))
        except (DocumentError, ValueError) as exc:
            raise HTTPException(422, str(exc) if isinstance(exc, DocumentError) else 'Invalid base64 document') from None

    @app.get('/internal/knowledge/catalog', dependencies=[Depends(authorize)])
    def catalog(offset: Annotated[int, QueryParam(ge=0)] = 0,
                limit: Annotated[int, QueryParam(ge=1, le=200)] = 100,
                release_id: Annotated[str | None, QueryParam(pattern=r'^[a-f0-9]{32}$')] = None):
        try:
            return catalog_page(store, graph_search, offset, limit, release_id)
        except CatalogChanged:
            raise HTTPException(409, 'Public catalog changed; reload from the first page') from None
        except Exception:
            raise HTTPException(503, 'Knowledge catalog unavailable') from None

    @app.post('/internal/knowledge/query', dependencies=[Depends(authorize)])
    def query(request: Query):
        try:
            return (graph_search.query(request.query, request.top_k) if request.mode == 'graph_assisted'
                    else store.query(request.query, request.top_k, hybrid=True) if request.mode == 'hybrid'
                    else store.query(request.query, request.top_k))
        except Busy:
            raise HTTPException(429, 'Knowledge service is busy; retry later') from None
        except Unavailable:
            raise HTTPException(503, 'Knowledge service unavailable') from None

    @app.post('/internal/knowledge/graph/trace', dependencies=[Depends(authorize)])
    def trace(request: GraphTrace):
        try:
            return graph_search.trace(request.entity_id, request.max_paths)
        except ValueError:
            raise HTTPException(422, 'Unknown public concept') from None
        except Unavailable:
            raise HTTPException(503, 'Graph evidence unavailable or stale') from None

    @app.get('/internal/knowledge/evidence/{release_id}/{chunk_id}', dependencies=[Depends(authorize)])
    def evidence(release_id: str, chunk_id: str):
        try:
            return store.evidence(release_id, chunk_id)
        except LookupError:
            raise HTTPException(404, 'Evidence unavailable or withdrawn') from None
        except Unavailable:
            raise HTTPException(503, 'Knowledge service unavailable') from None

    return app
