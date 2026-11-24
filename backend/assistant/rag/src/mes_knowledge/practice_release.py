"""Publish the versioned public-reference catalog while retaining separately approved documents."""

import json
import math
import re
from contextlib import closing
from pathlib import Path
from tempfile import TemporaryDirectory
from urllib.parse import urlsplit

from .config import RAG_ROOT
from .corpus import load_corpus, sha256_file
from .indexing import ingest
from .reviewed_documents import restore_active


def wafer_example(folder):
    name = 'MONITOR-6_3_3_2.DAT'
    raw = (folder / name).read_text('ascii')
    pairs = [(int(i), float(p)) for i, p in re.findall(r'^\s*(\d+)\s+([01]\.\d+)\s*$', raw, re.M)]
    if [i for i, _ in pairs] != list(range(1, 31)) or any(
        not 0 <= p <= 1 or not math.isclose(p * 50, round(p * 50)) for _, p in pairs
    ):
        raise ValueError('Unexpected wafer reference data')
    total = 50 * len(pairs)
    failures = sum(round(50 * p) for _, p in pairs)
    rate = failures / total
    distance = 3 * math.sqrt(rate * (1 - rate) / 50)
    outside = '、'.join(f'第{i}片（{p:.0%}）' for i, p in pairs if abs(p - rate) > distance)
    return {
        'id': 'M_STAT_WAFER_CASE',
        'title': '用 NIST 晶圆样本演算 p 控制图',
        'source_url': 'https://www.itl.nist.gov/div898/handbook/datasets/' + name,
        'keywords': ['晶圆算例', '晶片错位', 'wafer', 'p-chart', '控制图示例'],
        'attribution': '根据 NIST/SEMATECH 工程统计手册公开数据文件 ' + name + ' 重新计算。',
        'text': f'NIST 手册的公开算例共有 30 片晶圆，每片测 50 颗芯片，用位置偏差判断芯片是否不合格。'
        f'按原始数据的比例换算，总计测量 {total} 颗，有 {failures} 颗不合格。用全部样本建立初始基线，'
        f'中心线为 {rate:.2%}，按每组 50 颗计算三倍标准差的上下控制限，分别为 {rate+distance:.2%} 和 {rate-distance:.2%}。'
        f'{outside}超出初始控制限，应优先调查。此处数值只对应公开晶圆算例。'
        '应用到显示器生产时应重新读取实际检验台数、不合格台数和经过确认的稳定基线。',
    }


def practice_notes(folder, temp):
    notes = []
    catalogs = [json.loads((folder / f'{name}.json').read_text('utf-8')) for name in ('display', 'quality')]
    catalogs.append([wafer_example(folder)])
    for group in catalogs:
        for item in group:
            uid = item['id']
            url = urlsplit(item['source_url'])
            if (
                not re.fullmatch(r'M_(?:DISP|STAT)_[A-Z0-9_]+', uid)
                or url.scheme != 'https'
                or url.hostname not in {'www.dell.com', 'www.itl.nist.gov'}
                or url.username
                or url.password
                or url.query
                or url.fragment
                or not all(item[k] for k in ('title', 'keywords', 'text', 'attribution'))
            ):
                raise ValueError('Invalid public note metadata: ' + uid)
            # Archive our own note and attribution, not a copy or purported translation of the vendor manual.
            source = temp / (uid + '.md')
            body = item['title'] + '\n\n' + item['text']
            source.write_bytes(
                (body + '\n\n' + item['attribution'] + '\n' + item['source_url'] + '\n').encode()
            )
            text = temp / (uid + '-sections.txt')
            text.write_bytes(('===== PDF PAGE 1 =====\n' + body).encode())
            notes.append(
                {
                    **item,
                    'title': item['title'] + '（中文参考笔记）',
                    'source_type': 'PUBLIC_MANUAL',
                    'review_status': 'REFERENCE_ALLOWED',
                    'license': '项目原创参考笔记；外部原文权利归来源方',
                    'pages': [body],
                    'page_count': 1,
                    'pdf_path': source,
                    'text_path': text,
                    'sha256': sha256_file(source),
                    'text_sha256': sha256_file(text),
                }
            )
    return notes


def merge_documents(current, catalog):
    requested = {doc['id']: doc for doc in catalog}
    if len(requested) != len(catalog):
        raise ValueError('Duplicate public catalog ID')
    existing = {doc['id'] for doc in current}
    return [requested.get(doc['id'], doc) for doc in current] + [
        doc for doc in catalog if doc['id'] not in existing
    ]


def main():
    from dotenv import dotenv_values
    from qdrant_client import QdrantClient
    from .cli import settings_from_file
    from .embedding import E5Embedding

    deploy = RAG_ROOT.parent / 'deploy'
    settings = settings_from_file(deploy / '.env.knowledge.local')
    data = settings.data_dir
    active = data / 'active.json'
    manifest = json.loads(active.read_text('utf-8')) if active.exists() else None
    with TemporaryDirectory() as directory:
        papers = load_corpus(
            RAG_ROOT / 'corpus/papers.json', RAG_ROOT.parents[2] / 'docs/ai-planning-2026-11-07'
        )
        current = restore_active(data, manifest) if manifest else []
        docs = merge_documents(
            current, [*papers, *practice_notes(RAG_ROOT / 'corpus/practice', Path(directory))]
        )
        fields = ('id', 'sha256', 'text_sha256', 'title', 'source_url', 'license', 'source_type', 'keywords')

        def signature(rows):
            return [[row.get(k) for k in fields] for row in rows]

        writer = dotenv_values(deploy / '.env.qdrant.local', interpolate=False)['QDRANT__SERVICE__API_KEY']
        with closing(
            QdrantClient(url=settings.qdrant_url, api_key=writer, trust_env=False, timeout=30)
        ) as client:
            from qdrant_client import models
            embedding = E5Embedding(data / 'model', settings.threads)
            if (manifest and signature(docs) == signature(current)
                    and manifest['embedding_signature'] == embedding.signature
                    and client.collection_exists(manifest['collection'])):
                generation = models.Filter(must=[models.FieldCondition(
                    key='release_id', match=models.MatchValue(value=manifest['release_id']))])
                if client.count(manifest['collection'], count_filter=generation, exact=True).count == manifest['point_count']:
                    print('Active public catalog and vector generation already match.')
                    return
            result = ingest(
                docs,
                client,
                embedding,
                data,
                data / 'model/tokenizer.json',
                expected_release=manifest['release_id'] if manifest else '',
            )
        print(json.dumps({k: result[k] for k in ('release_id', 'point_count')}))


if __name__ == '__main__':
    main()
