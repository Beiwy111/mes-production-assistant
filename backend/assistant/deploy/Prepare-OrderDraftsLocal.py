"""Apply the single additive assistant draft table using existing local credentials.

Requires Python with pymysql (present on the current development computer).
Never prints configuration values or changes existing business tables.
"""
from pathlib import Path
from urllib.parse import urlparse
import sys
import pymysql


def main():
    deploy = Path(__file__).resolve().parent
    config = {}
    for line in (deploy / '.env.agents.local').read_text(encoding='utf-8-sig').splitlines():
        if line.strip() and not line.lstrip().startswith('#'):
            key, value = line.split('=', 1)
            config[key] = value
    url = urlparse(config['MES_AGENT_JDBC_URL'].removeprefix('jdbc:'))
    if url.scheme != 'mysql' or url.hostname not in ('127.0.0.1', 'localhost') or not url.path.strip('/'):
        raise ValueError('Local MySQL configuration required')
    with pymysql.connect(host=url.hostname, port=url.port or 3306, user=config['MES_AGENT_JDBC_USER'],
                         password=config['MES_AGENT_JDBC_PASSWORD'], database=url.path.strip('/'),
                         charset='utf8mb4', connect_timeout=10, read_timeout=15, write_timeout=15) as connection:
        with connection.cursor() as cursor:
            cursor.execute((deploy / 'order-drafts.sql').read_text(encoding='utf-8'))
            cursor.execute('SELECT COUNT(*) FROM assistant_order_draft_v2')
            cursor.fetchone()
        connection.commit()
    print('READY: assistant_order_draft_v2. Existing business tables were not changed.')


if __name__ == '__main__':
    try:
        main()
    except Exception as exc:
        print('Draft schema preparation failed (' + type(exc).__name__ + '). Check local MySQL configuration; credentials are not displayed.', file=sys.stderr)
        sys.exit(1)
