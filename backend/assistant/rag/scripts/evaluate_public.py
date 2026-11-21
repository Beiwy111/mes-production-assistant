"""Read-only local API smoke checks; no model-provider call or business data."""
import argparse
import json
import time
from datetime import datetime, timezone
from pathlib import Path

import httpx

from mes_knowledge.cli import settings_from_file
from mes_knowledge.config import RAG_ROOT


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    settings = settings_from_file(RAG_ROOT.parent / 'deploy/.env.knowledge.local')
    headers = {'Authorization': 'Bearer ' + settings.api_key.get_secret_value()}
    questions = [
        ('Mura缺陷为何在低对比度图像中难以检测？', 'P02'),
        ('分段伽马校正如何用于Mura缺陷检测？', 'P02'),
        ('YOLO-DEI如何改进液晶显示屏缺陷检测？', 'P01'),
        ('串扰缺陷的显著颜色通道频域滤波方法是什么？', 'P03'),
    ]
    checks, results = {}, []
    with httpx.Client(base_url='http://127.0.0.1:18081', trust_env=False, timeout=30) as client:
        checks['anonymous_rejected'] = client.post('/internal/knowledge/query', json={'query': 'Mura'}).status_code == 401
        checks['scope_injection_rejected'] = client.post('/internal/knowledge/query', headers=headers,
            json={'query': 'Mura', 'roles': ['ADMIN']}).status_code == 422
        for query, expected in questions:
            start = time.perf_counter()
            response = client.post('/internal/knowledge/query', headers=headers, json={'query': query, 'top_k': 5})
            response.raise_for_status()
            body = response.json()
            elapsed = round(time.perf_counter() - start, 3)
            first = body['hits'][0]
            evidence = client.get(first['evidence_path'], headers=headers)
            evidence.raise_for_status()
            same = evidence.json()['text'] == first['text'] and evidence.json()['evidence_ref'] == first['evidence_ref']
            results.append({'query': query, 'expected_document': expected,
                'hit_at_5': any(h['document_id'] == expected for h in body['hits']),
                'evidence_roundtrip': same, 'seconds': elapsed,
                'hits': [{k: h[k] for k in ('document_id', 'page_start', 'score', 'evidence_ref', 'text')}
                         for h in body['hits']]})
    manifest = json.loads((settings.data_dir / 'active.json').read_text(encoding='utf-8'))
    record = {'checked_at': datetime.now(timezone.utc).isoformat(), 'model': 'intfloat/multilingual-e5-small',
        'release_id': manifest['release_id'], 'point_count': manifest['point_count'],
        'scope': 'Four authored smoke questions; not a held-out benchmark or answer-correctness score.',
        'checks': checks, 'results': results}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(record, ensure_ascii=False, indent=2), encoding='utf-8')
    passed = all(checks.values()) and all(r['hit_at_5'] and r['evidence_roundtrip'] for r in results)
    print(json.dumps({'passed': passed, 'checks': checks, 'release_id': manifest['release_id'],
        'point_count': manifest['point_count'], 'questions': [
            {'expected': r['expected_document'], 'hit_at_5': r['hit_at_5'], 'seconds': r['seconds'],
             'top_pages': [f"{h['document_id']}:{h['page_start']}" for h in r['hits']]} for r in results]}, ensure_ascii=False))
    raise SystemExit(0 if passed else 1)


if __name__ == '__main__':
    main()
