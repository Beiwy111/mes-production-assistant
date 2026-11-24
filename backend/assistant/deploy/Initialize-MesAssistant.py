"""Initialize the local assistant against an existing, migrated MES MySQL database."""

import argparse
import getpass
import importlib
import os
from pathlib import Path
import re
import secrets
import sys

import assistant_seed_schema as seed

readers = importlib.import_module('Prepare-AssistantReadersLocal')
drafts = importlib.import_module('Prepare-OrderDraftsLocal')
DEPLOY = Path(__file__).resolve().parent
ROLE_KEYS = tuple('MES_AGENT_' + r + '_KEY' for r in
                  ('BOOTSTRAP', 'SUP', 'ORDER', 'SUPPLY', 'PRODUCTION', 'QUALITY', 'EQUIPMENT', 'AFTERSALES'))
ALLOWLIST = 'MES_AGENT_ALLOWED_ORDER_IDS'
MODEL_KEY = 'DASHSCOPE_API_KEY'
JDBC = readers.PREFIX


class SetupError(Exception):
    """Safe to display. Messages must not include credentials or connection strings."""


def setting(name, prompt, default=None, *, secret=False):
    if name in os.environ:
        return os.environ[name]
    if not sys.stdin.isatty():
        if default is not None:
            return default
        raise SetupError(f'Set {name} or run setup in an interactive terminal.')
    hint = f' [{default}]' if default is not None and not secret else ''
    try:
        value = (getpass.getpass if secret else input)(prompt + hint + ': ')
    except EOFError:
        raise SetupError(f'Set {name} or run setup in an interactive terminal.') from None
    return value if value or default is None else default


def admin_configuration(profile):
    previous = readers.location(profile) if JDBC + 'URL' in profile else None
    schema = setting('MES_SETUP_DB_SCHEMA', 'Existing MES database', previous.path[1:] if previous else 'display_manufacturing')
    port = setting('MES_SETUP_DB_PORT', 'MySQL TCP port on 127.0.0.1', str(previous.port or 3306) if previous else '3306')
    if not re.fullmatch(r'[A-Za-z0-9_]{1,64}', schema) or not re.fullmatch(r'[0-9]{1,5}', port) or not 1 <= int(port) <= 65535:
        raise SetupError('Enter a valid schema identifier and local TCP port.')
    if previous and (schema != previous.path[1:] or int(port) != (previous.port or 3306)):
        raise SetupError('The existing assistant profile belongs to a different database. Check the schema and port.')
    user = setting('MES_SETUP_DB_USER', 'MySQL user with CREATE USER and grant privileges', profile.get(JDBC + 'USER', 'root'))
    password = setting('MES_SETUP_DB_PASSWORD', 'MySQL password (hidden)', profile.get(JDBC + 'PASSWORD'), secret=True)
    return {JDBC + 'URL': f'jdbc:mysql://127.0.0.1:{port}/{schema}?serverTimezone=Asia/Shanghai',
            JDBC + 'USER': user, JDBC + 'PASSWORD': password}


def missing_objects(cursor):
    cursor.execute('SELECT TABLE_NAME, COLUMN_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE()')
    columns = {}
    for table, column in cursor.fetchall():
        columns.setdefault(table, set()).add(column)
    missing = set()
    for rules in readers.PROFILES.values():
        for table, projection in rules.items():
            if table not in columns:
                missing.add(table)
            elif projection:
                missing.update(f'{table}.{col}' for col in set(projection.split()) - columns[table])
    for name in seed.COLUMNS:
        table, column = name.split('.')
        if table in columns and column not in columns[table]:
            missing.add(name)
    return missing


def schema_check(cursor, database='', *, prepare_seed_schema=False, interactive=False):
    missing = missing_objects(cursor)
    if not missing:
        return
    names = ', '.join(sorted(missing))
    if missing - (seed.COLUMNS.keys() | seed.TABLES.keys()):
        raise SetupError('Apply the MES business migrations to the selected database first. Missing: ' + names)
    print(f'Local database {database} needs these additional MES tables/columns: {names}')
    if not prepare_seed_schema and interactive:
        try:
            answer = input('Add the listed empty tables/columns using the repository schema definitions? [y/N]: ')
        except EOFError:
            answer = ''
        prepare_seed_schema = answer.strip().lower() in ('y', 'yes')
    if not prepare_seed_schema:
        raise SetupError('Missing: ' + names + '. See SEED-SCHEMA.md. To apply these additive changes, '
                         'confirm during interactive setup or supply --prepare-seed-schema.')
    for statement in seed.statements(missing):
        try:
            cursor.execute(statement)
        except readers.pymysql.OperationalError as exc:
            if not exc.args or exc.args[0] != 1060:  # Another installer may have added the column.
                raise
    remaining = missing_objects(cursor)
    if remaining:
        raise SetupError('Remaining schema changes: ' + ', '.join(sorted(remaining)))


