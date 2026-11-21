"""Evidence-bounded local GraphRAG with explicit vector fallback."""
import json
import re

from ..retrieval import Unavailable
from .schema import normalized, resolve_entities


class GraphSearch:
    def __init__(self, vector, graph):
        self.vector = vector
        self.graph = graph

    @staticmethod
    def rooted(paths, roots):
        reached = set(roots)
        selected = {}
        for hop in (1, 2):
            frontier = set(reached)
            for path in paths:
                if path['subject_id'] in frontier or path['object_id'] in frontier:
                    selected.setdefault(path['claim_id'], {**path, 'hops': hop})
                    reached.update((path['subject_id'], path['object_id']))
        return list(selected.values())

    def manifest(self, vector_release):
        value = json.loads((self.vector.data_dir / 'graph-active.json').read_text(encoding='utf-8'))
        active = self.vector.active()
        if (not re.fullmatch(r'[a-f0-9]{32}', value['graph_release_id']) or value['status'] != 'READY' or
                value['domain'] != 'display_reference' or value['vector_release_id'] != vector_release or
                active['release_id'] != vector_release or value['embedding_signature'] != active['embedding_signature']):
            raise ValueError('Graph and vector publication differ')
        return value

    def _collect(self, manifest, roots, limit):
        release = manifest['vector_release_id']
        allowed = {d['id'] for d in self.vector.active()['documents']} - self.vector.withdrawn()
        rows = self.graph.claims(manifest['graph_release_id'], release, roots, sorted(allowed), limit)
        paths, evidence = [], []
        for row in rows[:limit]:
            if row['document_id'] not in allowed:
                continue
            for chunk_id in row['evidence_chunk_ids'][:2]:
                try:
                    hit = self.vector.evidence(release, chunk_id)
                except LookupError:
                    continue
                if (hit['document_id'] != row['document_id'] or hit['document_version'] != row['document_version'] or
                        hit['page_start'] != row['page'] or hit['authority_type'] != 'PUBLIC_RESEARCH' or
                        normalized(row['quote']) not in normalized(hit['text'])):
                    raise ValueError('Graph evidence no longer matches the original source')
                evidence.append(hit)
                paths.append({**{k: v for k, v in row.items() if k != 'evidence_chunk_ids'},
                    'graph_release_id': manifest['graph_release_id'], 'evidence_refs': [hit['evidence_ref']],
                    'authority_type': 'PUBLIC_RESEARCH', 'scope': 'RESEARCH_ONLY', 'review_status': 'SOURCE_VERIFIED'})
                break  # Overlapping chunks support one claim, not independent evidence votes.
        if self.manifest(release)['graph_release_id'] != manifest['graph_release_id']:
            raise ValueError('Graph publication changed while reading')
        rooted = self.rooted(paths, roots)
        refs = {ref for path in rooted for ref in path['evidence_refs']}
        return rooted, [h for h in evidence if h['evidence_ref'] in refs], len(rows) > limit or len(rooted) < len(rows[:limit])

    def _final(self, release, hits, paths, roots):
        if self.vector.active()['release_id'] != release:
            raise Unavailable('Vector publication changed while retrieving; retry')
        withdrawn = self.vector.withdrawn()
        hits = [h for h in hits if h['document_id'] not in withdrawn]
        refs = {h['evidence_ref'] for h in hits}
        paths = [p for p in paths if p['document_id'] not in withdrawn and set(p['evidence_refs']) <= refs]
        return hits, self.rooted(paths, roots)

    def query(self, query, top_k):
        result = getattr(self.vector, 'hybrid_query', self.vector.query)(query, top_k)
        release = result['release_id']
        paths, graph_hits, truncated, roots = [], [], False, []
        graph_status = 'UNAVAILABLE_OR_STALE'
        manifest = None
        try:
            manifest = self.manifest(release)
            roots = resolve_entities(query, manifest['entities'])
            if roots:
                paths, graph_hits, truncated = self._collect(manifest, roots, 8)
                graph_status = 'MATCHES' if paths else 'NO_MATCHES'
            else:
                graph_status = 'NO_ENTITY_MATCH'
        except Exception:
            # Graph failure never invents a path or erases a vector error/release switch.
            graph_status = 'UNAVAILABLE_OR_STALE'
            paths, graph_hits = [], []
        combined, seen = [], set()
        for index in range(max(len(result['hits']), len(graph_hits))):
            for source, pool in [('graph', graph_hits), ('vector', result['hits'])]:
                if index < len(pool):
                    hit = pool[index]
                    if hit['evidence_ref'] not in seen:
                        combined.append({**hit, 'retrieval_source': source})
                        seen.add(hit['evidence_ref'])
        limited = combined[:top_k]
        selected_refs = {h['evidence_ref'] for h in limited}
        truncated = truncated or len(combined) > top_k or any(not set(p['evidence_refs']) <= selected_refs for p in paths)
        before_path_count = len(paths)
        limited, paths = self._final(release, limited, paths, roots)
        truncated = truncated or len(paths) < before_path_count
        surviving_refs = {ref for path in paths for ref in path['evidence_refs']}
        vector_refs = {hit['evidence_ref'] for hit in result['hits']}
        limited = [hit for hit in limited if hit.get('retrieval_source') != 'graph' or
                   hit['evidence_ref'] in surviving_refs or hit['evidence_ref'] in vector_refs]
        if graph_status == 'MATCHES' and not paths:
            graph_status = 'NO_MATCHES'
        # A publication switch after evidence assembly discards all graph additions.
        if paths:
            try:
                if self.manifest(release)['graph_release_id'] != manifest['graph_release_id']:
                    raise ValueError('Changed')
            except Exception:
                limited, paths = self._final(release, result['hits'], [], roots)
                graph_status = 'UNAVAILABLE_OR_STALE'
        return {**result, 'hits': limited, 'status': 'MATCHES' if limited else 'NO_MATCHES',
            'mode_requested': 'graph_assisted', 'mode_used': 'graph_assisted' if paths else 'vector',
            'graph_status': graph_status, 'graph_release_id': manifest['graph_release_id'] if paths else None,
            'resolved_entities': roots, 'graph_paths': paths, 'complete': not truncated and graph_status == 'MATCHES',
            'coverage': 'CURATED_PUBLIC_CLAIMS_ONLY',
            'missing_information': (['GRAPH_UNAVAILABLE_OR_STALE_VECTOR_ONLY'] if graph_status == 'UNAVAILABLE_OR_STALE' else []) +
                                   (['CONTEXT_BUDGET_TRUNCATED'] if truncated else []),
            'limitations': result.get('limitations', []) + [
                'Graph claims are research statements, not production causality or factory acceptance rules.',
                'Two bounded logical claim hops; missing graph links do not prove no relationship exists.']}

    def trace(self, entity_id, max_paths):
        release = self.vector.active()['release_id']
        try:
            manifest = self.manifest(release)
        except Exception as exc:
            raise Unavailable('Graph is unavailable or stale') from exc
        if entity_id not in {e['id'] for e in manifest['entities']}:
            raise ValueError('Unknown public concept')
        try:
            paths, hits, truncated = self._collect(manifest, [entity_id], max_paths)
            before_count = len(paths)
            hits, paths = self._final(release, hits, paths, [entity_id])
            truncated = truncated or len(paths) < before_count
            refs = {ref for path in paths for ref in path['evidence_refs']}
            hits = [hit for hit in hits if hit['evidence_ref'] in refs]
        except Exception as exc:
            raise Unavailable('Graph evidence is unavailable or stale') from exc
        return {'template_id': 'DEFECT_KNOWLEDGE', 'release_id': release,
            'graph_release_id': manifest['graph_release_id'], 'knowledge_domain': 'display_reference',
            'graph_paths': paths, 'hits': list({h['evidence_ref']: h for h in hits}.values()),
            'complete': not truncated, 'truncated': truncated, 'coverage': 'CURATED_PUBLIC_CLAIMS_ONLY'}
