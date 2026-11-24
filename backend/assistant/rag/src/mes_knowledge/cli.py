"""Explicit commands; no import-time network calls or database mutations."""
import argparse
import hashlib
import json
from pathlib import Path

from dotenv import dotenv_values
from qdrant_client import QdrantClient

from .config import Settings, RAG_ROOT
from .corpus import load_corpus
from .embedding import E5Embedding, prepare_model
from .indexing import ingest, withdraw_document
from .retrieval import KnowledgeStore


def settings_from_file(path):
    values = dotenv_values(path, interpolate=False)
    # This CLI treats config as data; no sourcing, interpolation or shell evaluation.
    allowed = {key.removeprefix('MES_KNOWLEDGE_').lower(): value for key, value in values.items()
               if key.startswith('MES_KNOWLEDGE_')}
    return Settings(**allowed)


def open_store(settings):
    client = QdrantClient(url=settings.qdrant_url, api_key=settings.qdrant_read_key.get_secret_value(), timeout=15,
                          trust_env=False)
    return KnowledgeStore(settings.data_dir, client, E5Embedding(settings.data_dir / 'model', settings.threads))


def main():
    parser = argparse.ArgumentParser(description='MES public-reference retrieval')
    parser.add_argument('--config', type=Path, default=RAG_ROOT.parent / 'deploy' / '.env.knowledge.local')
    sub = parser.add_subparsers(dest='command', required=True)
    sub.add_parser('prepare-model')
    graph_parser = sub.add_parser('graph-build')
    graph_parser.add_argument('--claims', type=Path, default=RAG_ROOT / 'corpus/research-claims.json')
    graph_trace = sub.add_parser('graph-trace')
    graph_trace.add_argument('entity_id')
    business_sync = sub.add_parser('business-sync')
    business_sync.add_argument('--export-file', type=Path, required=True)
    business_sync.add_argument('--expected-order', required=True)
    source = business_sync.add_mutually_exclusive_group(required=True)
    source.add_argument('--synthetic', action='store_true')
    source.add_argument('--maintenance-export', action='store_true')
    business_trace = sub.add_parser('business-trace')
    business_trace.add_argument('--order', required=True)
    ingest_parser = sub.add_parser('ingest')
    ingest_parser.add_argument('--registry', type=Path, default=RAG_ROOT / 'corpus' / 'papers.json')
    ingest_parser.add_argument('--source-root', required=True, type=Path)
    ingest_parser.add_argument('--writer-config', type=Path, default=RAG_ROOT.parent / 'deploy' / '.env.qdrant.local')
    query_parser = sub.add_parser('query')
    query_parser.add_argument('query')
    query_parser.add_argument('--top-k', type=int, default=5, choices=range(1, 9))
    query_parser.add_argument('--mode', choices=['vector', 'graph_assisted'], default='vector')
    withdraw_parser = sub.add_parser('withdraw')
    withdraw_parser.add_argument('document_id')
    serve_parser = sub.add_parser('serve')
    serve_parser.add_argument('--port', type=int, default=18081)
    args = parser.parse_args()
    settings = settings_from_file(args.config)
    if args.command == 'prepare-model':
        metadata = prepare_model(settings.data_dir / 'model')
        print(json.dumps({'name': metadata['name'], 'revision': metadata['revision']}))
    elif args.command == 'ingest':
        documents = load_corpus(args.registry, args.source_root)
        writer = dotenv_values(args.writer_config, interpolate=False).get('QDRANT__SERVICE__API_KEY')
        if not writer:
            raise ValueError('Missing writer credential in private writer configuration')
        client = QdrantClient(url=settings.qdrant_url, api_key=writer, timeout=30, trust_env=False)
        embedder = E5Embedding(settings.data_dir / 'model', settings.threads)
        result = ingest(documents, client, embedder, settings.data_dir, settings.data_dir / 'model' / 'tokenizer.json')
        print(json.dumps(result, ensure_ascii=False, indent=2))
    elif args.command == 'withdraw':
        withdraw_document(settings.data_dir, args.document_id)
        print('Document withdrawn from query and evidence endpoints; raw sources preserved.')
    elif args.command in ('business-sync', 'business-trace'):
        from .graph.config import graph_settings
        from .graph.business_store import BusinessGraphStore
        from .graph.business import sync_business, trace_business
        config = graph_settings()
        if config.password is None:
            raise ValueError('Private graph credential required')
        graph = BusinessGraphStore(config.uri, config.user, config.password.get_secret_value())
        try:
            if args.command == 'business-sync':
                if args.export_file.stat().st_size > 10 * 1024 * 1024:
                    raise ValueError('Export exceeds maintenance file budget')
                data = json.loads(args.export_file.read_text(encoding='utf-8'))
                result = sync_business(settings.data_dir, graph, data, args.expected_order, synthetic=args.synthetic)
                # Print a receipt only, not source business records.
                result = {k:v for k,v in result.items() if k != 'missing_links'}
            else:
                result = trace_business(settings.data_dir, graph, args.order)
            print(json.dumps(result, ensure_ascii=False, indent=2))
        finally:
            graph.close()
    elif args.command in ('graph-build', 'graph-trace'):
        from .graph.config import graph_settings, open_graph
        from .graph.schema import prepare_graph
        from .graph.publish import collect_chunks, publish_graph
        from .graph.search import GraphSearch
        graph = open_graph(graph_settings())
        try:
            vector = open_store(settings)
            if args.command == 'graph-build':
                manifest = vector.active()
                raw_claims = args.claims.read_bytes()
                catalog = json.loads(raw_claims)
                bundle = prepare_graph(catalog, manifest, collect_chunks(vector, manifest))
                result = publish_graph(vector, graph, bundle, manifest, claims_hash=hashlib.sha256(raw_claims).hexdigest())
            else:
                result = GraphSearch(vector, graph).trace(args.entity_id, 8)
            print(json.dumps(result, ensure_ascii=False, indent=2))
        finally:
            graph.close()
    elif args.command == 'query':
        vector = open_store(settings)
        if args.mode == 'graph_assisted':
            from .graph.config import graph_settings, open_graph
            from .graph.search import GraphSearch
            config = graph_settings()
            graph = open_graph(config) if config.enabled else None
            try:
                result = GraphSearch(vector, graph).query(args.query, args.top_k)
            finally:
                if graph is not None:
                    graph.close()
        else:
            result = vector.query(args.query, args.top_k)
        print(json.dumps(result, ensure_ascii=False, indent=2))
    elif args.command == 'serve':
        import uvicorn
        from .api import create_app
        from .graph.config import graph_settings, open_graph
        graph_config = graph_settings()
        graph = open_graph(graph_config) if graph_config.enabled else None
        import os
        vector = open_store(settings)
        experience_key = os.environ.get('MES_KNOWLEDGE_EXPERIENCE_API_KEY')
        experience = None
        if experience_key:
            from .experience import ExperienceSearch
            experience = ExperienceSearch.from_store(vector, graph)
        ingestion_key = os.environ.get('MES_KNOWLEDGE_INGESTION_API_KEY')
        ingestion = None
        if ingestion_key:
            from .ingestion.jobs import IngestionJobs
            from .ingestion.indexes import PrivateIndexes
            ingestion = IngestionJobs(settings.data_dir / 'private-ingestion', vector.embedding,
                                      PrivateIndexes.configured(settings))
        app = create_app(vector, settings.api_key.get_secret_value(), graph,
                         experience=experience, experience_key=experience_key,
                         ingestion=ingestion, ingestion_key=ingestion_key)
        try:
            uvicorn.run(app, host='127.0.0.1', port=args.port, access_log=False)
        finally:
            if graph is not None:
                graph.close()


if __name__ == '__main__':
    main()
