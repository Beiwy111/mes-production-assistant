"""Review artifacts and isolated staging; deliberately has no activation operation."""
import hashlib
import json
import math
import re
import uuid
from datetime import datetime
from html.parser import HTMLParser
from pathlib import Path

from qdrant_client import models

from ..corpus import sha256_file
from ..indexing import archive_sources, atomic_json
from .client import VERSION, COMMIT, identifier


def digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, ensure_ascii=False,
                         separators=(',', ':'), allow_nan=False).encode()).hexdigest()


class TableText(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.parts = []
        self.suppressed = 0

    def handle_starttag(self, tag, attrs):
        if tag in ('script', 'style'):
            self.suppressed += 1
        if tag == 'br':
            self.parts.append('\n')

    def handle_endtag(self, tag):
        if tag in ('script', 'style'):
            self.suppressed = max(0, self.suppressed - 1)
        if tag in ('td', 'th'):
            self.parts.append('\t')
        if tag in ('tr', 'p', 'table'):
            self.parts.append('\n')

    def handle_data(self, data):
        if not self.suppressed:
            self.parts.append(data)


def plain_text(content):
    if not re.search(r'<(?:table|tr|td|th)\b', content, re.I):
        return content.strip()
    parser = TableText()
    parser.feed(content)
    return '\n'.join(line.rstrip('\t ') for line in ''.join(parser.parts).splitlines() if line.strip()).strip()


def position_pages(positions, page_count):
    # Pinned SDK positions are [1-based PDF page, x0, x1, y0, y1].
    # Missing/unknown layouts are unresolved, never inferred from chunk order.
    if not isinstance(positions, list) or not positions or len(positions) > 1000:
        return None
    pages = []
    for pos in positions:
        if (not isinstance(pos, list) or len(pos) != 5 or
                any(type(n) not in (int, float) or not math.isfinite(n) for n in pos) or
                int(pos[0]) != pos[0] or not 1 <= pos[0] <= page_count or
                min(pos[1:]) < 0 or pos[1] > pos[2] or pos[3] > pos[4]):
            return None
        pages.append(int(pos[0]))
    return min(pages), max(pages)


def normalize_document(document, dataset_id, document_id, raw_chunks):
    identifier(dataset_id)
    identifier(document_id)
    chunks, seen = [], set()
    for raw in raw_chunks:
        chunk_id = identifier(raw.get('id'))
        if chunk_id in seen or raw.get('document_id') != document_id:
            raise ValueError('Chunk identity or source mismatch')
        seen.add(chunk_id)
        content = raw.get('content')
        if not isinstance(content, str) or not content.strip() or len(content) > 200000:
            raise ValueError('Chunk content is empty or exceeds budget')
        positions = raw.get('positions', [])
        pages = position_pages(positions, document['page_count'])
        text = plain_text(content)
        if not text:
            raise ValueError('Chunk has no usable text')
        chunks.append({'ragflow_chunk_id': chunk_id, 'draft_chunk_id': chunk_id, 'content': content, 'text': text,
                       'positions': positions, 'page_start': pages[0] if pages else None,
                       'page_end': pages[1] if pages else None, 'review_status': 'NEEDS_REVIEW',
                       'issues': ([] if pages else ['MISSING_OR_INVALID_PAGE_POSITION']) +
                                 (['MULTIPAGE_REQUIRES_SPLIT'] if pages and pages[0] != pages[1] else []),
                       'has_table': bool(re.search(r'<table\b', content, re.I))})
    if not chunks:
        raise ValueError('Empty parsed document cannot be reviewed')
    return {**{k: document[k] for k in ('id', 'title', 'sha256', 'text_sha256', 'page_count', 'source_url', 'license')},
            'ragflow_dataset_id': dataset_id, 'ragflow_document_id': document_id, 'chunks': chunks}


def make_draft(documents):
    if not documents or len({d['id'] for d in documents}) != len(documents):
        raise ValueError('Draft requires unique documents')
    return {'schema': 'mes-ragflow-draft-v1', 'status': 'DRAFT', 'parser_version': VERSION,
            'parser_commit': COMMIT, 'documents': documents}


def review_template(draft):
    return {'schema': 'mes-ragflow-review-v1', 'draft_sha256': digest(draft), 'status': 'UNREVIEWED',
            'reviewer': '', 'reviewed_at': '', 'documents': [
                {'id': d['id'], 'sha256': d['sha256'], 'approved_chunk_ids': [],
                 'pages': [''] * d['page_count']} for d in draft['documents']]}


def validate_review(draft, review, originals):
    if (draft.get('schema') != 'mes-ragflow-draft-v1' or draft.get('status') != 'DRAFT' or
            draft.get('parser_version') != VERSION or draft.get('parser_commit') != COMMIT or
            review.get('schema') != 'mes-ragflow-review-v1' or review.get('status') != 'APPROVED' or
            review.get('draft_sha256') != digest(draft) or not isinstance(review.get('reviewer'), str) or
            not review['reviewer'].strip() or len(review['reviewer']) > 128):
        raise ValueError('Explicit digest-bound human approval required')
    reviewed_at = datetime.fromisoformat(review.get('reviewed_at', ''))
    if reviewed_at.tzinfo is None:
        raise ValueError('Review timestamp requires a timezone')
    original_map = {d['id']: d for d in originals}
    review_docs = review.get('documents', [])
    draft_docs = draft['documents']
    if (len({d['id'] for d in draft_docs}) != len(draft_docs) or
            len(review_docs) != len(draft_docs) or
            {d['id'] for d in review_docs} != {d['id'] for d in draft_docs}):
        raise ValueError('Review document set mismatch')
    reviews = {d['id']: d for d in review_docs}
    chunks, reviewed_documents = [], []
    for doc in draft_docs:
        original = original_map.get(doc['id'])
        accepted = reviews[doc['id']]
        if (not original or original.get('source_type') != 'PUBLIC_RESEARCH' or
                original.get('review_status') != 'REFERENCE_ALLOWED' or original.get('license') != 'CC BY 4.0' or
                any(doc.get(k) != original.get(k) for k in ('sha256', 'text_sha256', 'page_count', 'source_url', 'title', 'license')) or
                accepted.get('sha256') != doc['sha256'] or sha256_file(original['pdf_path']) != doc['sha256'] or
                sha256_file(original['text_path']) != doc['text_sha256']):
            raise ValueError('Reviewed original source or metadata mismatch')
        identifier(doc['ragflow_dataset_id'])
        identifier(doc['ragflow_document_id'])
        approved_ids = accepted.get('approved_chunk_ids', [])
        ids = [identifier(c.get('draft_chunk_id', c['ragflow_chunk_id'])) for c in doc['chunks']]
        if (not ids or len(set(ids)) != len(ids) or len(approved_ids) != len(ids) or set(approved_ids) != set(ids)):
            raise ValueError('Every draft chunk requires explicit review; edit/split draft then re-review')
        pages = accepted.get('pages')
        if (not isinstance(pages, list) or len(pages) != doc['page_count'] or
                any(not isinstance(p, str) or not p.strip() or p != p.strip() or len(p) > 500000 for p in pages)):
            raise ValueError('Reviewed page text must cover each original PDF page')
        for chunk in doc['chunks']:
            identifier(chunk['ragflow_chunk_id'])
            draft_chunk_id = identifier(chunk.get('draft_chunk_id', chunk['ragflow_chunk_id']))
            bounds = position_pages(chunk.get('positions'), doc['page_count'])
            if (not bounds or bounds[0] != bounds[1] or bounds != (chunk.get('page_start'), chunk.get('page_end'))):
                raise ValueError('Missing, inconsistent or multipage page positions require source review')
            text = chunk.get('text')
            if not isinstance(text, str) or not text or len(text) > 200000 or text != plain_text(chunk['content']):
                raise ValueError('Chunk text does not match normalized content')
            page = pages[bounds[0] - 1]
            start = page.find(text)
            if start < 0 or page.find(text, start + 1) >= 0:
                raise ValueError('Evidence must have one exact location in reviewed page text')
            chunk_id = digest([doc['sha256'], doc['ragflow_document_id'], chunk['ragflow_chunk_id'], draft_chunk_id,
                               bounds[0], start, text])[:32]
            chunks.append({'chunk_id': chunk_id, 'document_id': doc['id'], 'document_version': doc['sha256'],
                'title': original['title'], 'source_url': original['source_url'], 'license': original['license'],
                'page_start': bounds[0], 'page_end': bounds[1], 'char_start': start, 'char_end': start + len(text),
                'text': text, 'visibility': 'PUBLIC', 'knowledge_domain': 'display_reference',
                'review_status': 'REFERENCE_ALLOWED', 'authority_type': 'PUBLIC_RESEARCH',
                'keywords': original.get('keywords', []), 'parser_version': VERSION,
                'ragflow_dataset_id': doc['ragflow_dataset_id'], 'ragflow_document_id': doc['ragflow_document_id'],
                'ragflow_chunk_id': chunk['ragflow_chunk_id'], 'positions': chunk['positions'],
                'draft_chunk_id': draft_chunk_id,
                'draft_sha256': digest(draft), 'review_sha256': digest(review)})
        reviewed_documents.append({**original, 'pages': pages})
    if not chunks:
        raise ValueError('Empty review cannot be staged')
    return chunks, reviewed_documents


def stage_reviewed(draft, review, originals, client, embedding, data_dir: Path):
    chunks, documents = validate_review(draft, review, originals)
    if embedding.dimension != 384:
        raise ValueError('Staging requires the existing 384-dimensional E5 embedding')
    release = uuid.uuid4().hex
    collection = 'mes_ragflow_stage_' + release
    # Fresh collection only. No aliases, active manifests, or graph state are written here.
    client.create_collection(collection, vectors_config=models.VectorParams(size=384, distance=models.Distance.COSINE))
    destination = data_dir / 'ragflow' / 'staged' / release
    destination.mkdir(parents=True, exist_ok=False)
    sources = []
    for doc in documents:
        page_text = '\n'.join(f'===== PDF PAGE {i} =====\n{p}\n' for i, p in enumerate(doc['pages'], 1))
        text_path = destination / (identifier(doc['id']) + '-pages.txt')
        text_path.write_text(page_text, encoding='utf-8')
        doc = {**doc, 'text_path': text_path, 'text_sha256': sha256_file(text_path)}
        refs = archive_sources(doc, data_dir)
        sources.append({**{k: doc[k] for k in ('id', 'title', 'sha256', 'text_sha256', 'source_url', 'license', 'page_count')}, **refs})
    for start in range(0, len(chunks), 8):
        batch = chunks[start:start + 8]
        vectors = embedding.documents([c['title'] + '\n' + ', '.join(c['keywords']) + '\n' + c['text'] for c in batch])
        points = [models.PointStruct(id=str(uuid.uuid5(uuid.NAMESPACE_URL, release + ':' + c['chunk_id'])),
                    vector=v, payload={**c, 'release_id': release}) for c, v in zip(batch, vectors, strict=True)]
        client.upsert(collection, points, wait=True)
    if client.count(collection, exact=True).count != len(chunks):
        raise ValueError('Staged count mismatch; nothing has been published')
    manifest = {'release_id': release, 'collection': collection, 'embedding_signature': embedding.signature,
                'point_count': len(chunks), 'documents': sources, 'scope': 'public-reference-only',
                'status': 'STAGED_NOT_PUBLISHED', 'draft_sha256': digest(draft), 'review_sha256': digest(review)}
    atomic_json(destination / 'draft.json', draft)
    atomic_json(destination / 'review.json', review)
    atomic_json(destination / 'manifest.json', manifest)
    return manifest
