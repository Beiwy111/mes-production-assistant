"""Validate public claims against the exact active vector evidence before indexing."""
import re

KINDS = {'DefectConcept', 'Method'}
RELATIONS = {'DETECTS', 'USES', 'CLASSIFIES'}


def normalized(text):
    return ' '.join(text.split())


def prepare_graph(catalog, manifest, chunks):
    if catalog.get('schema_version') != 1:
        raise ValueError('Unsupported claim schema')
    entities = catalog['entities']
    ids = [e['id'] for e in entities]
    if len(ids) != len(set(ids)) or not entities or len(entities) > 100:
        raise ValueError('Invalid entities')
    for entity in entities:
        if (not re.fullmatch(r'[A-Z][A-Z0-9_]{1,63}', entity['id']) or entity['kind'] not in KINDS or
                not isinstance(entity['name'], str) or not 1 <= len(entity['name']) <= 200 or
                not isinstance(entity['aliases'], list) or not 1 <= len(entity['aliases']) <= 20 or
                any(not isinstance(a, str) or not 2 <= len(a) <= 100 for a in entity['aliases'])):
            raise ValueError('Invalid entity metadata')
    docs = {d['id']: d for d in manifest['documents']}
    if len(chunks) != manifest['point_count'] or len({c['chunk_id'] for c in chunks}) != len(chunks):
        raise ValueError('Incomplete or duplicate vector evidence')
    for chunk in chunks:
        if (chunk['release_id'] != manifest['release_id'] or chunk['document_id'] not in docs or
                chunk['document_version'] != docs[chunk['document_id']]['sha256'] or
                chunk['visibility'] != 'PUBLIC' or chunk['knowledge_domain'] != 'display_reference' or
                chunk['review_status'] != 'REFERENCE_ALLOWED'):
            raise ValueError('Cross-release or unauthorized evidence')
    claims = []
    seen = set()
    if not catalog['claims'] or len(catalog['claims']) > 200:
        raise ValueError('Invalid claim budget')
    for claim in catalog['claims']:
        if (claim['id'] in seen or not re.fullmatch(r'[A-Z][A-Z0-9_]{1,63}', claim['id']) or
                claim['subject'] not in ids or claim['object'] not in ids or
                claim['relation'] not in RELATIONS or claim['review_status'] != 'SOURCE_VERIFIED' or
                claim['authority_type'] != 'PUBLIC_RESEARCH' or
                not isinstance(claim['statement'], str) or not 1 <= len(claim['statement']) <= 600 or
                not 20 <= len(normalized(claim['quote'])) <= 1000):
            raise ValueError('Unverified, dangling or invalid research claim')
        evidence = [c['chunk_id'] for c in chunks if c['document_id'] == claim['document_id'] and
                    c['document_version'] == claim['document_version'] and c['page_start'] == claim['page'] and
                    normalized(claim['quote']) in normalized(c['text'])]
        if not evidence:
            raise ValueError('Claim lacks matching page, version and source quote: ' + claim['id'])
        claims.append({**claim, 'evidence_chunk_ids': sorted(evidence)[:2], 'scope': 'RESEARCH_ONLY'})
        seen.add(claim['id'])
    return {'entities': entities, 'claims': claims, 'chunks': chunks, 'documents': manifest['documents']}


def resolve_entities(query, entities):
    result = []
    for entity in entities:
        for alias in entity['aliases']:
            # Latin aliases use Latin/digit boundaries, while Chinese words may be adjacent.
            pattern = r'(?<![A-Za-z0-9])' + re.escape(alias) + r'(?![A-Za-z0-9])' if alias.isascii() else re.escape(alias)
            if re.search(pattern, query, flags=re.IGNORECASE):
                result.append(entity['id'])
                break
    return sorted(set(result))[:4]
