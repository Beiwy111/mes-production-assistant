"""Internal public-reference API. Never accepts tenant/role/scope claims from an LLM."""
import hmac
import base64
from typing import Annotated, Literal

from fastapi import Depends, FastAPI, Header, HTTPException
from pydantic import BaseModel, ConfigDict, Field, field_validator

from .retrieval import Busy, Unavailable
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


def create_app(store, api_key: str, graph_store=None):
    if len(api_key) < 24:
        raise ValueError('Configure a strong internal service key')
    app = FastAPI(title='MES public research retrieval', version='0.1.0', docs_url=None, redoc_url=None,
                  openapi_url=None)
    graph_search = GraphSearch(store, graph_store)

    def authorize(authorization: Annotated[str | None, Header()] = None):
        if authorization is None or not hmac.compare_digest(authorization.encode(), ('Bearer ' + api_key).encode()):
            raise HTTPException(401, 'Authentication required')

    @app.get('/health')
    def health():
        return {'status': 'alive', 'scope': 'public-reference-only'}

    @app.post('/internal/knowledge/documents/parse', dependencies=[Depends(authorize)])
    def parse(request: ParseDocument):
        try:
            return parse_document(request.filename, base64.b64decode(request.content_base64, validate=True))
        except (DocumentError, ValueError) as exc:
            raise HTTPException(422, str(exc) if isinstance(exc, DocumentError) else 'Invalid base64 document') from None

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
