"""Provision or verify loopback-only SELECT accounts for the four assistant readers."""
import argparse
import os
from pathlib import Path
import re
import secrets
import subprocess
import sys
import tempfile
from urllib.parse import parse_qs, urlencode, urlparse

import pymysql


DEPLOY = Path(__file__).resolve().parent
HOST = '127.0.0.1'
IDENTITY = {
    'user': 'user_id role_id customer_name status',
    'role': 'role_id role_code status',
    'sys_menu': 'menu_id menu_code status',
    'sys_role_menu': 'role_id menu_id',
}
TRACE = {
    'customer_order': 'order_id audit_status',
    'customer_order_item': 'order_item_id order_id quantity item_status',
    'production_plan': 'plan_id plan_status source_order_id',
    'production_plan_item': 'plan_item_id order_item_id plan_id',
    'work_order': 'work_order_id plan_id plan_item_id status planned_quantity completed_quantity',
    'quality_inspection': 'inspection_id work_order_id inspection_result sample_quantity qualified_quantity unqualified_quantity',
}
HISTORY = {
    'equipment_maintenance_record': 'maintenance_id maintenance_no equipment_id alarm_id maintenance_type fault_description maintenance_content maintenance_result start_time end_time downtime_minutes updated_at',
    'nonconforming_product': 'nonconforming_id nonconforming_no inspection_id work_order_id work_report_id defect_type defect_description handle_method handle_status severity registered_at handled_at remark updated_at',
    'equipment': 'equipment_id equipment_code equipment_name',
    'andon_alarm': 'alarm_id equipment_id work_order_id dispatch_id',
    'dispatch_task': 'dispatch_id work_order_id equipment_id step_id',
    'work_order': 'work_order_id work_order_no',
    'work_report': 'report_id work_order_id equipment_id step_id',
    'quality_inspection': 'inspection_id work_order_id work_report_id',
    'process_step': 'step_id step_name',
}
AGENT = {
    **dict.fromkeys('andon_alarm bom customer_order_item delivery_order dispatch_task equipment '
                    'equipment_maintenance_record inventory inventory_barcode inventory_transaction material '
                    'material_batch nonconforming_product process_step production_plan production_plan_item '
                    'production_plan_schedule purchase_order purchase_order_item quality_inspection '
                    'quality_inspection_item work_order work_report'.split()),
    **IDENTITY,
    'user': IDENTITY['user'] + ' real_name',
    'process_route': 'route_id material_id route_code route_name version_no status',
    'customer_order': 'order_id order_no order_date customer_name audit_status required_delivery_date updated_at',
    'after_sales_case': 'case_id case_no order_id customer_name delivery_id material_id batch_no quality_inspection_id '
                        'problem_type case_level case_status opened_at resolved_at closed_at updated_at',
    'after_sales_task': 'task_id task_no case_no plan_id task_type status due_at started_at completed_at updated_at',
    'after_sales_closure': 'closure_id case_no recheck_passed customer_confirmed created_at updated_at',
}
PROFILES = {'agent': AGENT, 'workbench': IDENTITY, 'trace': TRACE, 'history': HISTORY}
PREFIX = 'MES_AGENT_JDBC_'


def values(path):
    result = {}
    for line in path.read_text(encoding='utf-8-sig').splitlines():
        if not line.strip() or line.lstrip().startswith('#'):
            continue
        k, v = line.split('=', 1)
        if k in result:
            raise ValueError('Duplicate local configuration')
        result[k] = v
    return result


def location(config):
    parsed = urlparse(config[PREFIX + 'URL'].removeprefix('jdbc:'))
    if (parsed.scheme != 'mysql' or parsed.hostname not in {HOST, 'localhost'}
            or not re.fullmatch('/[A-Za-z0-9_]+', parsed.path)):
        raise ValueError('A local MySQL schema is required')
    return parsed


def connect(config, select_schema=True):
    target = location(config)
    return pymysql.connect(host=HOST, port=target.port or 3306, database=target.path[1:] if select_schema else None,
                           user=config[PREFIX + 'USER'], password=config[PREFIX + 'PASSWORD'],
                           charset='utf8mb4', connect_timeout=4, read_timeout=12, write_timeout=5,
                           autocommit=True)


def write_private(path, content):
    # Publish a fully written private file without replacing an existing credential on a retry.
    fd, name = tempfile.mkstemp(prefix=path.name + '.', suffix='.tmp', dir=path.parent)
    temporary = Path(name)
    try:
        with os.fdopen(fd, 'w', encoding='utf-8') as file:
            restrict_file(temporary)
            file.write(content)
            file.flush()
            os.fsync(file.fileno())
        if os.name == 'nt':
            os.rename(temporary, path)  # Windows refuses to replace an existing file.
        else:
            os.link(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)


def restrict_file(path):
    if os.name == 'nt':
        account = subprocess.check_output(['whoami', '/user', '/fo', 'csv', '/nh'])
        sid = re.search(rb'S-1-\d+(?:-\d+)+', account).group().decode('ascii')
        subprocess.run(['icacls', str(path), '/inheritance:r', '/grant:r', '*' + sid + ':F', '*S-1-5-18:F'],
                       check=True, capture_output=True)
    else:
        os.chmod(path, 0o600)


