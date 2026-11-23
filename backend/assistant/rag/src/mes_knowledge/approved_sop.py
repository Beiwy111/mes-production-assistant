"""Operator-only publication of approved factory-wide SOPs to the local assistant."""
import argparse
from datetime import date
import hashlib
import json
from pathlib import Path
import re

from filelock import FileLock
from .config import RAG_ROOT
from .documents import parse_document
from .indexing import atomic_json

ROOT = RAG_ROOT / 'data/approved-sop'
DOMAINS = {'PRODUCTION','PROCESS_ROUTE','QUALITY','EQUIPMENT','MAINTENANCE','ALARMS','INVENTORY','AFTERSALES','ORDER'}
ID = re.compile(r'[A-Z][A-Z0-9_-]{1,60}')
VERSION = re.compile(r'[a-zA-Z0-9][a-zA-Z0-9_.-]{0,39}')


def load(root):
    path = root/'active.json'
    if not path.exists():
        return {'schema':1,'documents':{}}
    value = json.loads(path.read_text('utf-8'))
    if value.get('schema') != 1 or not isinstance(value.get('documents'),dict):
        raise ValueError('Invalid active SOP catalog')
    return value


def publish(root, file, review):
    expected = {'document_id','title','version','allow_model_processing','effective_from','valid_to','domain',
                'approved_by','approved_on','allowed_roles','status','scope','sha256','keywords'}
    if set(review) != expected:
        raise ValueError('Incomplete or unknown review fields')
    if (not ID.fullmatch(review['document_id']) or not VERSION.fullmatch(review['version'])
        or review['status'] != 'APPROVED' or review['scope'] != 'FACTORY_WIDE'
        or review['allow_model_processing'] is not True or review['domain'] not in DOMAINS
        or not 1 <= len(review['approved_by'].strip()) <= 80 or not 1 <= len(review['title'].strip()) <= 180
        or not isinstance(review['allowed_roles'],list) or not 1 <= len(review['allowed_roles']) <= 32
        or any(not re.fullmatch(r'[A-Z_]{2,40}', r) or r=='CUSTOMER' for r in review['allowed_roles'])
        or not isinstance(review['keywords'],list) or not 1 <= len(review['keywords']) <= 32
        or any(not isinstance(w,str) or not 2 <= len(w.strip()) <= 80 for w in review['keywords'])):
        raise ValueError('Expected approved, role-limited, model-permitted factory-wide review')
    effective = date.fromisoformat(review['effective_from'])
    if date.fromisoformat(review['approved_on']) > date.today() or (review['valid_to'] is not None and
            date.fromisoformat(review['valid_to']) < effective):
        raise ValueError('Invalid approval or effectivity dates')
    if file.suffix.lower() not in {'.pdf','.docx','.txt','.md'} or file.stat().st_size > 4*1024*1024:
        raise ValueError('Expected a text PDF, DOCX or UTF-8 document under 4 MiB')
    raw = file.read_bytes()
    if hashlib.sha256(raw).hexdigest() != review['sha256']:
        raise ValueError('File changed since review')
    sections = []
    for page in parse_document(file.name, raw)['sections']:
        text = page['text'].strip()
        for offset in range(0,len(text),840):
            ref = f"sop:{review['document_id']}:{review['version']}:{review['sha256'][:16]}:{len(sections)+1}"
            sections.append({'location':f"{page['location']} · {offset+1}–{min(offset+900,len(text))}",
                             'evidence_ref':ref,'text':text[offset:offset+900]})
            if offset+900 >= len(text):
                break
    doc = {**review,'sections':sections}
    root.mkdir(parents=True,exist_ok=True)
    with FileLock(str(root/'.publish.lock'),timeout=10):
        data = load(root)
        current = data['documents'].get(doc['document_id'])
        if current == doc:
            return
        archive = root/'versions'/doc['document_id']
        archive.mkdir(parents=True,exist_ok=True)
        revision = archive/(doc['version']+'.json')
        identity = doc['document_id'] + ':' + doc['version']
        published = data.setdefault('published_versions',[])
        if identity in published or revision.exists() and json.loads(revision.read_text('utf-8')) != doc:
            raise ValueError('Version already registered; review a new version')
        data['documents'][doc['document_id']] = doc
        published.append(identity)
        if len(json.dumps(data,ensure_ascii=False,indent=2).encode('utf-8')) > 10*1024*1024:
            raise ValueError('Local SOP publication exceeds the catalog budget')
        # A crash before publishing the pointer leaves an identical uncommitted revision safe to retry.
        if not revision.exists():
            atomic_json(revision, doc)
        atomic_json(root/'active.json',data)


def withdraw(root, document_id, version):
    with FileLock(str(root/'.publish.lock'),timeout=10):
        data = load(root)
        if data['documents'].get(document_id,{}).get('version') != version:
            raise ValueError('Requested version is not active')
        del data['documents'][document_id]
        atomic_json(root/'active.json',data)


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    commands=parser.add_subparsers(dest='command',required=True)
    add=commands.add_parser('publish')
    add.add_argument('file',type=Path)
    add.add_argument('review',type=Path)
    revoke=commands.add_parser('withdraw')
    revoke.add_argument('document_id')
    revoke.add_argument('version')
    args=parser.parse_args()
    if args.command=='publish':
        publish(ROOT,args.file,json.loads(args.review.read_text('utf-8-sig')))
    else:
        withdraw(ROOT,args.document_id,args.version)
    print('SOP catalog updated')


if __name__=='__main__':
    main()

