"""Fixed, read-only projections of the two historical MES record types."""
from contextlib import contextmanager
from dataclasses import dataclass
import hashlib
import hmac
import json
import re
import unicodedata
import uuid
from urllib.parse import urlparse

from dotenv import dotenv_values

from ..config import RAG_ROOT

LIMIT = 5000  # per source, +1 detects incomplete snapshots before changing any index
TABLES = {'MAINTENANCE': 'equipment_maintenance_record', 'QUALITY': 'nonconforming_product'}
PRIMARY_KEYS = {'MAINTENANCE': 'm.maintenance_id', 'QUALITY': 'n.nonconforming_id'}
SQL = {
    'MAINTENANCE': """
        SELECT m.maintenance_id AS record_id, m.maintenance_no AS record_no,
               m.equipment_id, e.equipment_code, e.equipment_name,
               w.work_order_id, w.work_order_no, s.step_id, s.step_name,
               m.maintenance_type AS category, m.fault_description AS description,
               m.maintenance_content AS action, m.maintenance_result AS outcome,
               m.start_time AS occurred, m.end_time AS finished, m.downtime_minutes, m.updated_at
        FROM equipment_maintenance_record m
        JOIN equipment e ON e.equipment_id = m.equipment_id
        LEFT JOIN andon_alarm a ON a.alarm_id = m.alarm_id
             AND (a.equipment_id IS NULL OR a.equipment_id = m.equipment_id)
        LEFT JOIN dispatch_task d ON d.dispatch_id = a.dispatch_id
             AND (a.work_order_id IS NULL OR a.work_order_id = d.work_order_id)
             AND (d.equipment_id IS NULL OR d.equipment_id = m.equipment_id)
        LEFT JOIN work_order w ON w.work_order_id = COALESCE(a.work_order_id, d.work_order_id)
        LEFT JOIN process_step s ON s.step_id = d.step_id
        """,
    'QUALITY': """
        SELECT n.nonconforming_id AS record_id, n.nonconforming_no AS record_no,
               e.equipment_id, e.equipment_code, e.equipment_name,
               w.work_order_id, w.work_order_no, s.step_id, s.step_name,
               n.defect_type AS category, n.defect_description AS description,
               n.handle_method AS action, n.handle_status AS outcome, n.severity,
               n.registered_at AS occurred, n.handled_at AS finished, n.remark AS note, n.updated_at
        FROM nonconforming_product n
        LEFT JOIN quality_inspection i ON i.inspection_id = n.inspection_id
             AND (n.work_order_id IS NULL OR i.work_order_id IS NULL OR i.work_order_id = n.work_order_id)
             AND (n.work_report_id IS NULL OR i.work_report_id IS NULL OR i.work_report_id = n.work_report_id)
        LEFT JOIN work_report r ON r.report_id = COALESCE(n.work_report_id, i.work_report_id)
             AND (n.work_order_id IS NULL OR n.work_order_id = r.work_order_id)
             AND (i.work_order_id IS NULL OR i.work_order_id = r.work_order_id)
        LEFT JOIN work_order w ON w.work_order_id = COALESCE(n.work_order_id, i.work_order_id, r.work_order_id)
        LEFT JOIN equipment e ON e.equipment_id = r.equipment_id
        LEFT JOIN process_step s ON s.step_id = r.step_id
        """
}


@contextmanager
def reader_transaction(profile, path=None):
    import pymysql
    if profile not in {'history', 'agent'}:
        raise ValueError('Unknown local MySQL reader profile')
    path = path or RAG_ROOT.parent / f'deploy/.env.{profile}-read.local'
    config = dotenv_values(path, interpolate=False, encoding='utf-8-sig')
    url = urlparse(config['MES_AGENT_JDBC_URL'].removeprefix('jdbc:'))
    if (url.scheme != 'mysql' or url.hostname != '127.0.0.1'
            or config['MES_AGENT_JDBC_USER'] != 'mes_asst_ro_' + profile):
        raise ValueError('Dedicated local SELECT account required')
    with pymysql.connect(host=url.hostname, port=url.port or 3306, database=url.path.strip('/'),
            user=config['MES_AGENT_JDBC_USER'], password=config['MES_AGENT_JDBC_PASSWORD'],
            charset='utf8mb4', cursorclass=pymysql.cursors.DictCursor,
            connect_timeout=3, read_timeout=12, write_timeout=5) as connection:
        with connection.cursor() as cursor:
            cursor.execute('SET SESSION MAX_EXECUTION_TIME = 8000')
            cursor.execute('SET TRANSACTION ISOLATION LEVEL REPEATABLE READ')
            cursor.execute('START TRANSACTION WITH CONSISTENT SNAPSHOT, READ ONLY')
            try:
                yield cursor
            finally:
                connection.rollback()


class MySQLHistory:
    def __init__(self, path=None):
        self.path = path

    def transaction(self):
        return reader_transaction('history', self.path)

    def fetch(self, requested=None):
        """Full bounded snapshot for sync, or a parameterized primary-key lookup for serving."""
        result = {}
        with self.transaction() as cursor:
            for kind, statement in SQL.items():
                key = PRIMARY_KEYS[kind]
                ids = [] if requested is None else sorted(set(requested.get(kind, [])))
                if requested is not None:
                    if not ids:
                        continue
                    if len(ids) > 160 or any(type(i) is not int or i <= 0 for i in ids):
                        raise ValueError('Invalid history lookup')
                    statement += ' WHERE ' + key + ' IN (' + ','.join(['%s'] * len(ids)) + ')'
                cursor.execute(statement + ' ORDER BY ' + key + ' LIMIT %s', (*ids, LIMIT + 1))
                rows = cursor.fetchall()
                if len(rows) > LIMIT:
                    raise ValueError('History snapshot exceeds complete reconciliation budget')
                result[kind] = rows
        return result


