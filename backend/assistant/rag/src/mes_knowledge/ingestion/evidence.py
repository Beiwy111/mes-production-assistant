"""Deterministic extraction. Only literal mentions in a chunk can support a relationship."""
import re
import uuid
import json
from functools import lru_cache

from ..config import RAG_ROOT


def identifier(job_id, kind, key):
    return str(uuid.uuid5(uuid.UUID(job_id), f'{kind}:{key}'))


def split_chunks(job_id, sections, counter):
    chunks = []
    for index, section in enumerate(sections):
        text = section['text']
        offsets = counter.encode(text, add_special_tokens=False).offsets
        for start in range(0, len(offsets), 288):
            end = min(start + 320, len(offsets))
            low, high = offsets[start][0], offsets[end - 1][1]
            chunks.append({'id': identifier(job_id, 'chunk', f'{index}:{low}:{high}'),
                           'text': text[low:high], 'location': section['location'],
                           'metadata': {**section['metadata'], 'charStart': low, 'charEnd': high}})
            if len(chunks) > 2048:
                raise ValueError('单文档超过 2048 个片段')
            if end == len(offsets):
                break
    if not chunks:
        raise ValueError('未生成可向量化的片段')
    return chunks


TERM = re.compile(r'(?<![A-Za-z0-9])[A-Za-z][A-Za-z0-9]*(?:[-_][A-Za-z0-9]+)*(?![A-Za-z0-9])')
CJK = re.compile(r'[\u3400-\u9fff]+')
NOT_IDENTIFIERS = set('THE FOR AND FIG VOL DOI ISSN ISBN WWW HTTP HTTPS THIS THAT TABLE PAGE FIGURE '
                      'IN AT ON OF IS TO BE BY AS OR IF ET AL II III IV VI VII VIII IX XI XII WHERE '
                      'PEER REVIEW USA MDPI CCBY AN CC BY ABSTRACT INTRODUCTION REFERENCES'.split())
CODE = re.compile(r'(?:[A-Z]{2,10}(?:v)?\d{1,10}(?:[-_][A-Z\d]+)?|[A-Z]{1,10}[-_][A-Z\d-]*\d[A-Z\d-]*)$')
MACHINE_TERMS = (
    'piecewise gamma correction|adaptive gamma correction|gamma correction|gamma|'
    'automated optical inspection|optical inspection|optical density|optical contrast|'
    'liquid crystal display|low-contrast image|luminance nonuniformity|brightness uniformity|defect detection|'
    'mura defect|color filter|brightness|luminance|crosstalk|machine learning|deep learning|'
    'convolutional neural network|particle swarm optimization|fourier transform|image sensor|'
    'pressure sensor|temperature sensor|temperature controller|cooling chamber|coolant pump|'
    'hydraulic pump|relief valve|inlet valve|outlet valve|control valve|valve|pressure|'
    'filter|pump|bearing|sensor|actuator|conveyor|motor|encoder|spindle|controller|compressor|'
    'lubricant|gearbox|torque|overheating|vibration|coolant|temperature|inspection station|'
    '热压机|冲压机|传感器|伺服电机|进料阀|调压阀|刀具磨损|切削温度|质量检验|设备维护'
)
PREDICATE = re.compile(r'导致|引起|用于|依赖|包含|影响|产生|需要|使用|\b(?:causes|requires|contains|produces|affects|uses)\b', re.I)
UNCERTAIN = re.compile(r'不|未|无|否|可能|或许|也许|有望|推测|假设|疑似|据称|如果|能否|能不能|'
                       r'\b(?:no|not|never|without|may|might|could|possibly|potentially|unlikely|whether|if|suspected)\b', re.I)
GENERIC_NOUNS = {'情况', '问题', '方面', '内容', '人员', '时间', '方式', '过程', '方法', '结果', '部分',
                 '我们', '本文', '研究', '工作', '东西', '说明', '文档', '文件', '示例', '条件', '原因'}


@lru_cache(maxsize=1)
def glossary():
    names = set(MACHINE_TERMS.split('|'))
    path = RAG_ROOT / 'corpus' / 'research-claims.json'
    if path.is_file():
        for concept in json.loads(path.read_text(encoding='utf-8'))['entities']:
            names.update([concept['name'], *concept['aliases']])
    patterns = []
    for name in sorted(names, key=len, reverse=True):
        prefix = r'(?<![a-zA-Z0-9])' if name[0].isascii() else ''
        suffix = r'(?:s)?(?![a-zA-Z0-9])' if name[-1].isascii() else ''
        patterns.append(prefix + re.escape(name) + suffix)
    return re.compile('|'.join(patterns), re.I)


def terms(sentence):
    import jieba.posseg as posseg
    found = [(m.start(), m.end(), m[0]) for m in glossary().finditer(sentence)]
    for match in TERM.finditer(sentence):
        word = match[0]
        acronym = re.fullmatch(r'[A-Z]{2,8}(?:-[A-Z]{2,8})?', word)
        if word.upper() not in NOT_IDENTIFIERS and (acronym or CODE.fullmatch(word)):
            found.append((match.start(), match.end(), word))
    for run in CJK.finditer(sentence):
        start = run.start()
        for word, tag in posseg.cut(run[0]):
            if word not in GENERIC_NOUNS and 2 <= len(word) <= 32 and (tag.startswith('n') or tag == 'vn'):
                found.append((start, start + len(word), word))
            start += len(word)
    distinct = []
    for term in sorted(found, key=lambda item: (item[0], -item[1])):
        if not distinct or term[0] >= distinct[-1][1]:
            distinct.append(term)
    return distinct


def extract_entities(job_id, chunks):
    entities, evidence = {}, []
    for chunk in chunks:
        for match in re.finditer(r'[^。！？!?\n.;；]+', chunk['text']):
            quote = match[0].strip()
            mentions = terms(quote)
            if not mentions:
                continue
            for _, _, name in mentions:
                key = name.casefold()
                if key not in entities:
                    entities[key] = {'id': identifier(job_id, 'entity', key), 'name': name,
                                     'jobId': job_id, 'chunkIds': [], 'reviewStatus': 'UNREVIEWED'}
                citations = entities[key]['chunkIds']
                if not citations or citations[-1] != chunk['id']:
                    citations.append(chunk['id'])
            evidence.append((chunk['id'], quote, mentions))
    return list(entities.values()), evidence


def extract_relations(job_id, evidence):
    relations = []
    for chunk_id, quote, mentions in evidence:
        described = []
        for predicate in (() if UNCERTAIN.search(quote) else PREDICATE.finditer(quote)):
            left = next((term for term in reversed(mentions) if term[1] <= predicate.start()), None)
            right = next((term for term in mentions if term[0] >= predicate.end()), None)
            if left and right:
                described.append((left[2], right[2], predicate[0]))
        # A co-mention represents source co-occurrence and makes no causal claim.
        if not described:
            names = list(dict.fromkeys(item[2] for item in mentions))[:6]
            described = [(a, b, 'CO_OCCURS') for a, b in zip(names, names[1:])]
        for source, target, relation in dict.fromkeys(described):
            if source.casefold() == target.casefold():
                continue
            relations.append({'source': identifier(job_id, 'entity', source.casefold()),
                              'target': identifier(job_id, 'entity', target.casefold()), 'type': relation,
                              'chunkId': chunk_id, 'quote': quote, 'jobId': job_id,
                              'reviewStatus': 'UNREVIEWED',
                              'extractionMethod': 'co-occurrence' if relation == 'CO_OCCURS' else 'rule-candidate'})
    return relations
