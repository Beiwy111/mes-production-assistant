import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { parse } from 'yaml'

const agentsDir = new URL('../../backend/assistant/agents/', import.meta.url)
const moduleId = 'virtual:mes-workflows'
const knowledge = [
  { name: 'stage3-public-rag', label: '公开知识检索', description: '检索公开文档并生成带引用的回答' },
  { name: 'stage4-graph-rag', label: '图谱辅助检索', description: '联合检索文档与图谱关系证据' },
]

// Only project display fields. The source files also contain prompts, credentials,
// request bodies and executable code that must stay out of the browser bundle.
function configuration(data) {
  const entries = [
    ['语言', data.code_language], ['请求方法', data.method?.toUpperCase()],
    ['模型', data.model?.name], ['推理策略', data.agent_strategy_label || data.agent_strategy_name],
    ['工具', data.tool_label || data.tool_name], ['输出类型', data.output_type],
  ]
  if (data.cases) entries.push(['条件组', String(data.cases.length)])
  if (data.type === 'iteration') entries.push(['执行方式', data.is_parallel ? '并行' : '顺序'])
  return entries.filter(([, value]) => typeof value === 'string' && value)
}

function selector(value) {
  return Array.isArray(value) && value[0] !== 'env' && value.every(s => typeof s === 'string')
    ? value.join('.') : ''
}
function parameters(data) {
  const inputs = (data.variables || []).map(v => Array.isArray(v) ? [selector(v), ''] :
    [v.variable, [v.type, selector(v.value_selector), v.required && '必填'].filter(Boolean).join(' · ')])
  if (data.iterator_selector) inputs.push(['迭代项', selector(data.iterator_selector)])
  for (const p of Object.keys(data.tool_parameters || data.agent_parameters || {})) inputs.push([p, ''])
  for (const group of data.cases || []) {
    for (const c of group.conditions || []) inputs.push([selector(c.variable_selector), c.comparison_operator || ''])
  }
  const outputs = Array.isArray(data.outputs) ? data.outputs.map(v => [v.variable, v.value_type || selector(v.value_selector)])
    : Object.entries(data.outputs || {}).map(([key, v]) => [key, v.type])
  if (data.output_type) outputs.push(['output', data.output_type])
  return { inputs: inputs.filter(([key]) => key), outputs: outputs.filter(([key]) => key) }
}

export function projectDsl(dsl) {
  const graph = dsl.workflow.graph
  const nodes = graph.nodes.map(({ id, data, parentId }) => {
    const config = configuration(data)
    return {
      id: String(id), label: data.title, kind: data.type, parent: String(parentId || ''),
      detail: data.desc || '', config, ...parameters(data),
    }
  })
  const edges = graph.edges.map(e => ({
    from: String(e.source), to: String(e.target),
    label: e.sourceHandle && e.sourceHandle !== 'source' ? String(e.sourceHandle) : '',
  }))
  return { nodes, edges }
}

export function workflowCatalog() {
  return {
    name: 'mes-workflow-catalog',
    resolveId(id) { if (id === moduleId) return '\0' + id },
    load(id) {
      if (id !== '\0' + moduleId) return
      const read = (relative) => {
        const path = fileURLToPath(new URL(relative, agentsDir))
        this.addWatchFile(path)
        return parse(readFileSync(path, 'utf8'))
      }
      const { agents } = read('catalog.yaml')
      const flows = agents.map(a => ({
        name: 'stage5/' + a.id, agentId: a.id, label: a.name, description: a.responsibility,
        group: a.id === 'SUP' ? '总 Agent' : '子 Agent', workflowName: a.workflowName,
        delegates: agents.filter(role => a.tools?.includes(role.workflowName)).map(role => role.id),
      }))
      const catalog = flows.concat(knowledge.map(k => ({ ...k, group: '知识流程' }))).map(flow => ({
        ...flow, ...projectDsl(read('workflows/' + flow.name + '.yml')),
      }))
      return 'export default ' + JSON.stringify(catalog)
    },
  }
}
