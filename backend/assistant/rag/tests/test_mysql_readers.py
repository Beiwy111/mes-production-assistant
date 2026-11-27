import re
import runpy
from pathlib import Path

import pytest
from mes_knowledge.experience.source import SQL


ROOT = Path(__file__).resolve().parents[4]
DEPLOY = ROOT / 'backend/assistant/deploy'
MAPPERS = ROOT / 'backend/src/main/java/com/upc/computer/assistant/v2'
CONFIG = runpy.run_path(str(DEPLOY / 'Prepare-AssistantReadersLocal.py'))


def tables(statements):
    # Java documentation is not a query (for example, "from a scoped read").
    statements = (re.sub(r'(?m)^\s*//[^\n]*$', '', text) for text in statements)
    return {name for text in statements for name in re.findall(r'(?i)\b(?:FROM|JOIN)\s+`?(\w+)', text)}


def test_table_audit_ignores_comment_prose_but_keeps_query_tables():
    assert tables(['// Selected from a scoped read.\nSELECT * FROM work_order w JOIN production_plan p ON p.plan_id=w.plan_id']) == {'work_order', 'production_plan'}


def test_mysql_reader_allowlists_match_actual_fixed_source_tables():
    rules = CONFIG['PROFILES']
    for profile, names in {
        'agent': ['AiDeliveryReadMapper', 'AiBusinessReadMapper', 'NaturalBusinessReadMapper', 'WorkbenchIdentityReadMapper'],
        'workbench': ['WorkbenchIdentityReadMapper'], 'trace': ['GraphExportMapper'],
    }.items():
        files = [next(MAPPERS.rglob(name + '.java')) for name in names]
        assert set(rules[profile]) == tables(path.read_text('utf-8') for path in files)
    assert set(rules['history']) == tables(SQL.values())
    assert all(rules['history'].values()) and all(rules['workbench'].values())
    assert 'root_cause' not in rules['agent']['after_sales_closure'].split()
    assert not any(t.startswith('after_sales') for t in rules['history'])


@pytest.mark.parametrize('extra_grant', [
    'GRANT UPDATE ON `computer`.`work_order` TO `mes_asst_ro_agent`@`127.0.0.1`',
    'GRANT SELECT ON `computer`.* TO `mes_asst_ro_agent`@`127.0.0.1`',
    'GRANT SELECT ON `computer`.`work_order` TO `mes_asst_ro_agent`@`127.0.0.1` WITH GRANT OPTION',
])
def test_mysql_reader_audit_rejects_broader_grants(extra_grant):
    class Cursor:
        def execute(self, sql):
            pass
        def fetchone(self):
            return 'mes_asst_ro_agent@127.0.0.1', 'NONE', ''
        def fetchall(self):
            return [(extra_grant,)]
    with pytest.raises(ValueError, match='Unexpected privilege'):
        CONFIG['grants'](Cursor(), 'computer', 'mes_asst_ro_agent')
