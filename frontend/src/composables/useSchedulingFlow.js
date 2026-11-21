import { ref } from 'vue'

const PLANNER_FLOW_TEMPLATE = [
  { key: 'order', title: '读取订单', section: null },
  { key: 'inventory', title: '核查库存', section: 'inventory' },
  { key: 'material', title: 'BOM齐套', section: 'inventory' },
  { key: 'equipment', title: '设备测算', section: 'capacity' },
  { key: 'operator', title: '人员评估', section: 'capacity' },
  { key: 'allocate', title: '资源分配', section: 'workshops' },
  { key: 'result', title: '生成方案', section: 'result' }
]

const DISPATCH_FLOW_TEMPLATE = [
  { key: 'route', title: '加载工艺', section: null },
  { key: 'equipment', title: '扫描设备', section: 'table' },
  { key: 'operator', title: '评估人员', section: 'table' },
  { key: 'match', title: '智能匹配', section: 'table' },
  { key: 'result', title: '生成推荐', section: 'result' }
]

const AGENT_AVATARS = {
  order: 'ERP · 订单主数据',
  inventory: 'WMS · 成品库存',
  material: 'BOM · 物料齐套',
  equipment: 'MES · 设备台账',
  operator: 'HR · 人员编制',
  allocate: 'MES · 车间产能',
  result: 'APS · 排产结论'
}

const DISPATCH_AGENTS = {
  route: 'MES · 工艺路线',
  equipment: 'MES · 设备台账',
  operator: 'HR · 人员编制',
  match: 'APS · 匹配结果',
  result: 'APS · 派工结论'
}

function agentNameForKey(key, template) {
  if (AGENT_AVATARS[key]) return AGENT_AVATARS[key]
  if (DISPATCH_AGENTS[key]) return DISPATCH_AGENTS[key]
  return template.find((t) => t.key === key)?.title || '数据取证'
}

function defaultBadgeForKey(key) {
  if (key === 'result') return '汇总'
  if (key === 'allocate' || key === 'match') return '取证'
  return '查询'
}

function evidenceItem(id, source, tag, code, title, snippet, reliability, relatedSteps, metrics) {
  return {
    id, source, tag, code, title, snippet,
    reliability,
    relatedSteps: relatedSteps || [],
    metrics: metrics || {}
  }
}

