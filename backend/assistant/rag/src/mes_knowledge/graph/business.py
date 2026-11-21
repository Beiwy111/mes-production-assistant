"""Maintenance-only order snapshots, isolated from the public knowledge API."""
import hashlib
import json
import math
import re
import uuid
from datetime import datetime, timezone

from filelock import FileLock
from ..indexing import atomic_json

NODE_FIELDS = {
    'Order': ('customer_order', {'status'}),
    'OrderItem': ('customer_order_item', {'status', 'quantity'}),
    'Plan': ('production_plan', {'status'}),
    'WorkOrder': ('work_order', {'status', 'planned_quantity', 'completed_quantity'}),
    'Inspection': ('quality_inspection', {'status', 'sample_quantity', 'qualified_quantity', 'unqualified_quantity'}),
}
EDGE_FIELDS = {
    'HAS_ITEM': ('Order', 'OrderItem', 'customer_order_item'),
    'PLANNED_AS': ('OrderItem', 'Plan', 'production_plan_item'),
    'HAS_WORK_ORDER': ('Plan', 'WorkOrder', 'work_order'),
    'HAS_INSPECTION': ('WorkOrder', 'Inspection', 'quality_inspection'),
}


def order_id(value):
    if not isinstance(value, str) or not re.fullmatch(r'[1-9][0-9]{0,18}', value) or int(value) > 2**63-1:
        raise ValueError('Invalid source primary key')
    return value


def validate_export(data, expected_order):
    expected_order = order_id(expected_order)
    if (set(data) != {'schemaVersion', 'exportId', 'scope', 'sourceObservedAt', 'readFinishedAt',
                     'complete', 'nodes', 'edges', 'missingLinks'} or data['schemaVersion'] != '1' or
            data['scope'] != {'orderPk': expected_order} or data['complete'] is not True):
        raise ValueError('Unbounded, incomplete or out-of-scope maintenance export')
    uuid.UUID(data['exportId'])
    start, end = [datetime.fromisoformat(data[k]) for k in ('sourceObservedAt', 'readFinishedAt')]
    if start.tzinfo is None or end.tzinfo is None or end < start:
        raise ValueError('Invalid source observation window')
    if not 1 <= len(data['nodes']) <= 5000 or len(data['edges']) > 5000 or len(data['missingLinks']) > 5000:
        raise ValueError('Export exceeds maintenance budget')
    nodes = {}
    for node in data['nodes']:
        if set(node) != {'id', 'kind', 'source_table', 'source_id', 'properties'} or node['kind'] not in NODE_FIELDS:
            raise ValueError('Unsupported graph node')
        pk = order_id(node['source_id'])
        table, allowed = NODE_FIELDS[node['kind']]
        if (node['id'] != node['kind']+':'+pk or node['id'] in nodes or node['source_table'] != table or
                not isinstance(node['properties'], dict) or not set(node['properties']) <= allowed):
            raise ValueError('Invalid source identity or private/unsupported property')
        if node['kind'] == 'Order' and pk != expected_order:
            raise ValueError('Cross-order source record')
        for name, value in node['properties'].items():
            if name == 'status':
                if not isinstance(value, str) or not re.fullmatch(r'[A-Z_]{1,32}', value):
                    raise ValueError('Invalid status code')
            elif (type(value) not in (int, float) or not math.isfinite(value) or value < 0):
                raise ValueError('Invalid recorded quantity')
        nodes[node['id']] = node
    if 'Order:'+expected_order not in nodes:
        raise ValueError('Missing authorized root')
    seen = set()
    for edge in data['edges']:
        if (set(edge) != {'id', 'source', 'target', 'type', 'source_table', 'source_id', 'reliability'} or
                edge['type'] not in EDGE_FIELDS or edge['reliability'] != 'CONFIRMED' or edge['id'] in seen or
                edge['source'] not in nodes or edge['target'] not in nodes):
            raise ValueError('Unsupported, duplicate or dangling graph edge')
        source_kind, target_kind, table = EDGE_FIELDS[edge['type']]
        pk = order_id(edge['source_id'])
        if (nodes[edge['source']]['kind'] != source_kind or nodes[edge['target']]['kind'] != target_kind or
                edge['source_table'] != table or edge['id'] != edge['type']+':'+table+':'+pk or
                (edge['type'] != 'PLANNED_AS' and nodes[edge['target']]['source_id'] != pk)):
            raise ValueError('Invalid relationship provenance')
        seen.add(edge['id'])
    for missing in data['missingLinks']:
        if (set(missing) != {'source', 'type', 'reason'} or missing['source'] not in nodes or
                any(not isinstance(missing[k], str) or not re.fullmatch(r'[A-Z_a-z]{1,80}', missing[k])
                    for k in ('type', 'reason'))):
            raise ValueError('Invalid missing-link diagnostic')
    return data


