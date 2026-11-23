import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { parse, compileScript } from '@vue/compiler-sfc'
import * as vue from 'vue'
import { renderToString } from '@vue/server-renderer'
import * as agents from '../../utils/agentWorkbench.js'

const { descriptor } = parse(readFileSync(new URL('./AgentWorkflowCard.vue', import.meta.url), 'utf8'))
const dependencies = { vue, '@/utils/agentWorkbench': agents,
  './AgentAvatar.vue': { props: ['agentId', 'active'], setup: props => () => vue.h('span', { 'data-role': props.agentId, 'data-active': props.active }) },
  './AgentStatusIndicator.vue': { props:['status'], setup:props=>()=>vue.h('span',{'data-status':props.status}) },
  '@/components/assistant/InventoryFindings.vue': { setup: () => () => null },
  'lucide-vue-next': Object.fromEntries(['ChevronRight', 'Check', 'Circle', 'CircleAlert', 'LoaderCircle'].map((name) => [name, () => null])),
  '@/components/chat/ChatBubble.vue': { props: ['text'], setup: (props) => () => vue.h('p', props.text) } }
const code = compileScript(descriptor, { id: 'workflow-test', inlineTemplate: true }).content
  .replace(/^import\s*\{([^}]+)\}\s*from\s*['"]([^'"]+)['"];?\s*$/gm, (_, bindings, from) => `const { ${bindings.replace(/\bas\b/g, ':')} } = dependencies[${JSON.stringify(from)}];`)
  .replace(/^import\s+(\w+)\s+from\s*['"]([^'"]+)['"];?\s*$/gm, (_, binding, from) => `const ${binding} = dependencies[${JSON.stringify(from)}];`)
  .replace('export default', 'return')
const component = new Function('dependencies', code)(dependencies)
async function render(message) {
  const app = vue.createSSRApp(component, { message })
  app.component('el-icon', { setup: (_, { slots }) => () => vue.h('span', slots.default?.()) })
  return renderToString(app)
}
test('workflow shows only real observed steps with safe text and explicit skipped status', async () => {
  const html = await render({ status: 'RUNNING', agents: [{ agentId: 'QUALITY', status: 'RUNNING', steps: [
    { id: 'BUSINESS_READ', label: '已读取检验记录', status: 'COMPLETED', detail: '<img src=x onerror=alert(1)>', at: '2026-11-09T09:00:00' },
    { id: 'VISION_REVIEW', label: '没有归档', status: 'SKIPPED' },
    { id: 'ANALYSIS', label: '虚构分析', status: 'PENDING' },
  ] }] })
  for (const text of ['质量检验 Agent', '已读取检验记录', '没有归档', '未执行', '&lt;img']) assert.ok(html.includes(text), text)
  assert.doesNotMatch(html, /虚构分析|<img|公开检索已完成/)
  assert.match(html, /<details[^>]*\sopen/)
})
test('legacy agent history never invents business, vision or knowledge steps', async () => {
  const html = await render({ status: 'COMPLETED', agents: [{ agentId: 'ORDER', status: 'COMPLETED', summary: '旧版摘要' }] })
  assert.match(html, /尚未收到实际步骤记录/)
  assert.doesNotMatch(html, /已检索知识|已核验视觉/)
})

test('finished coordinator shows terminal phase while retaining the original event in history', async () => {
  const html = await render({ status: 'COMPLETED', agents: [], workflow: { events: [
    { seq: 1, kind: 'SUMMARIZING', agentId: 'SUP', status: 'RUNNING', label: '汇总证据', detail: '正在复核并汇总本次结果' },
  ] } })
  const phase = html.match(/class="flow-phase"[\s\S]*?<\/div>/)?.[0] || ''
  assert.match(phase, /协作记录/)
  assert.doesNotMatch(phase, /正在复核/)
  assert.match(html, /正在复核并汇总本次结果/)
})

test('live workflow shows waiting dependencies, observed failures and explicit handoffs', async () => {
  const html = await render({ status: 'RUNNING', agents: [], workflow: {
    plan: { action: 'QUERY', requiredAgents: ['QUALITY', 'AFTERSALES', 'EQUIPMENT'],
      dependencies: { AFTERSALES: ['QUALITY'] } },
    events: [
      { seq: 1, kind: 'AGENT_STARTED', agentId: 'QUALITY', status: 'RUNNING', label: '开始角色任务' },
      { seq: 2, kind: 'STEP_COMPLETED', agentId: 'QUALITY', status: 'FAILED', label: '读取业务证据' },
      { seq: 3, kind: 'AGENT_COMPLETED', agentId: 'QUALITY', status: 'FAILED', label: '角色任务结束' },
    ],
  } })
  for (const expected of ['等待前序', '待调用', '读取业务证据', '未完成', '质量检验 Agent']) assert.ok(html.includes(expected), expected)
  assert.match(html, /<details[^>]*\sopen/)
  assert.match(html, /class="flow-step failed"/)
  assert.doesNotMatch(html, /→/)
  const handoff = await render({ status: 'COMPLETED', agents: [], workflow: {
    events: [{ seq: 1, kind: 'HANDOFF', agentId: 'AFTERSALES', fromAgent: 'QUALITY',
      toAgent: 'AFTERSALES', status: 'COMPLETED', label: '交接业务证据' },
    { seq: 2, kind: 'AGENT_STARTED', agentId: 'AFTERSALES', status: 'RUNNING', label: '开始角色任务' }],
  } })
  assert.match(handoff, /质量检验 → 售后追溯/)
  assert.match(handoff, /状态待确认/)
  assert.doesNotMatch(handoff, /class="spin"|<details[^>]*\sopen/)
})