def allowed_orders(text):
    ids = [item.strip() for item in text.split(',')]
    if (len(text) > 20000 or any(not re.fullmatch(r'[1-9][0-9]{0,18}', item)
                                 or int(item) > 9223372036854775807 for item in ids)):
        raise SetupError('Enter explicit positive order_id values separated by commas.')
    return ','.join(dict.fromkeys(ids))


def check_role_keys(profile):
    keys = [profile.get(name, '') for name in ROLE_KEYS]
    if len(set(keys)) != len(keys) or any(not re.fullmatch(r'[A-Za-z0-9_-]{32,256}', key) for key in keys):
        raise SetupError('Existing .env.agents.local has missing, invalid or duplicate role keys.')
    if profile.get('MES_AGENT_PORT', '18082') != '18082':
        raise SetupError('The installed Dify workflows require MES_AGENT_PORT=18082.')
    if set(profile) - {*ROLE_KEYS, ALLOWLIST, 'MES_AGENT_PORT', JDBC + 'URL', JDBC + 'USER', JDBC + 'PASSWORD'}:
        raise SetupError('Unknown setting in .env.agents.local.')


def prepare_roles(admin, profile, path, prepare_seed_schema):
    with readers.connect(admin) as connection, connection.cursor() as cursor:
        schema_check(cursor, readers.location(admin).path[1:], prepare_seed_schema=prepare_seed_schema,
                     interactive=sys.stdin.isatty())
        if profile is not None:
            check_role_keys(profile)
            allowed_orders(profile.get(ALLOWLIST, ''))
            return
        if ALLOWLIST not in os.environ and sys.stdin.isatty():
            cursor.execute('SELECT order_id FROM customer_order ORDER BY order_id DESC LIMIT 8')
            recent = [str(row[0]) for row in cursor.fetchall()]
            print('Recent order IDs for the maintenance preview:', ', '.join(recent) or '(none)')
        allowed = allowed_orders(setting(ALLOWLIST, 'Order IDs authorized for the maintenance preview (comma-separated)'))
    config = {key: secrets.token_urlsafe(40) for key in ROLE_KEYS}
    config.update({ALLOWLIST: allowed, JDBC + 'URL': admin[JDBC + 'URL'], 'MES_AGENT_PORT': '18082'})
    readers.write_private(path, ''.join(f'{key}={value}\n' for key, value in config.items()))


def daily_configuration(path):
    if path.exists():
        readers.restrict_file(path)
        lines = path.read_text(encoding='utf-8-sig').splitlines()
        if len(lines) != 1 or not lines[0].startswith(MODEL_KEY + '='):
            raise SetupError('Expected one DASHSCOPE_API_KEY line in .env.daily-chat.local.')
        key = lines[0].partition('=')[2]
    else:
        key = setting(MODEL_KEY, 'DashScope API key for daily chat (hidden)', secret=True)
    if not re.fullmatch(r'\S+', key):
        raise SetupError('A nonempty single-line DashScope API key is required for daily chat.')
    if not path.exists():
        readers.write_private(path, f'{MODEL_KEY}={key}\n')


def main(prepare_seed_schema=False):
    path = DEPLOY / '.env.agents.local'
    profile = None
    if path.exists():
        readers.restrict_file(path)
        profile = readers.values(path)
    admin = admin_configuration(profile or {})
    prepare_roles(admin, profile, path, prepare_seed_schema)
    daily_configuration(DEPLOY / '.env.daily-chat.local')
    drafts.main(admin)
    for name, rules in readers.PROFILES.items():
        readers.prepare(name, rules, admin, False)
        print(f'[OK] {name} MySQL SELECT grants verified.')
    print('[OK] Assistant credentials, order draft schema and daily chat configuration ready.')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--prepare-seed-schema', action='store_true',
                        help='Apply supported additive MES schema changes without the interactive prompt.')
    try:
        main(parser.parse_args().prepare_seed_schema)
    except SetupError as exc:
        sys.exit(str(exc))
    except Exception as exc:
        code = exc.args[0] if isinstance(exc, readers.pymysql.MySQLError) and exc.args and isinstance(exc.args[0], int) else 'CONFIG'
        sys.exit(f'Assistant provisioning failed ({type(exc).__name__}, {code}). Check MySQL privileges and local configuration.')
