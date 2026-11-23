"""Build the read-only reference for the project's shipped demonstration SQL. Never executes it."""
import csv
import hashlib
import json
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[3]
SOURCE = 'sql/fixes/seed_quality_dashboard.sql'
OUTPUT = ROOT / 'backend/src/main/resources/assistant/inspection-seed-reference.json'


def build():
    sql = (ROOT / SOURCE).read_text(encoding='utf-8')
    catalog = {}
    for table, projection, tail in re.findall(
            r'INSERT INTO (quality_inspection(?:_item)?)\s*\([^;]+?\)\s*SELECT (.*?)\n((?:FROM|WHERE)[^;]+);',
            sql, re.S):
        fields = next(csv.reader([' '.join(projection.splitlines())], quotechar="'", skipinitialspace=True))
        if table == 'quality_inspection':
            number, _, _, batch, kind, category, sampled, good, bad, result, status, _, at = fields[:13]
            if at == 'NOW()':  # no fixed inspection timestamp to match against the live row
                continue
            catalog[number] = dict(batch_no=batch, inspection_type=kind, inspection_category=category,
                                   inspected_at=at, sample_quantity=int(sampled), qualified_quantity=int(good),
                                   unqualified_quantity=int(bad), inspection_result=result, inspection_status=status, items=[])
        else:
            number = re.search(r"qi.inspection_no = '(\w+)'", tail)[1]
            if number in catalog:
                catalog[number]['items'].append(dict(zip(('item_code','item_name','standard_value','measured_value','unit','result'),fields[1:7])))
    if not catalog:
        raise ValueError('No supported demonstration rows in the source')
    return dict(source=SOURCE, sha256=hashlib.sha256(sql.encode()).hexdigest(), inspections=catalog)


if __name__ == '__main__':
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT.write_text(json.dumps(build(), ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
