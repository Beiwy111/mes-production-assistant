"""Local preparation and readback checks run by Initialize-KnowledgeLocal.ps1."""
import argparse
import json
import os
import re
import secrets
import subprocess
import sys
import time
from contextlib import closing
from pathlib import Path
from tempfile import NamedTemporaryFile

import httpx
from dotenv import dotenv_values
from neo4j import GraphDatabase

from mes_knowledge.cli import settings_from_file
from mes_knowledge.config import RAG_ROOT
from mes_knowledge.corpus import sha256_file
from mes_knowledge.graph.config import graph_settings, open_graph

DEPLOY = RAG_ROOT.parent / 'deploy'
DATA = RAG_ROOT / 'data'
QDRANT = 'http://127.0.0.1:16333'
PUBLIC_GRAPH = 'bolt://127.0.0.1:17687'
PRIVATE_GRAPH = 'bolt://127.0.0.1:17688'
API = 'http://127.0.0.1:18081'


def read_config(name):
    return dotenv_values(DEPLOY / f'.env.{name}.local', interpolate=False, encoding='utf-8-sig')


def create_config(name, settings):
    path = DEPLOY / f'.env.{name}.local'
    if path.exists():
        return
    temporary = None
    try:
        with NamedTemporaryFile(mode='w', encoding='utf-8', prefix=path.name + '.', dir=DEPLOY, delete=False) as file:
            temporary = Path(file.name)
            if os.name == 'nt':
                account = subprocess.check_output(['whoami', '/user', '/fo', 'csv', '/nh'])
                sid = re.search(rb'S-1-\d+(?:-\d+)+', account).group().decode('ascii')
                subprocess.run(['icacls', str(temporary), '/inheritance:r', '/grant:r',
                                '*' + sid + ':F', '*S-1-5-18:F'], check=True, capture_output=True)
            else:
                os.chmod(temporary, 0o600)
            for key, value in settings.items():
                file.write(f'{key}={value}\n')
            file.flush()
            os.fsync(file.fileno())
        if os.name == 'nt':
            temporary.rename(path)
        else:
            os.link(temporary, path)
    except FileExistsError:
        pass
    finally:
        if temporary:
            temporary.unlink(missing_ok=True)


def configure():
    writer_key = 'QDRANT__SERVICE__API_KEY'
    reader_key = 'QDRANT__SERVICE__READ_ONLY_API_KEY'
    old = read_config('knowledge')
    create_config('qdrant', {writer_key: secrets.token_hex(32),
                             reader_key: old.get('MES_KNOWLEDGE_QDRANT_READ_KEY') or secrets.token_hex(32)})
    qdrant = read_config('qdrant')
    writer, reader = qdrant.get(writer_key, ''), qdrant.get(reader_key, '')
    if min(len(writer), len(reader)) < 24 or writer == reader:
        raise ValueError('Invalid local Qdrant credentials. Restore the original private configuration.')
    create_config('knowledge', {'MES_KNOWLEDGE_QDRANT_READ_KEY': reader,
                                'MES_KNOWLEDGE_API_KEY': secrets.token_hex(32),
                                'MES_KNOWLEDGE_QDRANT_URL': QDRANT,
                                'MES_KNOWLEDGE_THREADS': '4'})
    settings = settings_from_file(DEPLOY / '.env.knowledge.local')
    if (settings.qdrant_read_key.get_secret_value() != reader or settings.qdrant_url != QDRANT
            or settings.data_dir.resolve() != DATA.resolve()
            or settings.api_key.get_secret_value() in (reader, writer)):
        raise ValueError('Local knowledge configuration and this checkout disagree. Check paths and credential bindings.')
    password = secrets.token_hex(32)
    create_config('neo4j', {'NEO4J_AUTH': 'neo4j/' + password, 'MES_GRAPH_PASSWORD': password,
                            'MES_GRAPH_USER': 'neo4j', 'MES_GRAPH_URI': PUBLIC_GRAPH, 'MES_GRAPH_ENABLED': 'true'})
    graph = graph_settings()
    if (not graph.enabled or graph.uri != PUBLIC_GRAPH or graph.user != 'neo4j' or not graph.password
            or read_config('neo4j').get('NEO4J_AUTH') != 'neo4j/' + graph.password.get_secret_value()):
        raise ValueError('Local public graph configuration is incomplete or inconsistent.')


