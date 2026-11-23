import test from 'node:test'
import assert from 'node:assert/strict'
import * as evidence from './workbenchEvidence.js'
import { knowledgeGraph, layoutGraph } from './workbenchGraph.js'

export const hit = { release_id: 'release', evidence_ref: 'release:P01:c1', document_id: 'P01',
  document_version: 'sha256', chunk_id: 'c1', title: '<img src=x onerror=alert(1)>',
  text: 'Research observes Mura with an optical method.', page_start: 2, page_end: 3,
  authority_type: 'PUBLIC_RESEARCH', source_url: 'https://example.org/paper.pdf' }
export const path = { claim_id: 'C1', subject_id: 'METHOD', subject_name: 'Optical method',
  object_id: 'MURA', object_name: 'Mura', relation: 'DETECTS', statement: 'Public research statement',
  quote: 'observes Mura', page: 2, document_id: 'P01', document_version: 'sha256',
  graph_release_id: 'graph', evidence_refs: [hit.evidence_ref], scope: 'RESEARCH_ONLY',
  review_status: 'SOURCE_VERIFIED', authority_type: 'PUBLIC_RESEARCH' }
export const bundle = { agentId: 'QUALITY', status: 'MATCHES', query: 'Mura', release_id: 'release',
  requested_mode: 'graph_assisted', mode_used: 'graph_assisted', graph_status: 'MATCHES',
  graph_release_id: 'graph', hits: [hit], graph_paths: [path], limitations: [], missing_information: [] }

test('the knowledge window joins verified claims to their chunks once across agent results', () => {
  const checked = evidence.verifiedPublicBundle(bundle)
  const graph = knowledgeGraph([checked, checked])
  assert.equal(graph.nodes.length, 5)
  assert.deepEqual(graph.edges.map(edge => edge.label).sort(), ['主语', '包含', '宾语', '支持'].sort())
  const layout = layoutGraph(graph.nodes, graph.edges)
  assert.equal(layout.links.length, 4)
  assert.ok(layout.nodes.every(node => Number.isFinite(node.x) && Number.isFinite(node.y)))
  const fallback = evidence.verifiedPublicBundle({ ...bundle, graph_paths: [], graph_status: 'UNAVAILABLE_OR_STALE' })
  assert.deepEqual(knowledgeGraph([fallback]).nodes.map(node => node.kind), ['资料', '索引片段'])
})

test('research presentation validates original Python hit and graph shapes', () => {
  const [result] = evidence.knowledgeEvidence({ knowledgeEvidence: [bundle] })
  assert.equal(result.hits[0].title, hit.title)
  assert.equal(result.hits[0].url, hit.source_url)
  assert.equal(result.paths.length, 1)
  const invalid = evidence.knowledgeEvidence({ knowledgeEvidence: [{ ...bundle,
    hits: [hit, { ...hit, evidence_ref: 'forged' }], graph_paths: [path,
      { ...path, quote: 'invented' }, { ...path, document_version: 'old' },
      { ...path, evidence_refs: ['absent'] }] }] })[0]
  assert.equal(invalid.hits.length, 1)
  assert.equal(invalid.paths.length, 1)
  assert.equal(invalid.omitted, 4)
})

test('unsafe URLs never become links; empty, unavailable and fallback knowledge is explicit', () => {
  for (const url of ['javascript:alert(1)', 'data:text/html,evil', '//example.org', 'https://u:p@example.org', 'https://example.org/\nfoo', 'file:///x'])
    assert.equal(evidence.safeSourceUrl(url), '')
  assert.equal(evidence.safeSourceUrl('https://example.org/paper.pdf'), 'https://example.org/paper.pdf')
  for (const status of ['NO_MATCHES', 'UNAVAILABLE']) {
    const data = evidence.knowledgeEvidence({ knowledgeEvidence: [{ ...bundle, status }] })[0]
    assert.equal(data.hits.length, 0)
    assert.match(data.notice, status === 'NO_MATCHES' ? /未命中/ : /不可用/)
  }
  const fallback = evidence.knowledgeEvidence({ knowledgeEvidence: [{ ...bundle, mode_used: 'vector', graph_status: 'UNAVAILABLE_OR_STALE', graph_paths: [] }] })[0]
  assert.match(fallback.modeLabel, /向量/)
  assert.match(fallback.graphLabel, /不可用|过期/)
})

test('analysis renders only conclusions with all citations in the current evidence scope', () => {
  const result = { queryRefs: ['q1'], knowledgeEvidence: [bundle], analyses: [{ agentId: 'QUALITY',
    status: 'COMPLETED', summary: 'Uncited summary is explanation only',
    findings: [{ text: 'Actual record', evidenceRefs: ['q1'] }, { text: 'Forged', evidenceRefs: ['q1', 'fake'] },
      { text: 'No citations', evidenceRefs: [] }],
    hypotheses: [{ text: 'Research hypothesis', evidenceRefs: [hit.evidence_ref], verification: 'Reinspect' }],
    actions: [{ text: 'Verify source', evidenceRefs: ['q1'] }], missing: ['Need sample IDs'] }] }
  const [analysis] = evidence.analysisEvidence(result)
  assert.equal(analysis.findings.length, 1)
  assert.equal(analysis.hypotheses.length, 1)
  assert.equal(analysis.omitted, 2)
  assert.deepEqual(evidence.analysisEvidence({ ...result, verdict: 'DATA_CHANGED' }), [])
  assert.deepEqual(evidence.knowledgeEvidence({ ...result, verdict: 'DATA_CHANGED' }), [])
  result.analyses[0].agentId = 'AFTERSALES'
  assert.equal(evidence.analysisEvidence(result)[0].hypotheses.length, 0)
})
