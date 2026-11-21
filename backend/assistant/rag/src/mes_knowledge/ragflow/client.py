"""Bounded HTTP client for the verified RAGFlow v1.0.0 dataset API."""
import hashlib
import ipaddress
import json
import re
import time
from pathlib import Path
from urllib.parse import urlsplit

import httpx
from pydantic import SecretStr, field_validator, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict

VERSION = 'v1.0.0'
COMMIT = 'db1cd5929a7885a3074547791f5ecc4d2f891739'


class RagflowError(RuntimeError):
    """Safe messages only; never include response bodies or credentials."""


def identifier(value):
    if not isinstance(value, str) or not re.fullmatch(r'[A-Za-z0-9_-]{1,128}', value):
        raise ValueError('Invalid RAGFlow identifier')
    return value


class RagflowConfig(BaseSettings):
    model_config = SettingsConfigDict(env_prefix='MES_RAGFLOW_', extra='forbid', hide_input_in_errors=True)
    base_url: str = 'http://127.0.0.1:19380'
    allowed_hosts: tuple[str, ...] = ('127.0.0.1', 'localhost', '::1')
    api_key: SecretStr
    dataset_id: str
    timeout_seconds: int = 30
    poll_seconds: float = 2
    max_wait_seconds: int = 1800
    page_size: int = 100
    max_pages: int = 100
    max_chunks: int = 10000
    max_response_bytes: int = 4 * 1024 * 1024
    max_export_bytes: int = 64 * 1024 * 1024
    max_upload_bytes: int = 32 * 1024 * 1024

    @field_validator('dataset_id')
    @classmethod
    def valid_id(cls, value):
        return identifier(value)

    @model_validator(mode='after')
    def validate_config(self):
        url = urlsplit(self.base_url)
        if (url.scheme not in ('http', 'https') or url.username or url.password or
                url.path not in ('', '/') or url.query or url.fragment or
                url.hostname not in self.allowed_hosts):
            raise ValueError('RAGFlow URL must be an explicitly allowed origin')
        try:
            local = ipaddress.ip_address(url.hostname).is_loopback
        except ValueError:
            local = url.hostname == 'localhost'
        if url.scheme == 'http' and not local:
            raise ValueError('Remote RAGFlow origins require HTTPS')
        if not url.port or not 1 <= url.port <= 65535:
            raise ValueError('RAGFlow origin requires an explicit port')
        if len(self.api_key.get_secret_value()) < 12 or any(c.isspace() for c in self.api_key.get_secret_value()):
            raise ValueError('Private RAGFlow API key required')
        for name, low, high in [('timeout_seconds', 1, 120), ('poll_seconds', .01, 30),
                ('max_wait_seconds', 1, 7200), ('page_size', 1, 100), ('max_pages', 1, 200),
                ('max_chunks', 1, 20000), ('max_response_bytes', 64, 16*1024*1024),
                ('max_export_bytes', 64, 64*1024*1024),
                ('max_upload_bytes', 1, 64*1024*1024)]:
            if not low <= getattr(self, name) <= high:
                raise ValueError('RAGFlow resource limit out of bounds')
        return self