def wait_stores():
    ingestion = read_config('ingestion')
    if ingestion.get('MES_INGEST_GRAPH_URI') != PRIVATE_GRAPH or ingestion.get('MES_INGEST_GRAPH_USER') != 'neo4j':
        raise ValueError('Private upload graph configuration must match the local ingestion Compose project.')
    with httpx.Client(base_url=QDRANT, trust_env=False, timeout=4) as client, GraphDatabase.driver(
        PRIVATE_GRAPH, auth=('neo4j', ingestion['MES_INGEST_GRAPH_PASSWORD']), connection_timeout=3,
        connection_acquisition_timeout=4, max_transaction_retry_time=0
    ) as graph:
        deadline = time.monotonic() + 120
        while time.monotonic() < deadline:
            try:
                response = client.get('/collections', headers={'api-key': read_config('qdrant')['QDRANT__SERVICE__READ_ONLY_API_KEY']})
                response.raise_for_status()
                with graph.session(database='neo4j') as session:
                    row = session.run('SHOW DATABASE neo4j YIELD access RETURN access').single()
                    if row and row['access'] == 'read-write':
                        return
            except Exception:
                pass
            time.sleep(2)
    raise RuntimeError('Authenticated Qdrant and writable upload graph readiness timed out. Check local project logs and credentials.')


def graph_current():
    path = DATA / 'graph-active.json'
    if not path.exists():
        return False
    receipt = json.loads(path.read_text('utf-8'))
    vector = json.loads((DATA / 'active.json').read_text('utf-8'))
    if (receipt.get('claims_sha256') != sha256_file(RAG_ROOT / 'corpus/research-claims.json')
            or receipt.get('status') != 'READY' or receipt.get('vector_release_id') != vector['release_id']
            or receipt.get('embedding_signature') != vector['embedding_signature']):
        return False
    with closing(open_graph(graph_settings())) as graph:
        return graph.verify_release(receipt['graph_release_id'], vector['release_id']) == receipt['counts']


def accept():
    vector = json.loads((DATA / 'active.json').read_text('utf-8'))
    graph = json.loads((DATA / 'graph-active.json').read_text('utf-8'))
    settings = settings_from_file(DEPLOY / '.env.knowledge.local')
    with httpx.Client(base_url=API, trust_env=False, timeout=60,
                      headers={'Authorization': 'Bearer ' + settings.api_key.get_secret_value()}) as client:
        deadline = time.monotonic() + 120
        while time.monotonic() < deadline:
            try:
                if client.get('/health').json().get('status') == 'alive':
                    break
            except (httpx.TransportError, ValueError):
                pass
            time.sleep(2)
        else:
            raise RuntimeError('Knowledge API did not become ready. Check rag/logs/api.stderr.log and API port ownership.')
        for mode in ('vector', 'graph_assisted'):
            response = client.post('/internal/knowledge/query', json={'query': 'Mura APSO', 'mode': mode, 'top_k': 8})
            response.raise_for_status()
            result = response.json()
            if result.get('release_id') != vector['release_id'] or not result.get('hits'):
                raise ValueError(f'Missing active {mode} evidence.')
            hit = result['hits'][0]
            evidence = client.get(hit['evidence_path'])
            evidence.raise_for_status()
            if not hit['text'] or evidence.json()['text'] != hit['text']:
                raise ValueError('Evidence readback differs from the original source.')
            if mode == 'graph_assisted':
                refs = {row['evidence_ref'] for row in result['hits']}
                if (result.get('graph_release_id') != graph['graph_release_id']
                        or result.get('mode_used') != mode or not result.get('graph_paths')
                        or not all(p['evidence_refs'] and set(p['evidence_refs']) <= refs for p in result['graph_paths'])):
                    raise ValueError('Graph retrieval failed to return paths with matching source evidence.')
        key = read_config('ingestion')['MES_KNOWLEDGE_INGESTION_API_KEY']
        jobs = client.get('/internal/ingestion/jobs', headers={'Authorization': 'Bearer ' + key})
        jobs.raise_for_status()
        if not isinstance(jobs.json().get('jobs'), list):
            raise ValueError('Private upload service is unavailable.')
    print(json.dumps({'vector_release': vector['release_id'], 'graph_release': graph['graph_release_id'],
                      'documents': len(vector['documents']), 'chunks': vector['point_count'],
                      'claims': graph['counts']['claims'], 'acceptance': 'vector, graph, evidence, private upload API'}))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('operation', choices=('configure', 'wait-stores', 'graph-current', 'accept'))
    action = parser.parse_args().operation
    if action == 'graph-current':
        sys.exit(0 if graph_current() else 3)
    {'configure': configure, 'wait-stores': wait_stores, 'accept': accept}[action]()
