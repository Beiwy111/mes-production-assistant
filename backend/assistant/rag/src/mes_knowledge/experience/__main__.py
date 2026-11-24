"""Maintenance entry point. Prints a count-only receipt and never prints private source text."""
import argparse
import json
import sys

from dotenv import dotenv_values
from qdrant_client import QdrantClient

from ..cli import settings_from_file
from ..config import RAG_ROOT
from ..embedding import E5Embedding
from ..graph.config import graph_settings, open_graph
from .graph import ExperienceGraph
from .index import synchronize
from .source import MySQLHistory


def main():
    parser = argparse.ArgumentParser(description='Synchronize private MES experience during graph maintenance')
    parser.add_argument('command', choices=['sync'])
    parser.parse_args()
    deploy = RAG_ROOT.parent / 'deploy'
    settings = settings_from_file(deploy / '.env.knowledge.local')
    writer = dotenv_values(deploy / '.env.qdrant.local', interpolate=False)['QDRANT__SERVICE__API_KEY']
    graph = open_graph(graph_settings())
    try:
        if graph.readonly():
            raise RuntimeError('Use Update-GraphLocal.ps1 -Kind Experience for managed graph maintenance')
        client = QdrantClient(url=settings.qdrant_url, api_key=writer, timeout=30, trust_env=False)
        try:
            receipt = synchronize(settings.data_dir / 'private-experience', MySQLHistory(), client,
                                  E5Embedding(settings.data_dir / 'model', settings.threads), ExperienceGraph(graph))
            print(json.dumps(receipt))
        finally:
            client.close()
    finally:
        graph.close()


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        print('History sync failed (' + type(error).__name__ + '). Check local database/index configuration.',
              file=sys.stderr)
        sys.exit(1)