class RagflowClient:
    def __init__(self, config: RagflowConfig, *, transport=None):
        self.config = config
        self.http = httpx.Client(base_url=config.base_url.rstrip('/'),
            headers={'Authorization': 'Bearer ' + config.api_key.get_secret_value()},
            timeout=config.timeout_seconds, follow_redirects=False, trust_env=False, transport=transport)
        self.prefix = '/api/v1/datasets/' + identifier(config.dataset_id)

    def __enter__(self):
        return self

    def __exit__(self, *args):
        self.http.close()

    def _request(self, method, path, **kwargs):
        try:
            with self.http.stream(method, self.prefix + path, **kwargs) as response:
                if response.status_code != 200:
                    raise RagflowError(f'RAGFlow HTTP failure ({response.status_code})')
                body = bytearray()
                for part in response.iter_bytes(65536):
                    body.extend(part)
                    if len(body) > self.config.max_response_bytes:
                        raise RagflowError('RAGFlow response exceeds byte budget')
                result = json.loads(body)
        except (httpx.HTTPError, OSError):
            raise RagflowError('RAGFlow transport unavailable; no automatic mutation retry') from None
        except (ValueError, UnicodeError):
            raise RagflowError('RAGFlow returned invalid JSON') from None
        if not isinstance(result, dict) or type(result.get('code')) is not int or result['code'] != 0:
            raise RagflowError('RAGFlow API rejected operation')
        return result.get('data')

    def upload(self, pdf: Path, expected_sha256: str):
        # Snapshot local bytes: hash check and upload use the same bytes, no path/URL supplied by content.
        with pdf.open('rb') as stream:
            content = stream.read(self.config.max_upload_bytes + 1)
        if len(content) > self.config.max_upload_bytes or not content.startswith(b'%PDF-'):
            raise ValueError('Only bounded local PDF files are accepted')
        if hashlib.sha256(content).hexdigest() != expected_sha256:
            raise ValueError('Original PDF checksum mismatch')
        result = self._request('POST', '/documents', files={'file': ('source.pdf', content, 'application/pdf')})
        if not isinstance(result, list) or len(result) != 1 or not isinstance(result[0], dict):
            raise RagflowError('RAGFlow upload response has invalid document metadata')
        if result[0].get('dataset_id') != self.config.dataset_id:
            raise RagflowError('RAGFlow returned a different dataset')
        return identifier(result[0].get('id'))

    def parse(self, document_id):
        self._request('POST', '/chunks', json={'document_ids': [identifier(document_id)]})

    def wait(self, document_id):
        document_id = identifier(document_id)
        deadline = time.monotonic() + self.config.max_wait_seconds
        while time.monotonic() < deadline:
            result = self._request('GET', '/documents', params={'id': document_id, 'page': 1, 'page_size': 1})
            docs = result.get('docs') if isinstance(result, dict) else None
            if (not isinstance(docs, list) or len(docs) != 1 or not isinstance(docs[0], dict) or
                    docs[0].get('id') != document_id):
                raise RagflowError('RAGFlow document status missing or mismatched')
            state = str(docs[0].get('ingestion_status', docs[0].get('run')))
            if state in ('COMPLETED', 'DONE', '3'):
                # Go uses ingestion_status; legacy SDK payloads may expose numeric run.
                return docs[0]
            if state in ('FAILED', 'STOPPED', 'FAIL', 'CANCEL', '2', '4'):
                raise RagflowError('RAGFlow parse failed or was cancelled')
            if state not in ('UNSTART', 'CREATED', 'SCHEDULED', 'RUNNING', 'STOPPING', '0', '1', '5'):
                raise RagflowError('Unknown RAGFlow parse status')
            time.sleep(min(self.config.poll_seconds, max(0, deadline - time.monotonic())))
        raise RagflowError('RAGFlow parse wait exceeded deadline; resume export using saved document ID')

    def chunks(self, document_id):
        document_id = identifier(document_id)
        chunks, seen, total, byte_count = [], set(), None, 0
        for page in range(1, self.config.max_pages + 1):
            result = self._request('GET', '/documents/' + document_id + '/chunks',
                                   params={'page': page, 'page_size': self.config.page_size})
            if not isinstance(result, dict) or not isinstance(result.get('chunks'), list):
                raise RagflowError('Invalid RAGFlow chunks response')
            byte_count += len(json.dumps(result, ensure_ascii=False).encode())
            if byte_count > self.config.max_export_bytes:
                raise RagflowError('RAGFlow export exceeds aggregate byte budget')
            reported = result.get('total')
            if type(reported) is not int or not 0 <= reported <= self.config.max_chunks:
                raise RagflowError('RAGFlow chunk count exceeds budget or is invalid')
            if total is not None and reported != total:
                raise RagflowError('RAGFlow chunk count changed during export')
            total = reported
            batch = result['chunks']
            if len(batch) > self.config.page_size or (not batch and len(chunks) < total):
                raise RagflowError('Incomplete RAGFlow pagination')
            for chunk in batch:
                if not isinstance(chunk, dict) or chunk.get('document_id') != document_id:
                    raise RagflowError('RAGFlow chunk source mismatch')
                chunk_id = identifier(chunk.get('id'))
                if chunk_id in seen:
                    raise RagflowError('Duplicate RAGFlow chunk across pages')
                seen.add(chunk_id)
                chunks.append(chunk)
            if len(chunks) > total:
                raise RagflowError('RAGFlow returned more chunks than declared')
            if len(chunks) == total:
                return chunks
        raise RagflowError('RAGFlow pagination exceeds budget')