def clean(value):
    if value is None:
        return ''
    value = unicodedata.normalize('NFKC', str(value))
    value = re.sub(r'<(?:/?[a-zA-Z][^<>]{0,200})>', ' ', value)
    value = ''.join(' ' if unicodedata.category(c) in ('Cc', 'Cf') else c for c in value)
    return ' '.join(value.split())


def public_excerpt(value):
    """Strip common contact and credential forms before embedding or quoting case notes."""
    text = clean(value)
    text = re.sub(r'(?i)(?:bearer\s+\S+|(?:api[ _-]?key|password|secret|token|密码|密钥)\s*[:=]\s*[^\s,，;；]+)', '[凭据已隐藏]', text)
    text = re.sub(r'(?i)\b[\w.+-]+@[\w.-]+\.[a-z]{2,}\b', '[联系方式已隐藏]', text)
    return re.sub(r'(?<![0-9])1[3-9][0-9]{9}(?![0-9])', '[联系方式已隐藏]', text)


def digest(secret, *values):
    encoded = json.dumps(values, ensure_ascii=False, sort_keys=True, separators=(',', ':')).encode()
    return hmac.new(secret, encoded, hashlib.sha256).hexdigest()


def opaque(secret, *values):
    return str(uuid.UUID(bytes=bytes.fromhex(digest(secret, *values))[:16], version=4))


RELATIONS = {'EQUIPMENT': 'ON_EQUIPMENT', 'WORK_ORDER': 'FOR_WORK_ORDER', 'PROCESS': 'AT_PROCESS',
             'DEFECT': 'HAS_DEFECT', 'ACTION': 'RECORDED_ACTION', 'CAUSE': 'RECORDED_CAUSE'}
LABELS = {'equipment_code': '设备编号', 'equipment_name': '设备', 'work_order_no': '工单',
          'step_name': '工序', 'category': '类型', 'description': '描述', 'action': '处置',
          'outcome': '结果状态', 'severity': '严重程度', 'downtime_minutes': '停机分钟',
          'occurred': '发生时间', 'finished': '结束时间', 'note': '备注'}
ENUMS = {'COMPLETED': '已完成', 'UNRESOLVED': '未解决', 'TEMPORARY_FIXED': '临时修复',
         'INSPECTION': '巡检', 'REPAIR': '故障维修', 'MAINTENANCE': '保养', 'OVERHAUL': '大修',
         'PENDING': '待处理', 'PROCESSING': '处理中', 'REWORK': '返工', 'SCRAP': '报废',
         'CONCESSION': '让步接收', 'RECHECK': '复检', 'GENERAL': '一般', 'MINOR': '轻微', 'MAJOR': '严重'}


@dataclass
class Record:
    key: str
    kind: str
    pk: int
    number: str
    title: str
    text: str
    hash: str
    entities: dict

    @property
    def ref(self):
        return 'experience:' + self.key + ':' + self.hash


def record(secret, kind, raw):
    pk = int(raw['record_id'])
    values = {k: clean(v) for k, v in raw.items()}
    for field in ('description','action','note','outcome'):
        if field in raw:
            values[field] = public_excerpt(raw[field])
    number = values['record_no']
    title = ('设备维护' if kind == 'MAINTENANCE' else '不合格品处置') + ' ' + number
    def display(k):
        v = values[k]
        if k in {'category', 'action', 'outcome', 'severity'} and v in ENUMS:
            return ENUMS[v] + ' (' + v + ')'
        return v

    text = '\n'.join([title] + [label + '：' + display(k) for k, label in LABELS.items() if values.get(k)])
    entities = {}

    def entity(typ, identity, label):
        if identity and label:
            entities[opaque(secret, 'entity', typ, identity)] = {'type': typ, 'label': label}

    entity('EQUIPMENT', values.get('equipment_id'), ' '.join(filter(None, (
        values.get('equipment_code'), values.get('equipment_name')))))
    entity('WORK_ORDER', values.get('work_order_id'), values.get('work_order_no'))
    entity('PROCESS', values.get('step_id'), values.get('step_name'))
    if kind == 'QUALITY':
        entity('DEFECT', values.get('category'), values.get('category'))
    # Only an explicit, source-authored confirmation is eligible. Shared observations never establish a cause.
    for field in ('description','action','note'):
        for match in re.finditer(r'(?:^|[。；;])\s*(?:已确认根因|已确认故障原因)\s*:\s*([^。；;]{1,100})(?=[。；;]|$)', values.get(field, '')):
            cause = match.group(1).strip()
            if cause and not re.search('可能|疑似|推测|待查|未确认|未查明|不明|待核|未知|已隐藏|尚未', cause):
                entity('CAUSE', cause, cause)
    if values.get('action') and values['action'].upper() != 'PENDING':
        entity('ACTION', values['action'], display('action'))
    key = opaque(secret, 'record', kind, pk)
    return Record(key, kind, pk, number, title, text, digest(secret, 'v1', kind, values), entities)
