export const nodeTypes = {
  start: ['开始', '接收工作流输入', 'green'],
  end: ['输出', '返回流程执行结果', 'red'],
  code: ['处理', '执行变量校验与数据处理', 'blue'],
  llm: ['模型', '调用语言模型生成结果', 'purple'],
  agent: ['Agent', '执行 Agent 策略并调用工具', 'purple'],
  'overview-agent': ['业务 Agent', '处理专业领域任务', 'blue'],
  supervisor: ['总 Agent', '协调专家与任务分派', 'teal'],
  tool: ['业务工具', '调用已配置的业务工具', 'teal'],
  'http-request': ['业务接口', '调用业务服务并读取响应', 'teal'],
  'if-else': ['条件分支', '根据条件选择下游链路', 'amber'],
  iteration: ['迭代', '按输入集合执行子流程', 'amber'],
  'iteration-start': ['迭代入口', '接收当前迭代项', 'green'],
  'variable-aggregator': ['变量汇总', '合并分支输出', 'blue'],
  'knowledge-retrieval': ['知识检索', '从知识库检索相关资料', 'teal'],
  template: ['模板', '根据模板生成文本', 'blue'],
}
export const typeInfo = kind => nodeTypes[kind] || [kind, '按流程定义执行', 'blue']
export const colorByTone = {
  teal: '#128786', green: '#30976a', blue: '#447eca', purple: '#8860bb',
  amber: '#b47b24', red: '#c06a68',
}

export function overviewGraph(catalog) {
  const agents = catalog.filter(flow => flow.agentId)
  return {
    nodes: agents.map(flow => ({
      id: flow.agentId, kind: flow.agentId === 'SUP' ? 'supervisor' : 'overview-agent',
      label: flow.label + ' Agent', detail: flow.description, flowName: flow.name,
      config: [['工作流', flow.workflowName], ['DSL 节点', String(flow.nodes.length)]],
      inputs: flow.nodes.find(node => node.kind === 'start')?.inputs || [],
      outputs: flow.nodes.filter(node => node.kind === 'end').flatMap(node => node.outputs),
    })),
    edges: agents.flatMap(flow => flow.delegates.map(to => ({ from: flow.agentId, to, label: '可调度' }))),
  }
}

// Stable topological layers, shared by both layout directions and the structural main path.
function layers(nodes, edges) {
  const remaining = new Set(nodes.map(n => n.id))
  const incoming = new Map(nodes.map(n => [n.id, []]))
  for (const e of edges) incoming.get(e.to)?.push(e.from)
  const result = []
  while (remaining.size) {
    const row = [...remaining].filter(id => incoming.get(id).every(src => !remaining.has(src)))
    if (!row.length) row.push(remaining.values().next().value)
    row.forEach(id => remaining.delete(id))
    result.push(row)
  }
  return result
}

export function flowGraph(flow, parent, mainOnly) {
  const nodes = flow.nodes.filter(n => n.parent === parent)
  const ids = new Set(nodes.map(n => n.id))
  const edges = flow.edges.filter(e => ids.has(e.from) && ids.has(e.to))
  if (!mainOnly || !nodes.length) return { nodes, edges }
  // Prefer a longest real route from the DSL entry to an output. Ties retain DSL order.
  const ranks = new Map(), prev = new Map()
  const start = nodes.find(n => ['start', 'iteration-start'].includes(n.kind)) || nodes[0]
  ranks.set(start.id, 1)
  for (const id of layers(nodes, edges).flat()) {
    if (!ranks.has(id)) continue
    for (const edge of edges.filter(e => e.from === id)) {
      const score = ranks.get(id) + 1
      if (score > (ranks.get(edge.to) || 0)) {
        ranks.set(edge.to, score)
        prev.set(edge.to, edge)
      }
    }
  }
  const reachable = nodes.filter(n => ranks.has(n.id))
  const ends = reachable.filter(n => n.kind === 'end' || !edges.some(e => e.from === n.id))
  let id = (ends.length ? ends : reachable).reduce((a, b) => ranks.get(a.id) >= ranks.get(b.id) ? a : b).id
  const route = new Set([id]), links = []
  while (prev.has(id)) {
    const edge = prev.get(id)
    if (route.has(edge.from)) break
    links.push(edge)
    route.add(edge.from)
    id = edge.from
  }
  return { nodes: nodes.filter(n => route.has(n.id)), edges: links.reverse() }
}

export function layoutNodes(graph, direction) {
  const positions = new Map()
  const vertical = direction === 'vertical'
  for (const [level, row] of layers(graph.nodes, graph.edges).entries()) {
    row.forEach((id, index) => {
      const across = index - (row.length - 1) / 2
      positions.set(id, vertical ? { x: across * 266, y: level * 178 } : { x: level * 324, y: across * 122 })
    })
  }
  return positions
}
