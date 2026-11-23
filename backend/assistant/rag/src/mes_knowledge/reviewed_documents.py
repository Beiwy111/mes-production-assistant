"""Local operator publication of reviewed, publicly redistributable manuals and standards."""
import argparse
from contextlib import closing
import json
import re
from pathlib import Path
from tempfile import TemporaryDirectory
from urllib.parse import urlsplit

from .config import RAG_ROOT
from .corpus import safe_source, sha256_file, split_pages
from .documents import parse_document
from .indexing import ingest


def reviewed_source(source, meta, temp):
    url = urlsplit(meta.get('source_url', ''))
    if (meta.get('visibility') != 'PUBLIC' or meta.get('review_status') != 'REFERENCE_ALLOWED'
            or meta.get('source_type') not in ('PUBLIC_MANUAL', 'PUBLIC_STANDARD')
            or not re.fullmatch(r'[A-Z][A-Z0-9_-]{1,59}', meta.get('id', ''))
            or url.scheme != 'https' or not url.hostname or url.username or url.password or url.query or url.fragment
            or not 1 <= len(meta.get('title', '').strip()) <= 250 or not meta.get('license', '').strip()):
        raise ValueError('Expected reviewed public metadata, redistribution license and public HTTPS citation')
    if source.suffix.lower() not in ('.docx', '.pdf') or source.stat().st_size > 4*1024*1024:
        raise ValueError('Expected a text PDF or DOCX within the parser budget')
    if sha256_file(source) != meta.get('sha256'):
        raise ValueError('File differs from the reviewed version')
    parsed = parse_document(source.name, source.read_bytes())
    if parsed['version'] != meta['sha256']:
        raise ValueError('Source changed during review')
    pages = [s['text'].strip() for s in parsed['sections']]
    text = temp / (meta['id'] + '-sections.txt')
    text.write_text('\n'.join(f'===== PDF PAGE {i} =====\n{page}' for i,page in enumerate(pages,1)), encoding='utf-8')
    return {**meta, 'pages':pages, 'page_count':len(pages), 'pdf_path':source, 'text_path':text,
            'text_sha256':sha256_file(text)}


def restore_active(data, manifest):
    catalog = json.loads((RAG_ROOT/'corpus/papers.json').read_text('utf-8'))['sources']
    published = {(d['id'], d['sha256']):d for d in catalog}
    documents = []
    for doc in manifest['documents']:
        original = safe_source(data, doc.get('source_ref') or doc['pdf_ref'])
        text = safe_source(data, doc['text_ref'])
        if sha256_file(original) != doc['sha256'] or sha256_file(text) != doc['text_sha256']:
            raise ValueError('Active source archive failed integrity check')
        pages = split_pages(text.read_text('utf-8'), doc['page_count'])
        documents.append({**published.get((doc['id'],doc['sha256']),{}), **doc,
                          'pages':pages,'pdf_path':original,'text_path':text})
    return documents


def append_reviewed(document, client, embedding, data, tokenizer_path):
    manifest = json.loads((data/'active.json').read_text('utf-8'))
    documents = restore_active(data, manifest)
    for current in documents:
        if current['sha256'] == document['sha256'] and current['id'] == document['id']:
            fields = ('source_type','title','source_url','license','text_sha256')
            if all(current.get(k) == document.get(k) for k in fields):
                return manifest
        if current['id'] == document['id'] or current['sha256'] == document['sha256']:
            raise ValueError('Source ID or content already registered; review a distinct revision')
    return ingest([*documents, document],client,embedding,data,tokenizer_path,expected_release=manifest['release_id'])


def main():
    from .cli import settings_from_file
    from .embedding import E5Embedding
    from dotenv import dotenv_values
    from qdrant_client import QdrantClient
    deploy = RAG_ROOT.parent/'deploy'
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('file', type=Path)
    parser.add_argument('review', type=Path, help='Reviewed public metadata JSON')
    args = parser.parse_args()
    settings = settings_from_file(deploy/'.env.knowledge.local')
    key = dotenv_values(deploy/'.env.qdrant.local',interpolate=False)['QDRANT__SERVICE__API_KEY']
    with TemporaryDirectory() as temp:
        doc = reviewed_source(args.file,json.loads(args.review.read_text('utf-8-sig')),Path(temp))
        with closing(QdrantClient(url=settings.qdrant_url,api_key=key,trust_env=False,timeout=30)) as client:
            result = append_reviewed(doc,client,E5Embedding(settings.data_dir/'model',settings.threads),
                                     settings.data_dir,settings.data_dir/'model/tokenizer.json')
        print(json.dumps({'release_id':result['release_id'],'documents':len(result['documents']),'chunks':result['point_count']}))


if __name__ == '__main__':
    main()