test('a role retry replaces the first attempt in the node and preserves its failure in the timeline', async () => {
  const html = await render({ status: 'RUNNING', agents: [
    { agentId: 'QUALITY', taskId: 'first', status: 'FAILED', summary: '旧任务摘要' },
  ], workflow: {
    plan: { action: 'QUERY', requiredAgents: ['QUALITY', 'AFTERSALES'], dependencies: { AFTERSALES: ['QUALITY'] } },
    events: [
      { seq: 1, kind: 'AGENT_COMPLETED', agentId: 'QUALITY', taskId: 'first', status: 'FAILED', label: '首次任务结束' },
      { seq: 2, kind: 'AGENT_STARTED', agentId: 'QUALITY', taskId: 'second', status: 'RUNNING', label: '重试开始' },
      { seq: 3, kind: 'STEP_COMPLETED', agentId: 'QUALITY', taskId: 'second', status: 'COMPLETED', label: '读取业务证据' },
      { seq: 4, kind: 'AGENT_COMPLETED', agentId: 'QUALITY', taskId: 'second', status: 'COMPLETED', label: '重试成功' },
    ],
  } })
  assert.match(html, /class="flow-step completed"/)
  assert.match(html, /1 \/ 2 已完成/)
  assert.match(html, /首次任务结束 · 未完成/)
  assert.doesNotMatch(html, /旧任务摘要|等待前序/)
  assert.match(html, /role="status"[^>]*aria-live="polite"[^>]*>[^<]*已确认执行计划/)
})

test('terminal planned roles without invocations are marked uncalled and retain dependency information', async () => {
  const html = await render({ status: 'INTERRUPTED', agents: [], workflow: {
    plan: { action: 'QUERY', requiredAgents: ['QUALITY', 'AFTERSALES'], dependencies: { AFTERSALES: ['QUALITY'] } },
    events: [],
  } })
  assert.match(html, /未调用/)
  assert.match(html, /前序：质量检验/)
  assert.doesNotMatch(html, /等待前序|待调用/)
})

test('live role summaries expose the actual current task detail without opening role details', async () => {
  const html = await render({ status: 'RUNNING', agents: [], workflow: {
    events: [
      { seq: 1, kind: 'AGENT_STARTED', agentId: 'QUALITY', taskId: 'current', status: 'RUNNING' },
      { seq: 2, kind: 'STEP_COMPLETED', agentId: 'QUALITY', taskId: 'old', status: 'COMPLETED', label: '旧检查', detail: '过期任务详情' },
      { seq: 3, kind: 'STEP_STARTED', agentId: 'QUALITY', taskId: 'current', status: 'RUNNING', label: '读取检验记录', detail: '正在查询订单 <ORDER-01> 的检验记录。' },
    ],
  } })
  const summary = html.match(/<details class="flow-step running"[^>]*>\s*<summary[^>]*>([\s\S]*?)<\/summary>/)?.[1]
  assert.ok(summary, 'role remains individually collapsed')
  assert.match(summary, /读取检验记录/)
  assert.match(summary, /正在查询订单 &lt;ORDER-01&gt; 的检验记录。/)
  assert.doesNotMatch(summary, /过期任务详情/)
  assert.match(html, /<details class="flow-log"[^>]*>\s*<summary/)
})

test('completed, cancelled and invalidated workflows offer a compact collapsed process toggle', async () => {
  for (const message of [
    { status: 'COMPLETED', workflow: { events: [] } },
    { status: 'RUNNING', workflow: { events: [{ agentId: 'SUP', kind: 'CANCELLED' }] } },
    { status: 'RUNNING', workflow: { invalidated: true, events: [] } },
  ]) {
    const html = await render({ agents: [], ...message })
    assert.doesNotMatch(html, /<details[^>]*\sopen/)
    const heading = html.match(/<summary class="flow-heading"[^>]*>([\s\S]*?)<\/summary>/)?.[1]
    assert.match(heading, /查看协作过程/)
    assert.doesNotMatch(heading, /实时|已完成|flow-heading__count/)
  }
})

test('a mounted live process automatically collapses when the response completes and can reopen', async () => {
  const node = (type) => ({ type, props: {}, children: [], parent: null })
  const renderer = vue.createRenderer({
    createElement: node,
    createText: () => node('text'),
    createComment: () => node('comment'),
    setText() {},
    setElementText() {},
    patchProp: (element, key, _previous, value) => { element.props[key] = value },
    insert(element, parent, anchor) {
      element.parent = parent
      const index = anchor ? parent.children.indexOf(anchor) : -1
      if (index < 0) parent.children.push(element)
      else parent.children.splice(index, 0, element)
    },
    remove(element) {
      const siblings = element.parent.children
      siblings.splice(siblings.indexOf(element), 1)
    },
    parentNode: (element) => element.parent,
    nextSibling: (element) => element.parent?.children[element.parent.children.indexOf(element) + 1],
  })
  const message = vue.reactive({ status: 'RUNNING', agents: [], workflow: { events: [] } })
  const app = renderer.createApp(component, { message })
  app.component('el-icon', { setup: (_, { slots }) => () => vue.h('span', slots.default?.()) })
  const root = node('root')
  app.mount(root)
  try {
    const process = root.children.find((element) => element.type === 'details')
    assert.equal(process.props.open, true)
    message.status = 'COMPLETED'
    await vue.nextTick()
    assert.equal(process.props.open, false)
    process.children.find((element) => element.type === 'summary').props.onClick({ preventDefault() {} })
    await vue.nextTick()
    assert.equal(process.props.open, true)
  } finally {
    app.unmount()
  }
})