def grants(cursor, database, user):
    cursor.execute('SELECT CURRENT_USER(),CURRENT_ROLE(),@@mandatory_roles')
    principal, roles, mandatory = cursor.fetchone()
    if principal != user + '@' + HOST or roles != 'NONE' or mandatory:
        raise ValueError('Unexpected effective MySQL principal or roles')
    cursor.execute('SHOW GRANTS')
    found = {}
    for (grant,) in cursor.fetchall():
        if re.fullmatch(r'GRANT USAGE ON \*\.\* TO `[a-z_]+`@`127\.0\.0\.1`', grant):
            continue
        match = re.fullmatch(r'GRANT SELECT(?: \(([^)]+)\))? ON `([a-zA-Z0-9_]+)`\.`([a-z_]+)` '
                             r'TO `([a-z_]+)`@`127\.0\.0\.1`', grant)
        if not match or match[2] != database or match[4] != user:
            raise ValueError('Unexpected privilege, object scope or GRANT OPTION')
        found[match[3]] = set(match[1].replace('`', '').split(', ')) if match[1] else None
    return found


def verify(config, expected, *, partial=False):
    database, user = location(config).path[1:], config[PREFIX + 'USER']
    with connect(config, select_schema=not partial) as conn, conn.cursor() as cursor:
        found = grants(cursor, database, user)
        for table, cols in found.items():
            if table not in expected or (expected[table] is not None and (
                    cols is None or not cols <= expected[table])):
                raise ValueError('Existing reader has unexpected grants; inspect it before provisioning')
        if not partial:
            if found != expected:
                raise ValueError('Reader grants differ from the reviewed allowlist')
            cursor.execute('START TRANSACTION READ ONLY')
            for table, cols in found.items():
                projection = ','.join('`' + c + '`' for c in sorted(cols)) if cols else '*'
                cursor.execute(f'SELECT {projection} FROM `{table}` LIMIT 0')
            conn.rollback()
    return found


def prepare(name, rules, admin, verify_only):
    path = DEPLOY / f'.env.{name}-read.local'
    user = 'mes_asst_ro_' + name
    target = location(admin) if admin else None
    if name == 'agent':
        # Add optional evidence tables only when installed on this site.
        with connect(admin or values(path)) as conn, conn.cursor() as cursor:
            cursor.execute("SELECT table_name FROM information_schema.tables WHERE table_schema=DATABASE() "
                           "AND table_name IN ('assistant_training_work_history','quality_inspection_round',"
                           "'quality_inspection_round_item','quality_defect_sample','quality_sample_measurement')")
            rules = {**rules, **{row[0]: None for row in cursor.fetchall()}}
    rules = {t: set(v.split()) if v else None for t,v in rules.items()}
    if verify_only:
        cfg = values(path)
        if cfg[PREFIX + 'USER'] != user:
            raise ValueError('Incorrect reader profile')
        verify(cfg, rules)
        return
    database = target.path[1:]
    with connect(admin) as conn, conn.cursor() as cursor:
        cursor.execute('SELECT Host FROM mysql.user WHERE User=%s', (user,))
        hosts = [r[0] for r in cursor.fetchall()]
        if hosts and (hosts != [HOST] or not path.exists()):
            raise ValueError('Existing account collision; original account was preserved')
        if not path.exists():
            options = urlencode({'serverTimezone': parse_qs(target.query).get('serverTimezone', ['Asia/Shanghai'])[0],
                                 'connectTimeout': 5000, 'socketTimeout': 20000, 'allowPublicKeyRetrieval': 'true'})
            url = f'jdbc:mysql://{HOST}:{target.port or 3306}/{database}?{options}'
            write_private(path, f'{PREFIX}URL={url}\n{PREFIX}USER={user}\n{PREFIX}PASSWORD={secrets.token_urlsafe(40)}\n')
        else:
            restrict_file(path)
        cfg = values(path)
        dest = location(cfg)
        if (cfg[PREFIX + 'USER'] != user or dest.path != target.path or
                (dest.port or 3306) != (target.port or 3306)):
            raise ValueError('Private reader file belongs to another profile or database')
        if not hosts:
            cursor.execute('CREATE USER %s@%s IDENTIFIED BY %s', (user, HOST, cfg[PREFIX + 'PASSWORD']))
        found = verify(cfg, rules, partial=True)
        for table, cols in rules.items():
            if table in found and found[table] == cols:
                continue
            clause = ' (' + ','.join('`' + c + '`' for c in sorted(cols)) + ')' if cols else ''
            cursor.execute(f'GRANT SELECT{clause} ON `{database}`.`{table}` TO %s@%s', (user, HOST))
    verify(cfg, rules)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--verify-only', action='store_true')
    args = parser.parse_args()
    try:
        admin = None if args.verify_only else values(DEPLOY / '.env.agents.local')
        for name, rules in PROFILES.items():
            prepare(name, rules, admin, args.verify_only)
            print(f'{name}: verified loopback SELECT on {len(rules)} allowed tables')
    except Exception as exc:
        # MySQL diagnostics can quote SQL; never echo credentials or source statements.
        code = exc.args[0] if isinstance(exc, pymysql.MySQLError) and exc.args and isinstance(exc.args[0], int) else 'CONFIG'
        print(f'Reader setup failed ({type(exc).__name__}, code {code}). Check provisioning privileges, '
              'reader files and grant scope.', file=sys.stderr)
        sys.exit(1)