def _path(data_dir, order):
    return data_dir / 'business-graphs' / (order_id(order) + '.json')


def sync_business(data_dir, repository, packet, expected_order, *, synthetic):
    order_id(expected_order)
    target = _path(data_dir, expected_order)
    target.parent.mkdir(exist_ok=True)
    domain = 'business_demo' if synthetic else 'business_reference'
    with FileLock(str(data_dir / '.graph-publish.lock'), timeout=1):
        old = json.loads(target.read_text(encoding='utf-8')) if target.exists() else {}
        # The maintenance operator selected this order explicitly. A rejected replacement
        # invalidates its old ready marker as well; it must not look like a fresh snapshot.
        atomic_json(target, {**old, 'status':'SYNCING', 'scope_order_id':expected_order})
        try:
            data = validate_export(packet, expected_order)
            digest = hashlib.sha256(json.dumps(data, sort_keys=True, allow_nan=False).encode()).hexdigest()
        except Exception:
            atomic_json(target, {**old, 'status':'FAILED', 'scope_order_id':expected_order})
            raise
        if old.get('status') == 'READY' and old.get('source_hash') == digest and old.get('domain') == domain:
            if repository.business_counts(old['graph_snapshot_id'], expected_order, domain) == old['counts']:
                atomic_json(target, old)
                return old
        generation = uuid.uuid4().hex
        receipt = {'status':'SYNCING', 'graph_snapshot_id':generation, 'scope_order_id':expected_order,
            'domain':domain, 'synthetic':synthetic, 'source_hash':digest, 'source_export_id':data['exportId'],
            'source_observed_at':data['sourceObservedAt'], 'source_read_finished_at':data['readFinishedAt'],
            'missing_links':data['missingLinks'], 'complete':True}
        atomic_json(target, receipt)
        try:
            repository.write_business(generation, expected_order, data, domain)
            counts = repository.business_counts(generation, expected_order, domain)
            if counts != {'nodes':len(data['nodes']), 'edges':len(data['edges'])}:
                raise ValueError('Business snapshot count verification failed')
            receipt.update(status='READY', counts=counts, synced_at=datetime.now(timezone.utc).isoformat())
            archive=data_dir/'business-releases'; archive.mkdir(exist_ok=True)
            atomic_json(archive/(generation+'.json'), receipt)
            atomic_json(target, receipt)
            return receipt
        except Exception:
            atomic_json(target, {**receipt, 'status':'FAILED'})
            raise


def trace_business(data_dir, repository, order, limit=100):
    if not 1 <= limit <= 100:
        raise ValueError('Trace budget must be 1..100')
    path = _path(data_dir, order)
    before = json.loads(path.read_text(encoding='utf-8'))
    if before['status'] != 'READY' or before['scope_order_id'] != order:
        raise ValueError('Business graph is syncing, failed or stale')
    data = repository.read_business(before['graph_snapshot_id'], order, before['domain'], limit)
    after = json.loads(path.read_text(encoding='utf-8'))
    if after != before:
        raise ValueError('Business graph generation changed while reading')
    nodes = data['nodes'][:limit]
    ids = {n['id'] for n in nodes}
    edges = [e for e in data['edges'][:limit] if e['source'] in ids and e['target'] in ids]
    truncated = len(data['nodes']) > limit or len(data['edges']) > limit or len(edges) != len(data['edges'])
    return {**before, 'nodes':nodes, 'edges':edges, 'complete':not truncated, 'truncated':truncated,
        'coverage':'ORDER_PLAN_WORK_ORDER_INSPECTION_ONLY',
        'limitations':['Maintenance snapshot, not current stock or delivery promise.',
                       'Complete extraction does not imply all business links exist.']}
