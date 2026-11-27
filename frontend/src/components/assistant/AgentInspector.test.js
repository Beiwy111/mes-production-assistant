import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { stripTypeScriptTypes } from 'node:module'
import { parse, compileScript } from '@vue/compiler-sfc'
import * as vue from 'vue'
import { renderToString } from '@vue/server-renderer'
import * as agents from '../../utils/agentWorkbench.js'
import * as inspector from '../../utils/agentInspector.js'
import * as knowledge from '../../utils/knowledgeSources.js'

const { descriptor } = parse(readFileSync(new URL('./AgentInspector.vue', import.meta.url), 'utf8'))
const dependencies = { vue, '@/utils/agentWorkbench': agents, '@/utils/agentInspector': inspector, '@/utils/knowledgeSources': knowledge,
  './AgentStatusIndicator.vue': { props:['status'], setup:props=>()=>vue.h('span',{'data-status':props.status}) },
  'lucide-vue-next': Object.fromEntries(['Bot', 'Check', 'ChevronRight', 'Circle', 'CircleAlert', 'LoaderCircle', 'Network', 'X'].map((name) => [name, () => null])) }
function loadComponent(descriptor) {
  const code = stripTypeScriptTypes(compileScript(descriptor, { id: 'inspector-test', inlineTemplate: true }).content)
  .replace(/^import\s*\{([^}]+)\}\s*from\s*['"]([^'"]+)['"];?\s*$/gm, (_, bindings, from) => `const { ${bindings.replace(/\bas\b/g, ':')} } = dependencies[${JSON.stringify(from)}];`)
  .replace(/^import\s+(\w+)\s+from\s*['"]([^'"]+)['"];?\s*$/gm, (_, binding, from) => `const ${binding} = dependencies[${JSON.stringify(from)}];`)
  .replace('export default', 'return')
  return new Function('dependencies', code)(dependencies)
}
dependencies['./ExperienceEvidence.vue'] = loadComponent(parse(readFileSync(new URL('./ExperienceEvidence.vue', import.meta.url), 'utf8')).descriptor)
dependencies['./AgentAvatar.vue'] = loadComponent(parse(readFileSync(new URL('./AgentAvatar.vue', import.meta.url), 'utf8')).descriptor)
const component = loadComponent(descriptor)

test('historical graph paths show only records in the current validated result', async () => {
  const hash = 'a'.repeat(64), ref = 'experience:de9a5c32-d3e7-498a-895d-773061761a7e:' + hash
  const hit = { kind:'MAINTENANCE', evidence_ref:ref, record_hash:hash, source_table:'equipment_maintenance_record',
    record_id:3, title:'维修三号', text:'已更换零件', retrieval_source:'vector' }
  const bundle = { agentId:'EQUIPMENT', domain:'DIAGNOSTIC', status:'MATCHES', graph_status:'MATCHES', hits:[hit],
    graph_paths:[{source_ref:ref,target_ref:ref,relation:'ON_EQUIPMENT→RECORDED_ACTION',entity_label:'设备 → 处置',hops:2},
      {source_ref:ref,target_ref:'foreign',entity_label:'不可见其他记录',hops:2}] }
  const message = { status:'COMPLETED', agents:[], result:{experienceEvidence:[bundle]} }
  const render = () => renderToString(vue.createSSRApp(component,{message}))
  const html = await render()
  assert.match(html,/维修三号/);assert.match(html,/Neo4j 关联图/);assert.doesNotMatch(html,/不可见其他记录/)
  message.result.verdict='DATA_CHANGED'
  assert.doesNotMatch(await render(),/维修三号/)
})

