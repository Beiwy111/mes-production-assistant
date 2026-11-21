"""Deterministic local BM25 + reciprocal rank fusion; no new model/service required."""
import math
import re
from collections import Counter


def terms(text):
    result = re.findall(r'[a-z0-9_]+', text.lower())
    for segment in re.findall(r'[\u3400-\u9fff]+', text):
        result.extend(segment[i:i+2] for i in range(max(1, len(segment)-1)))
    return result


def bm25_rank(query, documents, limit=24):
    words = set(terms(query))
    frequencies = [Counter(terms(doc.get('title', '') + ' ' + doc['text'])) for doc in documents]
    lengths = [sum(counts.values()) for counts in frequencies]
    average = sum(lengths) / max(1, len(lengths)) or 1
    occurrence = {word: sum(word in counts for counts in frequencies) for word in words}
    scores = []
    for doc, counts, length in zip(documents, frequencies, lengths):
        score = 0
        for word in words:
            freq = counts[word]
            if freq:
                idf = math.log(1 + (len(documents)-occurrence[word]+.5)/(occurrence[word]+.5))
                score += idf * freq * 2.2 / (freq + 1.2*(.25+.75*length/average))
        if score > 0:
            scores.append((doc['chunk_id'], score))
    return sorted(scores, key=lambda item: (-item[1], item[0]))[:limit]


def reciprocal_rank_fusion(vector_ids, keyword_ids, limit, constant=60):
    scores = {}
    for ranking in (vector_ids, keyword_ids):
        for rank, identifier in enumerate(dict.fromkeys(ranking), 1):
            scores[identifier] = scores.get(identifier, 0) + 1/(constant+rank)
    return sorted(scores, key=lambda key: (-scores[key], key))[:limit]