/** 后端未返回 schedulingSteps 时，从分析结果拼装思维流 */
function buildPlannerStepsFromResult(result) {
  if (!result) return []
  const inv = result.inventoryCheck || {}
  const cap = result.capacityAnalysis || {}
  const orderNo = result.orderId || '—'
  const product = result.productModel || '—'
  const orderQty = result.orderQuantity ?? 0
  const fgStock = inv.finishedGoodsStock ?? 0
  const shipStock = inv.shipFromStock ?? 0
  const needProduce = inv.needToProduce ?? 0
  const recQty = result.recommendedPlanQty ?? inv.recommendedPlanQty ?? 0
  const mats = (inv.materialChecks || []).filter((m) => m.materialType !== 'FINISHED')
  const matSupports = mats.map((m) => Number(m.maxSupportQty)).filter((n) => Number.isFinite(n))
  const materialLimit = matSupports.length
    ? Math.min(...matSupports)
    : (needProduce > 0 ? needProduce : 0)
  const operatorHeadcount = Math.min(
    Number(cap.operatorLimit) || 0,
    Number(result.availableOperators) || 0
  ) || Number(result.availableOperators) || 0
  const matLines = mats.map((m) =>
    `${m.materialName}：可用 ${m.available}，需求 ${m.requiredForPlan ?? m.required}，可支撑 ${m.maxSupportQty} 台${m.sufficient ? '' : '（不足）'}`
  )
  const workshops = result.workshops || []
  const wsLines = workshops.map((w) =>
    `${w.workshopName}：设备 ${w.requiredMachines} 台、人员 ${w.requiredOperators} 人`
  )

  return [
    {
      key: 'order', agentName: 'ERP · 订单主数据', actionType: '查询', badge: '查询',
      action: '检索 customer_order / 订单明细',
      summary: `从 ERP 订单库取出「${orderNo}」：${product} 订购 ${orderQty} 台。`,
      thought: `【查询 ERP.customer_order】订单 ${orderNo}，产品「${product}」，订购 ${orderQty} 台，计划 ${result.planStart} ~ ${result.planEnd}（${result.workDays} 天）。`,
      detailLines: [`订单号：${orderNo}`, `产品：${product}`, `订购数量：${orderQty} 台`, `计划天数：${result.workDays} 天`],
      evidenceCount: 1, section: null
    },
    {
      key: 'inventory', agentName: 'WMS · 成品库存', actionType: '查询', badge: '查询',
      action: '检索 inventory / 成品仓台账',
      summary: `从 WMS 库存表核对成品仓：库存 ${fgStock} 台，可直发 ${shipStock} 台，缺口 ${needProduce} 台。`,
      thought: `【查询 WMS.inventory】成品仓 ${fgStock} 台，可直发 ${shipStock} 台，需生产 ${needProduce} 台。`,
      detailLines: [`成品库存：${fgStock} 台`, `可现货发货：${shipStock} 台`, `需生产补足：${needProduce} 台`],
      evidenceCount: 2, section: 'inventory'
    },
    {
      key: 'material', agentName: 'BOM · 物料齐套', actionType: '查询', badge: '查询',
      action: '检索 bom_item / 原材料库存',
      summary: `从 BOM 与库存表核查 ${mats.length} 种物料，物料可产 ${materialLimit} 台。`,
      thought: `【查询 BOM.material】${mats.length} 种原材料，瓶颈：${(inv.bottlenecks || []).join('、') || '无'}。`,
      detailLines: matLines.length ? matLines : ['无子项物料'],
      evidenceCount: 2 + mats.length, section: 'inventory'
    },
    {
      key: 'equipment', agentName: 'MES · 设备台账', actionType: '查询', badge: '查询',
      action: '检索 equipment / 设备状态',
      summary: `从 MES 设备台账测算设备可产 ${cap.equipmentLimit ?? '-'} 台。`,
      thought: `【查询 MES.equipment】设备产能上限 ${cap.equipmentLimit ?? '-'} 台，分配设备 ${result.totalMachines ?? '-'} 台。`,
      detailLines: (cap.limits || []).slice(0, 4).length
        ? (cap.limits || []).slice(0, 4)
        : [`设备可产：${cap.equipmentLimit ?? '-'} 台`],
      evidenceCount: 3, section: 'capacity'
    },
    {
      key: 'operator', agentName: 'HR · 人员编制', actionType: '查询', badge: '查询',
      action: '检索 user / 在岗派工负荷',
      summary: `从人员编制表取出在岗 ${result.availableOperators ?? 0} 人，人员可行产量 ${cap.operatorLimit ?? '-'} 台。`,
      thought: `【查询 HR.user】在岗 ${result.availableOperators ?? 0} 人，人员可行产量 ${cap.operatorLimit ?? '-'} 台。`,
      detailLines: [
        `在岗操作员：${result.availableOperators ?? 0} 人`,
        `人员上限：${operatorHeadcount || '-'} 人`,
        `人员可行产量：${cap.operatorLimit ?? '-'} 台`
      ],
      evidenceCount: 4, section: 'capacity'
    },
    {
      key: 'allocate', agentName: 'MES · 车间产能', actionType: '取证', badge: '取证',
      action: '汇总车间设备与工序分配记录',
      summary: `从车间产能数据汇总 ${workshops.length} 个车间。`,
      thought: `【汇总 MES.workshop_capacity】分配至 ${workshops.length} 个车间，设备 ${result.totalMachines} 台、人员 ${result.totalOperators} 人。`,
      detailLines: wsLines.length ? wsLines : ['暂无车间分配'],
      evidenceCount: 5, section: 'workshops'
    },
    {
      key: 'result', agentName: 'APS · 排产结论', actionType: '汇总', badge: '汇总',
      action: '综合多源证据输出建议排产量',
      summary: `综合数据库证据，建议排产 ${recQty} 台。`,
      thought: `【汇总多源证据】建议排产 ${recQty} 台。${result.recommendation || ''}`,
      detailLines: [
        `订单：${orderQty} 台`,
        `物料可产：${materialLimit} 台`,
        `设备可产：${cap.equipmentLimit ?? '-'} 台`,
        `人员上限：${operatorHeadcount || '-'} 人`,
        `建议排产：${recQty} 台`,
        result.recommendation || ''
      ].filter(Boolean),
      evidenceCount: 6, section: 'result'
    }
  ]
}

