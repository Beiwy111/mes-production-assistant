"""Write and search isolated private indexes using the existing model and database clients."""
import json
import os

from neo4j import GraphDatabase
from qdrant_client import QdrantClient, models

COLLECTION = 'mes_admin_uploads_e5small_614241f6_v1'


def job_filter(ids):
    return models.Filter(must=[models.FieldCondition(key='jobId', match=models.MatchAny(any=ids))])


class PrivateIndexes:
    def __init__(self, vector, graph):
        self.vector = vector
        self.graph = graph

    @classmethod
    def configured(cls, settings):
        writer = os.environ.get('MES_INGEST_QDRANT_API_KEY')
        uri = os.environ.get('MES_INGEST_GRAPH_URI')
        password = os.environ.get('MES_INGEST_GRAPH_PASSWORD')
        graph = GraphDatabase.driver(uri, auth=(os.environ.get('MES_INGEST_GRAPH_USER', 'neo4j'), password),
                                     connection_timeout=4, max_transaction_retry_time=3) if uri and password else None
        vector = QdrantClient(url=settings.qdrant_url, api_key=writer, timeout=30, trust_env=False) if writer else None
        return cls(vector, graph)

    def vectorize(self, job_id, filename, chunks, vectors, dimension):
        if self.vector is None:
            raise RuntimeError('缺少私有向量写入凭据')
        if not self.vector.collection_exists(COLLECTION):
            self.vector.create_collection(COLLECTION, vectors_config=models.VectorParams(
                size=dimension, distance=models.Distance.COSINE))
            self.vector.create_payload_index(COLLECTION, 'jobId', models.PayloadSchemaType.KEYWORD, wait=True)
        self.remove_vectors(job_id)
        for start in range(0, len(chunks), 64):
            batch = [models.PointStruct(id=chunk['id'], vector=vector, payload={
                'jobId': job_id, 'filename': filename, 'chunkId': chunk['id'], 'location': chunk['location'],
                'text': chunk['text'], 'metadata': chunk['metadata']})
                for chunk, vector in zip(chunks[start:start+64], vectors[start:start+64], strict=True)]
            self.vector.upsert(COLLECTION, batch, wait=True)
        if self.vector.count(COLLECTION, count_filter=job_filter([job_id]), exact=True).count != len(chunks):
            raise RuntimeError('向量写入数量校验失败')

    def remove_vectors(self, job_id):
        if self.vector is None:
            raise RuntimeError('缺少私有向量写入凭据')
        if self.vector.collection_exists(COLLECTION):
            self.vector.delete(COLLECTION, points_selector=job_filter([job_id]), wait=True)

    def write_graph(self, job_id, filename, chunks, entities, relations):
        if self.graph is None:
            raise RuntimeError('请配置可写的私有 Neo4j 实例')
        with self.graph.session(database='neo4j') as session:
            session.run('CREATE CONSTRAINT upload_node_id IF NOT EXISTS '
                        'FOR (n:UploadNode) REQUIRE n.id IS UNIQUE').consume()
            with session.begin_transaction(timeout=90) as tx:
                tx.run('MATCH (n:UploadNode {jobId:$j}) DETACH DELETE n', j=job_id).consume()
                tx.run('CREATE (:UploadNode:UploadDocument {id:$j,jobId:$j,name:$name})',
                       j=job_id, name=filename).consume()
                records = [{'id': c['id'], 'location': c['location'], 'text': c['text'],
                            'metadata': json.dumps(c['metadata'], ensure_ascii=False)} for c in chunks]
                tx.run('MATCH (d:UploadNode {id:$j}) UNWIND $rows AS row '
                       'CREATE (c:UploadNode:UploadChunk {id:row.id,jobId:$j}) SET c += row '
                       'CREATE (d)-[:HAS_CHUNK]->(c)', j=job_id, rows=records).consume()
                for start in range(0, len(entities), 256):
                    tx.run('UNWIND $rows AS row CREATE (e:UploadNode:UploadEntity {id:row.id,jobId:$j,name:row.name,reviewStatus:row.reviewStatus}) '
                           'WITH row,e UNWIND row.chunkIds AS cid MATCH (c:UploadNode {id:cid}) '
                           'CREATE (e)-[:MENTIONED_IN]->(c)', j=job_id, rows=entities[start:start+256]).consume()
                for start in range(0, len(relations), 256):
                    tx.run('UNWIND $rows AS row MATCH (a:UploadNode {id:row.source}), (b:UploadNode {id:row.target}) '
                           'CREATE (a)-[:EVIDENCED_RELATION {kind:row.type,chunkId:row.chunkId,quote:row.quote,'
                           'reviewStatus:row.reviewStatus,extractionMethod:row.extractionMethod}]->(b)',
                           rows=relations[start:start+256]).consume()
                tx.commit()

    def remove_graph(self, job_id):
        if self.graph is None:
            raise RuntimeError('请配置可写的私有 Neo4j 实例')
        with self.graph.session(database='neo4j') as session:
            session.run('MATCH (n:UploadNode {jobId:$j}) DETACH DELETE n', j=job_id).consume()

    def search(self, embedding, query, job_ids, limit):
        if not job_ids:
            return {'hits': [], 'entities': [], 'relations': []}
        points = self.vector.query_points(COLLECTION, query=embedding.query(query), query_filter=job_filter(job_ids),
                                          limit=limit, with_payload=True).points
        hits = [{**point.payload, 'score': point.score} for point in points]
        ids = [h['chunkId'] for h in hits]
        with self.graph.session(database='neo4j') as session:
            entities = session.run('UNWIND $ids AS id MATCH (c:UploadNode {id:id})<-[:MENTIONED_IN]-(e:UploadEntity) '
                                   'RETURN e.id AS id,e.name AS name,e.jobId AS jobId,e.reviewStatus AS reviewStatus,'
                                   'collect(DISTINCT c.id) AS chunkIds',
                                   ids=ids).data()
            relations = session.run('UNWIND $ids AS id MATCH (c:UploadNode {id:id})<-[:MENTIONED_IN]-(a:UploadEntity)'
                                    '-[r:EVIDENCED_RELATION]->(b:UploadEntity) WHERE r.chunkId=id '
                                    'RETURN a.id AS source,b.id AS target,r.kind AS type,r.quote AS quote,'
                                    'r.chunkId AS chunkId,a.jobId AS jobId,r.reviewStatus AS reviewStatus,'
                                    'r.extractionMethod AS extractionMethod', ids=ids).data()
        return {'hits': hits, 'entities': entities, 'relations': relations}

    def close(self):
        if self.vector is not None:
            self.vector.close()
        if self.graph is not None:
            self.graph.close()
