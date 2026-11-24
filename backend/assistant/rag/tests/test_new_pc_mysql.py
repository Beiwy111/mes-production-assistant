"""Opt-in Windows/MySQL 8 integration test using a new data directory and an ephemeral port."""
import os
import hashlib
from pathlib import Path
import re
import secrets
import shutil
import socket
import subprocess
import sys
import time

import pymysql
import pytest

ROOT = Path(__file__).resolve().parents[4]
DEPLOY = ROOT / 'backend/assistant/deploy'


@pytest.mark.skipif(os.environ.get('MES_TEST_ISOLATED_MYSQL') != '1', reason='set MES_TEST_ISOLATED_MYSQL=1 to start an isolated instance')
def test_repository_seed_provision_and_repeat(tmp_path):
    server_bin = shutil.which('mysqld')
    client_bin = shutil.which('mysql')
    if not server_bin or not client_bin:
        pytest.skip('MySQL 8 server and client binaries must be installed')
    data = tmp_path / 'mysql'
    base = [server_bin, '--no-defaults', '--datadir=' + str(data)]
    subprocess.run([*base, '--initialize-insecure'], cwd=tmp_path, capture_output=True, check=True, timeout=120)
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', 0))
        port = sock.getsockname()[1]
    admin_password = secrets.token_hex(32)
    init = tmp_path / '.env.mysql-init.local'
    init.write_text(f"ALTER USER 'root'@'localhost' IDENTIFIED BY '{admin_password}';\n", encoding='utf-8')
    connection_args = dict(host='127.0.0.1', port=port, user='root', password=admin_password, connect_timeout=2)
    verified_instance = False
    monitor_args = ['--no-monitor'] if os.name == 'nt' else []
    with (tmp_path / 'server.log').open('wb') as log:
        proc = subprocess.Popen([*base, *monitor_args, '--bind-address=127.0.0.1', f'--port={port}', '--mysqlx=0',
                                 '--init-file=' + str(init), '--console'], cwd=tmp_path, stdout=log, stderr=log)
        try:
            for _ in range(90):
                if proc.poll() is not None:
                    pytest.fail('Disposable MySQL server exited before it became ready.')
                try:
                    conn = pymysql.connect(**connection_args)
                    break
                except pymysql.OperationalError:
                    time.sleep(1)
            else:
                pytest.fail('Disposable MySQL server did not become ready.')
            with conn, conn.cursor() as cursor:
                cursor.execute('SELECT @@datadir, @@port')
                actual_dir, actual_port = cursor.fetchone()
                assert Path(actual_dir).resolve() == data.resolve() and actual_port == port
                verified_instance = True
            init.unlink()
            exercise_install(tmp_path, client_bin, port, admin_password)
        finally:
            if verified_instance and proc.poll() is None:
                try:
                    with pymysql.connect(**connection_args) as conn, conn.cursor() as cursor:
                        cursor.execute('SHUTDOWN')
                    proc.wait(timeout=30)
                except (pymysql.MySQLError, subprocess.TimeoutExpired):
                    pass
            if proc.poll() is None:
                proc.terminate()
                proc.wait(timeout=30)
            init.unlink(missing_ok=True)
            if data.exists() and data.resolve().parent == tmp_path.resolve():
                shutil.rmtree(data)


def exercise_install(tmp_path, client_bin, port, password):
    checkout = tmp_path / 'checkout'
    deploy = checkout / 'backend/assistant/deploy'
    deploy.mkdir(parents=True)
    for name in ('Initialize-MesAssistant.py', 'assistant_seed_schema.py', 'Prepare-AssistantReadersLocal.py',
                 'Prepare-OrderDraftsLocal.py', 'order-drafts.sql'):
        shutil.copyfile(DEPLOY / name, deploy / name)
    migrations = checkout / 'sql/migrations'
    migrations.mkdir(parents=True)
    for file in ('aftersale_workflow_v2.sql', 'warehouse_barcode_schema.sql', 'upgrade_planner_module.sql', 'quality_module_v2.sql'):
        shutil.copyfile(ROOT / 'sql/migrations' / file, migrations / file)
    env = {**os.environ, 'MYSQL_PWD': password, 'MES_SETUP_DB_SCHEMA': 'display_manufacturing',
           'MES_SETUP_DB_PASSWORD': password, 'MES_SETUP_DB_USER': 'root', 'MES_SETUP_DB_PORT': str(port),
           'MES_AGENT_ALLOWED_ORDER_IDS': '1', 'DASHSCOPE_API_KEY': secrets.token_hex(32)}
    def sql(source, label):
        result = subprocess.run([client_bin, '--no-defaults', '--protocol=TCP', '--host=127.0.0.1',
                                 f'--port={port}', '--user=root', '--default-character-set=utf8mb4'],
                                env=env, input=source, capture_output=True, timeout=90)
        if result.returncode:
            code = re.search(rb'ERROR ([0-9]+).*? at line ([0-9]+)', result.stderr)
            pytest.fail(f'{label}: MySQL error and line {code.groups() if code else "unknown"}.')
    def initialize(*args):
        return subprocess.run([sys.executable, str(deploy / args[0]), *args[1:]],
                              cwd=tmp_path, env=env, input=b'', capture_output=True, timeout=90)
    def business_state():
        with pymysql.connect(host='127.0.0.1', port=port, user='root', password=password,
                             database='display_manufacturing') as conn, conn.cursor() as cursor:
            cursor.execute('SELECT (SELECT COUNT(*) FROM customer_order), (SELECT COUNT(*) FROM material), '
                           '(SELECT COUNT(*) FROM quality_inspection), (SELECT COUNT(*) FROM after_sales_case), '
                           '(SELECT SHA2(GROUP_CONCAT(user_id,role_id,username,password_hash,status ORDER BY user_id),256) FROM `user`)')
            return cursor.fetchone()
    try:
        sql((ROOT / 'sql/init/init.sql').read_bytes(), 'init.sql')
        before = business_state()
        run = initialize('Initialize-MesAssistant.py')
        assert run.returncode != 0 and b'production_plan_schedule' in run.stderr
        assert not list(deploy.glob('.env.*'))
        for iteration in range(2):
            run = initialize('Initialize-MesAssistant.py', '--prepare-seed-schema')
            assert run.returncode == 0, run.stderr.decode(errors='replace')
            profiles = {p.name: hashlib.sha256(p.read_bytes()).digest() for p in deploy.glob('.env.*.local')}
            if iteration == 0:
                assert len(profiles) == 6
                first = profiles
            else:
                assert profiles == first
            assert all(key.encode() not in run.stdout + run.stderr for key in (password, env['DASHSCOPE_API_KEY']))
        audit = initialize('Prepare-AssistantReadersLocal.py', '--verify-only')
        assert audit.returncode == 0, audit.stderr.decode(errors='replace')
        assert business_state() == before
        sql(b'USE display_manufacturing; SELECT customer_name, shipping_address FROM `user` LIMIT 0; '
            b'SELECT image_url,product_summary,ports,sort_order,supplier_id FROM material LIMIT 0; '
            b'SELECT version_no,parent_plan_no FROM production_plan LIMIT 0; '
            b'SELECT inspection_category,inspection_status FROM quality_inspection LIMIT 0;', 'runtime read projections')
    finally:
        for profile in deploy.glob('.env.*.local'):
            profile.unlink()
