"""Compare authoritative facts and real task intervals, ignoring only generated references."""
import json
from pathlib import Path
root=Path('docs/ui-verification/parallel-agents')
before=json.loads((root/'before.json').read_text(encoding='utf-8'))
after=json.loads((root/'after.json').read_text(encoding='utf-8'))
def differences(a,b,path=''):
 if isinstance(a,dict) and isinstance(b,dict):
  return sum((differences(a.get(k),b.get(k),path+'/'+k) for k in a.keys()|b.keys()),[])
 if isinstance(a,list) and isinstance(b,list) and len(a)==len(b):
  return sum((differences(x,y,path+'/'+str(i)) for i,(x,y) in enumerate(zip(a,b))),[])
 return [] if a==b else [path]
report=[]
for current in after['cases']:
 previous=next(c for c in before['cases'] if c['name']==current['name'])
 delta=differences(previous['result'].get('facts'),current['result'].get('facts'))
 intervals={}
 for event in current['events']:
  if event['type'] in ('agent_started','agent_completed'):
   intervals.setdefault(event['agent_id'],{})[event['type']]=event['at']
 row={'name':current['name'],'beforeMs':previous['firstTokenMs'],'afterMs':current['firstTokenMs'],
      'reductionPercent':round(100*(1-current['firstTokenMs']/previous['firstTokenMs']),1),
      'changedFactPaths':delta,'agentIntervals':intervals}
 assert set(delta)<={'/E2/parentFulfillmentQueryRef','/E3/materialQueryRef'},delta
 for packet in (previous,current):
  facts=packet['result'].get('facts',{})
  for kind,key in [('E2','parentFulfillmentQueryRef'),('E3','materialQueryRef')]:
   if kind in facts:
    assert facts[kind][key] in packet['result']['queryRefs'],(kind,'unregistered evidence')
 if current['name']=='multi':
  active=[intervals[r] for r in ('SUPPLY','PRODUCTION','EQUIPMENT')]
  assert max(x['agent_started'] for x in active)<min(x['agent_completed'] for x in active),'No actual three-agent overlap'
 if current['name']=='delivery':
  assert intervals['ORDER']['agent_completed']<=intervals['SUPPLY']['agent_started']
  assert intervals['SUPPLY']['agent_completed']<=intervals['PRODUCTION']['agent_started']
 row['businessFactsMatch']=True
 report.append(row)
 print(json.dumps(row,ensure_ascii=False))
(root/'comparison.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