/** 后端未返回 evidenceBase 时，从分析结果拼装证据库 */
function buildPlannerEvidenceFromResult(result) {
  if (!result) return []
  const inv = result.inventoryCheck || {}
  const cap = result.capacityAnalysis || {}
  const orderNo = result.orderId || '—'
  const needProduce = inv.needToProduce ?? 0
  const mats = (inv.materialChecks || []).filter((m) => m.materialType !== 'FINISHED')
  const matSupports = mats.map((m) => Number(m.maxSupportQty)).filter((n) => Number.isFinite(n))
  const materialLimit = matSupports.length
    ? Math.min(...matSupports)
    : (needProduce > 0 ? needProduce : 0)
  const operatorHeadcount = Math.min(
    Number(cap.operatorLimit) || 0,
    Number(result.availableOperators) || 0
  ) || Number(result.availableOperators) || 0
  const list = []

  list.push(evidenceItem('ev-order', 'ERP', '订单', orderNo, '客户订单主数据',
    `【订单数据】${orderNo} 订购 ${result.productModel} ${result.orderQuantity} 台。`,
    96, ['order'], {
      订单号: orderNo,
      产品型号: result.productModel,
      订购数量: `${result.orderQuantity} 台`,
      计划天数: `${result.workDays} 天`
    }))

  list.push(evidenceItem('ev-inv', 'WMS', '库存', 'FG-STOCK', '成品库存核查结果',
    `【库存数据】成品仓 ${inv.finishedGoodsStock ?? 0} 台，可发货 ${inv.shipFromStock ?? 0} 台，缺口 ${inv.needToProduce ?? 0} 台。`,
    92, ['inventory'], {
      成品可用库存: `${inv.finishedGoodsStock ?? 0} 台`,
      可现货发货: `${inv.shipFromStock ?? 0} 台`,
      需生产补足: `${inv.needToProduce ?? 0} 台`
    }))

  for (const mat of (inv.materialChecks || []).filter((m) => m.materialType !== 'FINISHED')) {
    list.push(evidenceItem(`ev-mat-${mat.materialCode}`, 'BOM', '物料', mat.materialCode, mat.materialName,
      `可用 ${mat.available}，可支撑 ${mat.maxSupportQty} 台${mat.sufficient ? '' : '（不足）'}`,
      88, ['material'], {
        物料编码: mat.materialCode,
        可用量: String(mat.available),
        可支撑产量: `${mat.maxSupportQty} 台`,
        是否充足: mat.sufficient ? '是' : '否'
      }))
  }

  list.push(evidenceItem('ev-eq', 'MES', '设备', 'EQ-POOL', '设备资源池',
    `【设备数据】设备上限 ${cap.equipmentLimit ?? '-'} 台，分配 ${result.totalMachines ?? '-'} 台。`,
    90, ['equipment', 'allocate'], {
      设备上限: `${cap.equipmentLimit ?? '-'} 台`,
      分配设备: `${result.totalMachines ?? '-'} 台`
    }))

  list.push(evidenceItem('ev-hr', 'HR', '人员', 'OP-POOL', '操作员编制',
    `【人员数据】在岗 ${result.availableOperators ?? 0} 人，可行产量 ${cap.operatorLimit ?? '-'} 台。`,
    87, ['operator', 'allocate'], {
      在岗操作员: `${result.availableOperators ?? 0} 人`,
      人员可行产量: `${cap.operatorLimit ?? '-'} 台`
    }))

  list.push(evidenceItem('ev-cap', 'APS', '结论', 'PLAN-RESULT', '排产结论',
    `【排产结论】建议排产 ${result.recommendedPlanQty ?? 0} 台。${result.recommendation || ''}`,
    95, ['result'], {
      建议排产量: `${result.recommendedPlanQty ?? 0} 台`,
      物料可产: `${materialLimit} 台`,
      设备可产: `${cap.equipmentLimit ?? '-'} 台`,
      人员上限: `${operatorHeadcount || '-'} 人`,
      人员可行产量: `${cap.operatorLimit ?? '-'} 台`,
      综合可行产量: `${cap.feasibleQty ?? '-'} 台`
    }))

  return list
}

