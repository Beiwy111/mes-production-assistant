"""Verified public sources and page-local chunks. No URLs are fetched during ingestion."""
import hashlib
import json
import re
from pathlib import Path


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(block)
    return digest.hexdigest()


def safe_source(root: Path, value: str) -> Path:
    root = root.resolve()
    path = (root / value).resolve()
    if Path(value).is_absolute() or not path.is_relative_to(root):
        raise ValueError('Source path must stay within the explicit source root')
    return path


def split_pages(text: str, expected: int) -> list[str]:
    parts = re.split(r'^===== PDF PAGE (\d+) =====\s*$', text, flags=re.MULTILINE)
    numbers = [int(n) for n in parts[1::2]]
    if parts[0].strip() or numbers != list(range(1, expected + 1)):
        raise ValueError('Page markers do not match the declared PDF page count')
    pages = [p.strip() for p in parts[2::2]]
    if any(not page for page in pages):
        raise ValueError('Empty extracted page needs review')
    return pages


def load_corpus(registry: Path, source_root: Path) -> list[dict]:
    records = json.loads(registry.read_text(encoding='utf-8'))['sources']
    documents = []
    ids = set()
    for record in records:
        if record['id'] in ids:
            raise ValueError('Duplicate document ID')
        ids.add(record['id'])
        if (record.get('source_type') != 'PUBLIC_RESEARCH' or
                record.get('review_status') != 'REFERENCE_ALLOWED' or record.get('license') != 'CC BY 4.0'):
            raise ValueError('Only reviewed open public research is allowed in this release')
        pdf = safe_source(source_root, record['file'])
        text = safe_source(source_root, record['text_file'])
        if sha256_file(pdf) != record['sha256'] or sha256_file(text) != record['text_sha256']:
            raise ValueError('Source checksum mismatch; review the new version before publishing')
        pages = split_pages(text.read_text(encoding='utf-8'), record['page_count'])
        documents.append({**record, 'pages': pages, 'pdf_path': pdf, 'text_path': text})
    if not documents:
        raise ValueError('Empty corpus cannot be published')
    return documents


def chunk_document(document: dict, tokenizer, max_tokens: int = 320, overlap: int = 48) -> list[dict]:
    if not 0 <= overlap < max_tokens:
        raise ValueError('Invalid chunk overlap')
    chunks = []
    # The indexing tokenizer must not truncate or pad; offsets refer to the exact stored page.
    tokenizer.no_truncation()
    tokenizer.no_padding()
    for page_number, page in enumerate(document['pages'], 1):
        references = re.search(r'^\s*References\s*$', page, flags=re.MULTILINE | re.IGNORECASE)
        indexed_page = page[:references.start()] if references else page
        offsets = tokenizer.encode(indexed_page, add_special_tokens=False).offsets
        for start in range(0, len(offsets), max_tokens - overlap):
            end = min(start + max_tokens, len(offsets))
            char_start, char_end = offsets[start][0], offsets[end - 1][1]
            text = page[char_start:char_end]
            identity = f"{document['id']}:{document['sha256']}:{page_number}:{char_start}:{char_end}:{text}"
            chunks.append({
                'chunk_id': hashlib.sha256(identity.encode()).hexdigest()[:32],
                'document_id': document['id'], 'document_version': document['sha256'],
                'title': document['title'], 'source_url': document['source_url'],
                'license': document['license'], 'page_start': page_number, 'page_end': page_number,
                'char_start': char_start, 'char_end': char_end, 'text': text,
                'knowledge_domain': 'display_reference', 'visibility': 'PUBLIC',
                'review_status': 'REFERENCE_ALLOWED', 'authority_type': 'PUBLIC_RESEARCH',
                'keywords': document.get('keywords', []),
            })
            if end == len(offsets):
                break
        if references:
            break  # Bibliography titles are not evidence of this paper's findings; originals remain archived.
    return chunks