test('inspector renders seven actual roles and safely nests real research evidence', async () => {
  const html = await renderToString(vue.createSSRApp(component, { message: { status: 'COMPLETED',
    agents: [{ agentId: 'QUALITY', status: 'COMPLETED', steps: [
      { id: 'KNOWLEDGE_RETRIEVAL', status: 'COMPLETED', label: '公开知识检索', detail: '<img src=x>' },
    ] }], result: { knowledgeEvidence: [{ agentId: 'QUALITY', status: 'NO_MATCHES', graph_status: 'UNAVAILABLE_OR_STALE', hits: [] }] },
  } }))
  assert.equal((html.match(/class="inspector-role/g) || []).length, 7)
  assert.deepEqual(new Set(Array.from(html.matchAll(/data-role="([A-Z]+)"/g), match => match[1])), new Set(Object.keys(agents.AGENTS)))
  assert.doesNotMatch(html, /agent-avatar working/)
  for (const label of ['Supervisor', '公开知识检索', '本轮知识检索', '不可用或已过期', '&lt;img']) assert.ok(html.includes(label), label)
  assert.doesNotMatch(html, /<img|RAG Agent|GraphRAG Agent/)
  assert.match(html, /role="tab"[^>]*aria-selected="true"/)
  assert.match(html, /role="tabpanel"[^>]*hidden/)
})

test('tabs switch displayed content by click and keyboard and close emits', async () => {
  const node = (type) => ({ type, props: {}, children: [], parent: null, focused: false,
    focus() { this.focused = true } })
  const renderer = vue.createRenderer({
    createElement: node, createText: () => node('text'), createComment: () => node('comment'),
    setText() {}, setElementText() {},
    patchProp: (element, key, _previous, value) => { element.props[key] = value },
    insert(element, parent, anchor) { element.parent = parent; const index = anchor ? parent.children.indexOf(anchor) : -1;
      if (index < 0) parent.children.push(element); else parent.children.splice(index, 0, element) },
    remove(element) { const siblings = element.parent.children; siblings.splice(siblings.indexOf(element), 1) },
    parentNode: (element) => element.parent,
    nextSibling: (element) => element.parent?.children[element.parent.children.indexOf(element) + 1],
  })
  let closed = false
  const app = renderer.createApp(component, { message: null, onClose: () => { closed = true } })
  const root = node('root')
  app.mount(root)
  const descendants = (element) => [element, ...element.children.flatMap(descendants)]
  try {
    const all = descendants(root)
    const tabs = all.filter((element) => element.props.role === 'tab')
    const panels = all.filter((element) => element.props.role === 'tabpanel')
    tabs[1].props.onClick()
    await vue.nextTick()
    assert.equal(tabs[1].props['aria-selected'], true)
    assert.equal(panels[0].props.hidden, true)
    assert.equal(panels[1].props.hidden, false)
    await tabs[1].props.onKeydown({ key: 'ArrowLeft', preventDefault() {} })
    assert.equal(tabs[0].props['aria-selected'], true)
    assert.equal(tabs[0].focused, true)
    all.find((element) => element.props['aria-label'] === '关闭 Agent 面板').props.onClick()
    assert.equal(closed, true)
  } finally { app.unmount() }
})

test('inspector displays order request state, actual service logs and draft status without extra agents', async () => {
  const html = await renderToString(vue.createSSRApp(component, { message: { status: 'COMPLETED', orderFlow: {
    pending: '正在提交订单审核', orderDraft: { status: 'READY' }, steps: [
      { agentId: 'SUP', label: '识别需求', status: 'COMPLETED' },
      { agentId: 'ORDER', label: '整理需求', status: 'COMPLETED' },
      { agentId: 'SERVICE', label: '校验草稿', status: 'COMPLETED', detail: '<script>unsafe()</script>' },
    ],
  } } }))
  assert.equal((html.match(/class="inspector-role/g) || []).length, 7)
  for (const label of ['当前请求', '订单草稿', '待确认', '业务服务', '正在提交订单审核', '校验草稿', '&lt;script&gt;']) assert.ok(html.includes(label), label)
  assert.doesNotMatch(html, /SERVICE Agent|<script>|状态待确认/)
})

test('the inspector reports a production preview and decision without inventing agent calls', async () => {
  for (const [status, label] of [['NEEDS_INPUT', '待补充'], ['PENDING', '待确认执行'], ['EXECUTED', '操作已执行'], ['REJECTED', '操作已取消']]) {
    const html = await renderToString(vue.createSSRApp(component, { message: { status: 'COMPLETED',
      productionFlow: { status, confirmation: { title: '保存生产排程草案' } } } }))
    assert.ok(html.includes(label))
    assert.doesNotMatch(html, /等待任务/)
  }
})

test('production service receipt replaces the empty timeline with actual response counts', async () => {
  const html = await renderToString(vue.createSSRApp(component, { message: { status: 'COMPLETED',
    productionFlow: { status: 'NEEDS_INPUT', steps: [{ agentId: 'SERVICE', label: '准备生产操作预览',
      toolId: 'production.preview', status: 'COMPLETED', detail: '返回 3 种排产方案；等待补充或选择，未写入业务数据。' }] } } }))
  for (const label of ['业务服务', '准备生产操作预览', '返回 3 种排产方案', '未写入业务数据']) assert.ok(html.includes(label), label)
  assert.doesNotMatch(html, /class="inspector-idle"|SERVICE Agent/)
})

test('live roles open automatically and expose actual query results without nested clicks', async () => {
  const html = await renderToString(vue.createSSRApp(component, { message: { id: 'live', status: 'RUNNING',
    agents: [{ agentId: 'SUPPLY', status: 'RUNNING', evidence: [{ kind: 'OVERVIEW_INVENTORY', status: 'PARTIAL',
      data: { overview: { totalRecords: 5, returnedRecords: 1, truncated: true, keyword: 'PCB',
        rows: [{ material_name: 'PCB板', quantity_available: 0, unit: '块' }] } } }] }],
  } }))
  assert.match(html, /class="inspector-role running"[^>]*open/)
  assert.match(html, /class="agent-avatar working" data-role="SUPPLY"/)
  assert.match(html, /库存查询/)
  assert.match(html, /返回 1 条/)
  assert.match(html, /PCB板/)
})

test('actual lifecycle opens active agents, respects manual toggles, closes on end, and resets for history', async () => {
  const node = (type) => ({ type, props: {}, children: [], parent: null })
  const renderer = vue.createRenderer({
    createElement: node, createText: () => node('text'), createComment: () => node('comment'),
    setText() {}, setElementText() {},
    patchProp: (el, key, _old, value) => { el.props[key] = value },
    insert(el, parent, anchor) { el.parent = parent; const i = anchor ? parent.children.indexOf(anchor) : -1;
      if (i < 0) parent.children.push(el); else parent.children.splice(i, 0, el) },
    remove(el) { el.parent.children.splice(el.parent.children.indexOf(el), 1) },
    parentNode: (el) => el.parent, nextSibling: (el) => el.parent?.children[el.parent.children.indexOf(el) + 1],
  })
  const message = vue.ref({ id: 'live', status: 'RUNNING', agents: [
    { agentId: 'SUPPLY', taskId: '1', status: 'RUNNING' }, { agentId: 'PRODUCTION', taskId: '2', status: 'WAITING' },
  ] })
  const app = renderer.createApp({ setup: () => () => vue.h(component, { message: message.value }) })
  const root = node('root'); app.mount(root)
  const descendants = (el) => [el, ...el.children.flatMap(descendants)]
  const role = (id) => descendants(root).find((el) => el.props['data-agent'] === id)
  try {
    assert.equal(role('SUPPLY').props.open, true)
    assert.equal(role('PRODUCTION').props.open, false)
    role('SUPPLY').children.find((el) => el.type === 'summary').props.onClick({ preventDefault() {} })
    await vue.nextTick()
    message.value.agents[0].summary = '真实返回摘要'
    await vue.nextTick()
    assert.equal(role('SUPPLY').props.open, false, 'a new streamed result does not override a manual collapse')
    message.value.agents[1].status = 'RUNNING'
    await vue.nextTick()
    assert.equal(role('PRODUCTION').props.open, true)
    message.value.agents[0].taskId = 'retry'
    await vue.nextTick()
    assert.equal(role('SUPPLY').props.open, true, 'a new attempt expands')
    message.value.agents[0].status = 'COMPLETED'
    await vue.nextTick()
    assert.equal(role('SUPPLY').props.open, false)
    assert.equal(role('PRODUCTION').props.open, true)
    message.value.status = 'CANCELLED'
    await vue.nextTick()
    assert.equal(role('PRODUCTION').props.open, false)
    role('SUPPLY').children.find((el) => el.type === 'summary').props.onClick({ preventDefault() {} })
    await vue.nextTick()
    assert.equal(role('SUPPLY').props.open, true, 'completed evidence can be manually reopened')
    message.value = { id: 'history', status: 'COMPLETED', agents: [{ agentId: 'SUPPLY', status: 'COMPLETED' }] }
    await vue.nextTick()
    assert.equal(role('SUPPLY').props.open, false)
  } finally { app.unmount() }
})
