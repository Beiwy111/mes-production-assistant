"""python -m mes_knowledge.ragflow --help"""
import argparse
import json
from pathlib import Path

from dotenv import dotenv_values

from ..config import RAG_ROOT
from ..indexing import atomic_json
from .client import RagflowClient, RagflowConfig, RagflowError
from .drafts import review_template, stage_reviewed, validate_review
from .workflow import archived_corpus, comparison, export_drafts, ingest_drafts, read_json


def main():
    parser = argparse.ArgumentParser(description='RAGFlow parsing drafts; never activates a release')
    parser.add_argument('--registry', type=Path, default=RAG_ROOT / 'corpus' / 'papers.json')
    parser.add_argument('--data-dir', type=Path, default=RAG_ROOT / 'data')
    parser.add_argument('--config', type=Path, default=RAG_ROOT.parent / 'deploy' / '.env.ragflow.local')
    sub = parser.add_subparsers(dest='command', required=True)
    ingest = sub.add_parser('ingest', help='Upload registered originals, start parse, export a draft')
    ingest.add_argument('--out', required=True, type=Path)
    export = sub.add_parser('export', help='Resume polling/export using a saved upload receipt')
    export.add_argument('--receipt', required=True, type=Path)
    export.add_argument('--out', required=True, type=Path)
    retry = sub.add_parser('parse', help='Explicitly start/restart parsing a saved document; no automatic retries')
    retry.add_argument('--document-id', required=True)
    compare = sub.add_parser('compare', help='Offline comparison; omitting draft records NOT_PARSED')
    compare.add_argument('--draft', type=Path)
    compare.add_argument('--out', required=True, type=Path)
    template = sub.add_parser('review-template')
    template.add_argument('--draft', required=True, type=Path)
    template.add_argument('--out', required=True, type=Path)
    for command in ('validate-review', 'stage'):
        command_parser = sub.add_parser(command)
        command_parser.add_argument('--draft', required=True, type=Path)
        command_parser.add_argument('--review', required=True, type=Path)
        if command == 'stage':
            command_parser.add_argument('--confirm-reviewed-stage', action='store_true', required=True)
            command_parser.add_argument('--knowledge-config', type=Path,
                default=RAG_ROOT.parent / 'deploy' / '.env.knowledge.local')
            command_parser.add_argument('--writer-config', type=Path,
                default=RAG_ROOT.parent / 'deploy' / '.env.qdrant.local')
    args = parser.parse_args()
    try:
        originals = archived_corpus(args.registry, args.data_dir)
        if args.command in ('ingest', 'export', 'parse'):
            values = dotenv_values(args.config, interpolate=False)
            config_values = {k.removeprefix('MES_RAGFLOW_').lower(): v for k, v in values.items()
                             if k.startswith('MES_RAGFLOW_')}
            if 'allowed_hosts' in config_values:
                config_values['allowed_hosts'] = json.loads(config_values['allowed_hosts'])
            settings = RagflowConfig(**config_values)
            with RagflowClient(settings) as client:
                if args.command == 'parse':
                    client.parse(args.document_id)
                    result = {'status': 'PARSE_REQUEST_ACCEPTED'}
                else:
                    draft = (ingest_drafts(originals, client, args.out) if args.command == 'ingest' else
                             export_drafts(originals, client, read_json(args.receipt), args.out))
                    result = {'status': 'DRAFT_EXPORTED', 'documents': len(draft['documents'])}
        elif args.command == 'compare':
            tokenizer = args.data_dir / 'model' / 'tokenizer.json'
            result = comparison(originals, read_json(args.draft) if args.draft else None,
                                tokenizer if tokenizer.exists() else None)
            args.out.parent.mkdir(parents=True, exist_ok=True)
            atomic_json(args.out, result)
        elif args.command == 'review-template':
            if args.out.exists():
                raise ValueError('Review template destination exists')
            args.out.parent.mkdir(parents=True, exist_ok=True)
            atomic_json(args.out, review_template(read_json(args.draft)))
            result = {'status': 'UNREVIEWED_TEMPLATE_CREATED'}
        else:
            draft, review = read_json(args.draft), read_json(args.review)
            chunks, _ = validate_review(draft, review, originals)
            result = {'status': 'REVIEW_VALIDATED', 'chunks': len(chunks)}
            if args.command == 'stage':
                from ..cli import settings_from_file
                from ..embedding import E5Embedding
                from qdrant_client import QdrantClient
                settings = settings_from_file(args.knowledge_config)
                if settings.data_dir.resolve() != args.data_dir.resolve():
                    raise ValueError('Knowledge config and source data directory differ')
                writer = dotenv_values(args.writer_config, interpolate=False).get('QDRANT__SERVICE__API_KEY')
                if not writer:
                    raise ValueError('Private Qdrant writer credential required')
                with QdrantClient(url=settings.qdrant_url, api_key=writer, timeout=30, trust_env=False) as client:
                    result = stage_reviewed(draft, review, originals, client,
                        E5Embedding(args.data_dir / 'model', settings.threads), args.data_dir)
        print(json.dumps(result, ensure_ascii=False, indent=2))
    except RagflowError as error:
        parser.exit(1, str(error) + '\n')
    except Exception:
        # HTTP/config/model exceptions may embed connection strings or credentials. No traceback in operator CLI.
        parser.exit(1, 'RAGFlow workflow failed validation or local/backend operation; no release was activated.\n')


if __name__ == '__main__':
    main()
