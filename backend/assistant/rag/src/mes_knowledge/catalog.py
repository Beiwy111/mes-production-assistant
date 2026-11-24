"""Read-only enumeration of the active public evidence and its verified graph claims."""
from qdrant_client import models

from .graph.schema import normalized
from .retrieval import Unavailable


class CatalogChanged(Exception):
    pass


def _pages(store, manifest, selection, fields):
    cursor = None
    while True:
        rows, cursor = store.client.scroll(manifest['collection'], scroll_filter=selection,
            offset=cursor, limit=256, with_payload=fields, with_vectors=False)
        yield from rows
        if cursor is None:
            break


def _chunk_index(store, manifest, documents):
    # Keep only small ordering keys and point IDs across pages. Read full texts only
    # for this output page and for the active graph's bounded evidence set.
    selection = store._filter(manifest)
    fields = ['document_id', 'document_version', 'chunk_id', 'page_start', 'char_start', 'authority_type']
    chunks = {}
    for number, point in enumerate(_pages(store, manifest, selection, fields), 1):
        if number > 10000:
            raise Unavailable('Public corpus exceeds catalog budget')
        p = point.payload
        doc = documents.get(p['document_id'])
        if (doc is None or p['document_version'] != doc['sha256'] or
                p['authority_type'] != doc.get('source_type', 'PUBLIC_RESEARCH')):
            continue
        key = p['chunk_id']
        if key in chunks:
            raise Unavailable('Duplicate public chunk')
        chunks[key] = ((p['document_id'], p['page_start'], p.get('char_start', 0), key), point.id)
    return chunks


def _graph_rows(search, release, documents, all_visible):
    if search.graph is None:
        return None, []
    try:
        publication = search.manifest(release)
        rows = search.graph.catalog_claims(publication['graph_release_id'], release, documents)
        if all_visible and len(rows) != publication['counts']['claims']:
            raise ValueError('Published graph is incomplete')
        if any(r['document_id'] not in documents or not 1 <= len(r['evidence_chunk_ids']) <= 2 for r in rows):
            raise ValueError('Invalid claim supports')
        return publication, rows
    except Exception:
        return None, []


def _graph_paths(publication, rows, evidence):
    paths = []
    for row in rows:
        refs = []
        for chunk_id in sorted(set(row['evidence_chunk_ids'])):
            hit = evidence[chunk_id]
            if (hit['authority_type'] != 'PUBLIC_RESEARCH' or hit['document_id'] != row['document_id'] or
                    hit['document_version'] != row['document_version'] or hit['page_start'] != row['page'] or
                    not row['quote'].strip() or normalized(row['quote']) not in normalized(hit['text'])):
                raise ValueError('Graph evidence no longer matches source')
            refs.append(hit['evidence_ref'])
        paths.append({**{k: v for k, v in row.items() if k != 'evidence_chunk_ids'}, 'hops': 1,
            'graph_release_id': publication['graph_release_id'], 'authority_type': 'PUBLIC_RESEARCH',
            'scope': 'RESEARCH_ONLY', 'review_status': 'SOURCE_VERIFIED', 'evidence_refs': refs})
    return paths


def catalog_page(store, search, offset, limit, release_id):
    manifest = store.active()
    release = manifest['release_id']
    if release_id is not None and release_id != release:
        raise CatalogChanged()
    withdrawn = store.withdrawn()
    documents = {d['id']: d for d in manifest['documents'] if d['id'] not in withdrawn and
                 d.get('source_type', 'PUBLIC_RESEARCH') in ('PUBLIC_RESEARCH', 'PUBLIC_MANUAL', 'PUBLIC_STANDARD')}
    chunks = _chunk_index(store, manifest, documents)
    page = sorted(chunks, key=lambda key: chunks[key][0])[offset:offset + limit]
    publication, rows = _graph_rows(search, release, documents, len(documents) == len(manifest['documents']))
    required = set(page) | {key for row in rows for key in row['evidence_chunk_ids'] if key in chunks}
    evidence = {}
    if required:
        selection = store._filter(manifest)
        selection.must.append(models.HasIdCondition(has_id=[chunks[key][1] for key in required]))
        for point in _pages(store, manifest, selection, True):
            hit = store._hit(point.payload, release)
            evidence[hit['chunk_id']] = hit
    paths = []
    if publication:
        try:
            paths = _graph_paths(publication, rows, evidence)
            if search.manifest(release)['graph_release_id'] != publication['graph_release_id']:
                raise CatalogChanged()
        except (ValueError, LookupError, OSError, TypeError):
            publication, paths = None, []
    if store.active()['release_id'] != release or store.withdrawn() != withdrawn:
        raise CatalogChanged()
    if any(key not in evidence for key in page):
        raise Unavailable('Public evidence changed')
    entities = {p[k] for p in paths for k in ('subject_id', 'object_id')}
    counts = {'chunks': len(chunks), 'documents': len({order[0] for order, _ in chunks.values()}),
              'claims': len(paths), 'entities': len(entities),
              'evidence_links': sum(len(p['evidence_refs']) for p in paths)}
    end = offset + limit
    return {'release_id': release, 'graph_release_id': publication['graph_release_id'] if publication else None,
            'knowledge_domain': 'display_reference',
            'graph_status': 'READY' if publication else 'UNAVAILABLE_OR_STALE', 'counts': counts,
            'hits': [evidence[key] for key in page], 'graph_paths': paths[offset:end],
            'next_offset': end if end < max(len(paths), len(chunks)) else None}