function buildDispatchStepsFromResult(result) {
  if (!result) return []
  const recs = result.recommendations || []
  const recLines = recs.map((r) =>
    `${r.processStep} → ${r.recommendedOperatorName} @ ${r.equipmentName}（${r.planQty} 台）`
  )
  return [
    {
      key: 'route', agentName: 'MES · 工艺路线', actionType: '查询', badge: '查询',
      action: '检索 process_route / process_step',
      summary: `从工艺路线表展开「${result.planId}」：排产 ${result.planQuantity ?? '-'} 台，${recs.length} 道工序。`,
      thought: `【查询 MES.process_route】计划 ${result.planId}，产量 ${result.planQuantity} 台。`,
      detailLines: [`计划号：${result.planId}`, `计划数量：${result.planQuantity} 台`, `工序数：${recs.length} 道`],
      section: null
    },
    {
      key: 'equipment', agentName: 'MES · 设备台账', actionType: '查询', badge: '查询',
      action: '检索 equipment / 设备状态',
      summary: '从设备台账按工序类型筛选可用机台。',
      thought: '【查询 MES.equipment】扫描全厂设备，按工序类型匹配。',
      detailLines: ['排除故障/维保机台', '按工序设备类型筛选'],
      section: 'table'
    },
    {
      key: 'operator', agentName: 'HR · 人员编制', actionType: '查询', badge: '查询',
      action: '检索 user / 在途派工负荷',
      summary: '从人员编制表统计在岗操作员。',
      thought: '【查询 HR.user】计算岗位匹配度与空闲度。',
      detailLines: recs.map((r) => `候选：${r.recommendedOperatorName}`).slice(0, 5),
      section: 'table'
    },
    {
      key: 'match', agentName: 'APS · 匹配结果', actionType: '取证', badge: '取证',
      action: '沉淀工序-人员-设备匹配证据',
      summary: `完成 ${recs.length} 道工序匹配证据沉淀。`,
      thought: `【汇总匹配证据】生成 ${recs.length} 条推荐。`,
      detailLines: recLines,
      section: 'table'
    },
    {
      key: 'result', agentName: 'APS · 派工结论', actionType: '汇总', badge: '汇总',
      action: '综合多源证据输出派工方案',
      summary: `综合数据库证据，${recs.length} 道工序推荐完成。`,
      thought: result.summary || '派工推荐完成。',
      detailLines: [result.summary || '确认后将生成工单并派工'],
      section: 'result'
    }
  ]
}

function buildDispatchEvidenceFromResult(result) {
  if (!result) return []
  const recs = result.recommendations || []
  const list = []
  list.push(evidenceItem('dv-plan', 'APS', '计划', result.planId, '生产计划数据',
    `计划 ${result.planId} 需生产 ${result.planQuantity} 台。`, 95, ['route'], {
      计划号: result.planId,
      计划数量: `${result.planQuantity} 台`,
      工序数: `${recs.length} 道`
    }))
  recs.forEach((r, i) => {
    list.push(evidenceItem(`dv-rec-${i}`, 'AI', '推荐', r.processStep, `${r.processStep} 派工推荐`,
      `${r.processStep} → ${r.recommendedOperatorName} @ ${r.equipmentName}，${r.recommendReason}`,
      86, ['match', 'result'], {
        工序: r.processStep,
        推荐人: r.recommendedOperatorName,
        设备: r.equipmentName,
        派工数量: `${r.planQty} 台`,
        推荐原因: r.recommendReason
      }))
  })
  return list
}

