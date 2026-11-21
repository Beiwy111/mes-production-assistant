"""Local, resumable ingestion/export and honest baseline comparisons."""
import json
import re
from pathlib import Path

from ..corpus import sha256_file, split_pages, safe_source, chunk_document
from ..indexing import atomic_json
from .client import VERSION, identifier
from .drafts import make_draft, normalize_document, position_pages, review_template


def read_json(path: Path):
    with path.open('rb') as stream:
        data = stream.read(64 * 1024 * 1024 + 1)
    if len(data) > 64 * 1024 * 1024:
        raise ValueError('Artifact exceeds 64 MiB budget')
    return json.loads(data)


def archived_corpus(registry: Path, data_dir: Path):
    documents, seen = [], set()
    for record in read_json(registry)['sources']:
        identifier(record['id'])
        if record['id'] in seen:
            raise ValueError('Duplicate source ID')
        seen.add(record['id'])
        if (record.get('source_type') != 'PUBLIC_RESEARCH' or record.get('review_status') != 'REFERENCE_ALLOWED' or
                record.get('license') != 'CC BY 4.0' or
                any(not re.fullmatch(r'[a-f0-9]{64}', record.get(k, '')) for k in ('sha256', 'text_sha256'))):
            raise ValueError('Only registered, reviewed public originals may be uploaded')
        folder = 'sources/' + record['sha256'] + '/'
        pdf = safe_source(data_dir, folder + 'original.pdf')
        text = safe_source(data_dir, folder + 'text-' + record['text_sha256'] + '.txt')
        if sha256_file(pdf) != record['sha256'] or sha256_file(text) != record['text_sha256']:
            raise ValueError('Archived source checksum mismatch')
        pages = split_pages(text.read_text(encoding='utf-8'), record['page_count'])
        documents.append({**record, 'pdf_path': pdf, 'text_path': text, 'pages': pages})
    if not documents:
        raise ValueError('Empty corpus')
    return documents


def ingest_drafts(documents, client, destination: Path):
    destination.mkdir(parents=True, exist_ok=True)
    receipt_path = destination / 'upload-receipt.json'
    if receipt_path.exists():
        raise ValueError('Upload receipt already exists; resume export instead of uploading duplicates')
    receipt = {'schema': 'mes-ragflow-upload-v1', 'parser_version': VERSION,
               'dataset_id': client.config.dataset_id, 'documents': []}
    atomic_json(receipt_path, receipt)
    for doc in documents:
        remote_id = client.upload(doc['pdf_path'], doc['sha256'])
        # Save before triggering parse so a failed parse request cannot lose the upload identity.
        receipt['documents'].append({'id': doc['id'], 'sha256': doc['sha256'],
                                     'ragflow_document_id': remote_id})
        atomic_json(receipt_path, receipt)
        client.parse(remote_id)
    return export_drafts(documents, client, receipt, destination)


def export_drafts(documents, client, receipt, destination: Path):
    if (receipt.get('schema') != 'mes-ragflow-upload-v1' or receipt.get('parser_version') != VERSION or
            receipt.get('dataset_id') != client.config.dataset_id):
        raise ValueError('Upload receipt does not match configured parser/dataset')
    originals = {d['id']: d for d in documents}
    rows = receipt.get('documents', [])
    if not rows or len({r['id'] for r in rows}) != len(rows):
        raise ValueError('Upload receipt requires unique document identities')
    normalized = []
    for row in rows:
        doc = originals.get(row['id'])
        if not doc or doc['sha256'] != row.get('sha256'):
            raise ValueError('Upload receipt original checksum mismatch')
        remote_id = identifier(row['ragflow_document_id'])
        client.wait(remote_id)
        normalized.append(normalize_document(doc, client.config.dataset_id, remote_id, client.chunks(remote_id)))
    draft = make_draft(normalized)
    destination.mkdir(parents=True, exist_ok=True)
    # Refuse overwrites to retain prior export/review evidence.
    if (destination / 'draft.json').exists() or (destination / 'review-template.json').exists():
        raise ValueError('Draft destination already contains an export; use a new directory')
    atomic_json(destination / 'draft.json', draft)
    atomic_json(destination / 'review-template.json', review_template(draft))
    return draft


def comparison(documents, draft=None, tokenizer_path=None):
    tokenizer = None
    if tokenizer_path:
        from tokenizers import Tokenizer
        tokenizer = Tokenizer.from_file(str(tokenizer_path))
    parsed = {d['id']: d for d in draft['documents']} if draft else {}
    rows = []
    for doc in documents:
        item = parsed.get(doc['id'])
        if item and (item['sha256'] != doc['sha256'] or item['page_count'] != doc['page_count']):
            raise ValueError('Comparison requires the same original PDF')
        chunks = item['chunks'] if item else None
        bounds = [position_pages(c.get('positions'), doc['page_count']) for c in chunks] if chunks else []
        rows.append({'id': doc['id'], 'sha256': doc['sha256'], 'baseline_pages': len(doc['pages']),
            'baseline_characters': sum(map(len, doc['pages'])),
            'baseline_chunks': len(chunk_document(doc, tokenizer)) if tokenizer else None,
            'ragflow_chunks': len(chunks) if chunks is not None else None,
            'ragflow_characters': sum(len(c['text']) for c in chunks) if chunks is not None else None,
            'table_chunks': sum(bool(c.get('has_table')) for c in chunks) if chunks is not None else None,
            'unresolved_positions': sum(b is None or b[0] != b[1] for b in bounds) if chunks is not None else None,
            'pages_covered': sorted({p for b in bounds if b for p in range(b[0], b[1] + 1)}) if chunks is not None else None,
            'exact_baseline_matches': sum(b is not None and b[0] == b[1] and c['text'] in doc['pages'][b[0]-1]
                                          for c, b in zip(chunks, bounds)) if chunks is not None else None,
            'page_character_counts': [len(page) for page in doc['pages']]})
    return {'schema': 'mes-ragflow-comparison-v1', 'ragflow_status': 'EXPORTED_DRAFT' if draft else 'NOT_PARSED',
            'quality_verdict': 'NOT_EVALUATED', 'documents': rows,
            'limitations': ['Counts and exact matches do not establish OCR/table accuracy or retrieval quality.',
                'Manually compare tables, formulas, reading order and page positions against each original PDF.',
                'No publication or graph update is performed by this command.']}
