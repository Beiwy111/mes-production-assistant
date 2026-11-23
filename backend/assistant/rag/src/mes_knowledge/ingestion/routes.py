import base64
import hmac
from typing import Annotated
from urllib.parse import quote

from fastapi import APIRouter, Depends, Header, HTTPException, Response
from pydantic import BaseModel, ConfigDict, Field

from .jobs import Conflict
from .parsing import DocumentError, MAX_BYTES


class Upload(BaseModel):
    model_config = ConfigDict(extra='forbid')
    filename: str = Field(min_length=1, max_length=200)
    content_base64: str = Field(min_length=1, max_length=4 * ((MAX_BYTES + 2) // 3))


class Search(BaseModel):
    model_config = ConfigDict(extra='forbid', str_strip_whitespace=True)
    query: str = Field(min_length=1, max_length=2000)
    jobId: str | None = Field(default=None, pattern=r'^[0-9a-f]{32}$')
    top_k: int = Field(default=5, ge=1, le=20)


def private_router(jobs, key):
    def authorize(authorization: Annotated[str | None, Header()] = None):
        if not key or not authorization or not hmac.compare_digest(authorization.encode(), ('Bearer ' + key).encode()):
            raise HTTPException(401, 'Authentication required')
        if jobs is None:
            raise HTTPException(503, '私有知识入库服务未配置')

    router = APIRouter(prefix='/internal/ingestion', dependencies=[Depends(authorize)])

    def call(operation, *args):
        try:
            return operation(*args)
        except LookupError:
            raise HTTPException(404, '资料不存在') from None
        except Conflict as exc:
            raise HTTPException(409, str(exc)) from None
        except DocumentError as exc:
            raise HTTPException(422, str(exc)) from None
        except Exception:
            raise HTTPException(503, '私有知识服务暂时不可用') from None

    @router.post('/jobs', status_code=202)
    def upload(request: Upload):
        try:
            data = base64.b64decode(request.content_base64, validate=True)
        except ValueError:
            raise HTTPException(422, '文件编码无效') from None
        return call(jobs.enqueue, request.filename, data)

    @router.get('/jobs')
    def list_jobs():
        return {'jobs': call(jobs.list)}

    @router.get('/jobs/{job_id}')
    def detail(job_id: str):
        return call(jobs.get, job_id)

    @router.get('/jobs/{job_id}/source')
    def source(job_id: str):
        name, content = call(jobs.source, job_id)
        return Response(content=content, media_type='application/octet-stream',
                        headers={'Content-Disposition': "attachment; filename*=UTF-8''" + quote(name, safe=''),
                                 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff'})

    @router.post('/jobs/{job_id}/retry', status_code=202)
    def retry(job_id: str):
        return call(jobs.retry, job_id)

    @router.delete('/jobs/{job_id}')
    def delete(job_id: str):
        return call(jobs.delete, job_id)

    @router.delete('/jobs/{job_id}/document')
    def delete_published_document(job_id: str):
        return call(jobs.delete, job_id, True)

    @router.post('/search')
    def search(request: Search):
        return call(jobs.search, request.query, request.jobId, request.top_k)

    return router