function resolveFlowData(result, template) {
  let steps = result?.schedulingSteps
  let evidence = result?.evidenceBase
  if (!steps?.length) {
    steps = template === DISPATCH_FLOW_TEMPLATE
      ? buildDispatchStepsFromResult(result)
      : buildPlannerStepsFromResult(result)
  }
  if (!evidence?.length) {
    evidence = template === DISPATCH_FLOW_TEMPLATE
      ? buildDispatchEvidenceFromResult(result)
      : buildPlannerEvidenceFromResult(result)
  }
  return { steps, evidence }
}

function mergeSteps(template, serverSteps) {
  if (!serverSteps?.length) {
    return template.map((s) => ({
      ...s,
      agentName: agentNameForKey(s.key, template),
      actionType: defaultBadgeForKey(s.key),
      badge: defaultBadgeForKey(s.key),
      title: s.title,
      action: s.title,
      summary: '正在检索数据库证据…',
      thought: '正在检索数据库证据…',
      detail: '正在检索数据库证据…',
      detailLines: [],
      evidenceCount: 0
    }))
  }
  return serverSteps.map((s, i) => ({
    key: s.key || template[i]?.key || `step-${i}`,
    title: s.title || s.action || template[i]?.title || `步骤 ${i + 1}`,
    action: s.action || s.title || template[i]?.title || '',
    summary: s.summary || s.thought || s.detail || '',
    detail: s.detail || s.thought || '',
    detailLines: s.detailLines || [],
    evidenceCount: s.evidenceCount ?? 0,
    agentName: s.agentName || agentNameForKey(s.key || template[i]?.key, template),
    actionType: s.actionType || defaultBadgeForKey(s.key || template[i]?.key),
    badge: s.badge || s.actionType || defaultBadgeForKey(s.key || template[i]?.key),
    thought: s.thought || s.detail || s.summary || '',
    section: template.find((t) => t.key === s.key)?.section
      ?? template[i]?.section
      ?? null
  }))
}

function appendEvidenceForStep(allEvidence, revealed, stepKey) {
  const related = allEvidence.filter((ev) => (ev.relatedSteps || []).includes(stepKey))
  const merged = [...revealed]
  for (const ev of related) {
    if (!merged.some((e) => e.id === ev.id)) {
      merged.push(ev)
    }
  }
  return merged.length ? merged : revealed
}

export function useSchedulingFlow(template = PLANNER_FLOW_TEMPLATE) {
  const activeStep = ref(-1)
  const activeStepKey = ref('')
  const flowSteps = ref([])
  const thoughtStream = ref([])
  const evidenceList = ref([])
  const allEvidence = ref([])
  const currentDetail = ref('')
  const visibleSections = ref(new Set())
  const isRunning = ref(false)
  const selectedStepKey = ref('')

  let timers = []

  function clearTimers() {
    timers.forEach(clearTimeout)
    timers = []
  }

  function reset() {
    clearTimers()
    activeStep.value = -1
    activeStepKey.value = ''
    selectedStepKey.value = ''
    flowSteps.value = []
    thoughtStream.value = []
    evidenceList.value = []
    allEvidence.value = []
    currentDetail.value = ''
    visibleSections.value = new Set()
    isRunning.value = false
  }

  function revealSection(section) {
    if (!section) return
    const next = new Set(visibleSections.value)
    next.add(section)
    visibleSections.value = next
  }

  function delay(ms) {
    return new Promise((resolve) => {
      timers.push(setTimeout(resolve, ms))
    })
  }

  function patchThoughtStep(index, patch) {
    const next = [...thoughtStream.value]
    if (!next[index]) return
    next[index] = { ...next[index], ...patch }
    thoughtStream.value = next
  }

  async function typewriterStepSummary(index, fullText, charMs = 20) {
    const text = fullText || ''
    if (!text) return
    for (let i = 1; i <= text.length; i += 1) {
      const slice = text.slice(0, i)
      patchThoughtStep(index, { displaySummary: slice })
      await delay(charMs)
    }
  }

  async function streamEvidenceForStep(stepKey, evidence, evidenceMs = 220) {
    const related = evidence.filter((ev) => (ev.relatedSteps || []).includes(stepKey))
    for (const ev of related) {
      if (!evidenceList.value.some((e) => e.id === ev.id)) {
        evidenceList.value = [...evidenceList.value, ev]
        await delay(evidenceMs)
      }
    }
  }

  async function animateThoughtStream(steps, evidence, options = {}) {
    const {
      stepPauseMs = 700,
      charMs = 20,
      evidenceMs = 220
    } = typeof options === 'number' ? { stepPauseMs: options } : options

    thoughtStream.value = []
    evidenceList.value = []

    for (let i = 0; i < steps.length; i += 1) {
      const step = { ...steps[i], displaySummary: '' }
      thoughtStream.value = [...thoughtStream.value, step]
      const index = thoughtStream.value.length - 1

      activeStep.value = i + 1
      activeStepKey.value = step.key
      selectedStepKey.value = step.key
      currentDetail.value = '正在分析…'

      const summaryText = step.summary || step.thought || step.detail || ''
      await typewriterStepSummary(index, summaryText, charMs)
      await streamEvidenceForStep(step.key, evidence, evidenceMs)
      revealSection(step.section)
      if (i < steps.length - 1) {
        await delay(stepPauseMs)
      }
    }

    evidenceList.value = [...evidence]
  }

  function finishWithResult(result) {
    const { steps: rawSteps, evidence } = resolveFlowData(result, template)
    const serverSteps = mergeSteps(template, rawSteps)
    flowSteps.value = serverSteps
    allEvidence.value = evidence
    evidenceList.value = evidence
    activeStep.value = flowSteps.value.length
    const last = flowSteps.value[flowSteps.value.length - 1]
    activeStepKey.value = last?.key || 'result'
    selectedStepKey.value = last?.key || 'result'
    currentDetail.value = last?.summary || last?.thought || last?.detail || result?.summary || '排产分析完成'
    thoughtStream.value = [...flowSteps.value]
    template.forEach((s) => revealSection(s.section))
    revealSection('result')
  }

  async function runAnimatedPreview(loadFn, options = {}) {
    const flowOptions = typeof options === 'number'
      ? { stepPauseMs: options }
      : {
          stepPauseMs: options.stepPauseMs ?? options.stepMs ?? 500,
          charMs: options.charMs ?? 20,
          evidenceMs: options.evidenceMs ?? 200,
          ...options
        }
    clearTimers()
    isRunning.value = true
    activeStep.value = 0
    activeStepKey.value = ''
    selectedStepKey.value = ''
    visibleSections.value = new Set()
    thoughtStream.value = []
    evidenceList.value = []
    allEvidence.value = []
    flowSteps.value = mergeSteps(template, null)
    currentDetail.value = template === DISPATCH_FLOW_TEMPLATE
      ? '正在接入工艺路线、设备台账与人员编制等数据库…'
      : '正在接入订单、库存、设备与人员等数据库证据…'

    try {
      const result = await loadFn()
      clearTimers()
      const { steps: rawSteps, evidence } = resolveFlowData(result, template)
      const steps = mergeSteps(template, rawSteps)
      flowSteps.value = steps
      allEvidence.value = evidence
      await animateThoughtStream(steps, evidence, flowOptions)
      finishWithResult(result)
      return result
    } catch (e) {
      clearTimers()
      currentDetail.value = e?.message || '排产分析失败'
      throw e
    } finally {
      isRunning.value = false
    }
  }

  function showCompletedResult(result) {
    finishWithResult(result)
  }

  function selectStep(key) {
    selectedStepKey.value = key
    activeStepKey.value = key
  }

  return {
    activeStep,
    activeStepKey,
    selectedStepKey,
    flowSteps,
    thoughtStream,
    evidenceList,
    allEvidence,
    currentDetail,
    visibleSections,
    isRunning,
    reset,
    runAnimatedPreview,
    showCompletedResult,
    revealSection,
    selectStep
  }
}

export { PLANNER_FLOW_TEMPLATE, DISPATCH_FLOW_TEMPLATE }
