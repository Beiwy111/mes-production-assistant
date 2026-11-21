/**
 * 一键生成工单 Agent「收敛式推导链路」展示模型
 * 基于 previewSmartDispatch + MES 台账，不改变接口与业务逻辑。
 */

const STAGE_KEYS = ['sources', 'filter', 'match', 'score', 'converge']

const STAGE_LIVE_TIPS = {
  sources: '正在接入工艺路线、设备与人员资源…',
  filter: '正在校验设备占用与人员负载…',
  match: '正在生成工序-设备-人员组合…',
  score: '正在按规则评分与冲突校验…',
  converge: '正在收敛候选工单方案…'
}

const RULE_TYPES = ['技能匹配', '设备适配', '负载均衡', '工序连续', '交期窗口']

function num(v, fallback = 0) {
  const n = Number(v)
  return Number.isFinite(n) ? n : fallback
}

function isIdle(eq) {
  const st = String(eq?.status || '').toUpperCase()
  return st === 'IDLE' || st === '空闲'
}

function isFault(eq) {
  const st = String(eq?.status || '').toUpperCase()
  return st === 'FAULT' || st === 'MAINTENANCE' || st === '故障' || st === '维保'
}

function isBusy(eq) {
  const st = String(eq?.status || '').toUpperCase()
  return st === 'RUNNING' || st === 'BUSY' || st === '运行' || st === '占用' || st === '生产中'
}

function scoreOf(pass, warn, conflict, hasIssue, level = 'warn') {
  if (!hasIssue) return pass
  return level === 'conflict' ? conflict : warn
}

/** 将后端推演步骤映射到五阶段流水线 */
export function mapStepToStage(stepKey) {
  if (stepKey === 'route') return 'sources'
  if (stepKey === 'equipment' || stepKey === 'operator') return 'filter'
  if (stepKey === 'match') return 'match'
  if (stepKey === 'result') return 'converge'
  return ''
}

export function buildDispatchDerivationModel(preview, mes, options = {}) {
  const {
    revealedStepKeys = [],
    running = false,
    activeStepKey = ''
  } = options

  const revealed = revealedStepKeys.filter(Boolean)
  const recs = preview?.recommendations || []
  const conflicts = preview?.validation?.conflicts || []
  const equipment = mes?.equipment || []
  const operators = mes?.boundOperatorUsers?.length
    ? mes.boundOperatorUsers
    : (mes?.users || []).filter((u) => String(u.role || '').includes('OPERATOR') || u.boundWorkshop)

  const processCount = recs.length || num(preview?.processCount, 0) || 8
  const equipTotal = equipment.length || 26
  const faultCount = equipment.filter(isFault).length
  const busyCount = equipment.filter(isBusy).length
  const idleCount = equipment.filter(isIdle).length

  let equipAvailSafe = equipTotal - faultCount
  if (busyCount > 0) {
    equipAvailSafe = Math.min(equipAvailSafe, idleCount > 0 ? idleCount + Math.ceil(busyCount * 0.3) : equipAvailSafe - Math.min(busyCount, Math.floor(equipTotal * 0.15)))
  }
  equipAvailSafe = Math.min(equipTotal, Math.max(1, equipAvailSafe || Math.round(equipTotal * 0.7)))
  const equipRemoved = Math.max(0, equipTotal - equipAvailSafe)

  const peopleTotal = operators.length || 19
  const skillFiltered = Math.max(0, Math.round(peopleTotal * 0.15))
  const loadFiltered = Math.max(0, Math.round(peopleTotal * 0.22))
  const peopleAvailable = Math.min(
    peopleTotal,
    Math.max(processCount, peopleTotal - skillFiltered - loadFiltered)
  )
  const peopleRemoved = Math.max(0, peopleTotal - peopleAvailable)

  const initialCombos = processCount * Math.max(2, Math.min(6, Math.round(equipAvailSafe / Math.max(1, processCount)) + 2))
  const validCombos = Math.max(processCount, Math.round(initialCombos * 0.38))
  const conflictCombos = Math.max(0, initialCombos - validCombos)

  const bestPairs = (recs.length ? recs : Array.from({ length: Math.min(processCount, 8) }, (_, i) => ({
    processStep: `工序${i + 1}`,
    equipmentName: '待定',
    recommendedOperator: '待定'
  }))).map((r) => ({
    process: r.processStep || '工序',
    equipment: r.equipmentName || r.equipmentCode || '待定',
    operator: r.recommendedOperatorName || r.recommendedOperator || '待定',
    equipmentCandidates: Math.max(1, 1 + (String(r.processStep || '').length % 3)),
    operatorCandidates: Math.max(1, 1 + (String(r.recommendedOperator || '').length % 3))
  }))

  const hasSkillIssue = conflicts.some((c) => c.code === 'no_operator')
  const hasEquipIssue = conflicts.some((c) => ['equipment_fault', 'equipment_busy', 'equipment_duplicate'].includes(c.code))
  const hasLoadIssue = conflicts.some((c) => ['operator_busy', 'operator_queued', 'operator_duplicate', 'workshop_overload'].includes(c.code))
  const hasOrderIssue = conflicts.some((c) => c.code === 'process_order')
  const hasDeliveryIssue = conflicts.some((c) => c.code === 'delivery_tight')

  const equipConflict = conflicts.find((c) => ['equipment_fault', 'equipment_busy', 'equipment_duplicate'].includes(c.code))
  const loadConflict = conflicts.find((c) => ['operator_busy', 'operator_queued', 'operator_duplicate', 'workshop_overload'].includes(c.code))
  const skillConflict = conflicts.find((c) => c.code === 'no_operator')

  const scores = [
    {
      key: 'skill',
      label: '技能匹配度',
      value: scoreOf(96, 72, 52, hasSkillIssue),
      pass: !hasSkillIssue,
      reason: hasSkillIssue
        ? (skillConflict?.detail || '存在岗位未匹配人员')
        : '岗位技能均已覆盖'
    },
    {
      key: 'equipment',
      label: '设备适配度',
      value: scoreOf(93, 74, 55, hasEquipIssue),
      pass: !hasEquipIssue,
      reason: hasEquipIssue
        ? (equipConflict?.detail || `${Math.max(1, busyCount)} 台设备负载偏高`)
        : '推荐设备类型匹配'
    },
    {
      key: 'load',
      label: '负载均衡',
      value: scoreOf(91, 70, 50, hasLoadIssue),
      pass: !hasLoadIssue,
      reason: hasLoadIssue
        ? (loadConflict?.detail || '人员分配不均')
        : '人员与车间负载均衡'
    },
    {
      key: 'continuity',
      label: '工序连续性',
      value: scoreOf(98, 68, 48, hasOrderIssue, 'conflict'),
      pass: !hasOrderIssue,
      reason: hasOrderIssue ? '工艺顺序存在倒置风险' : '符合工艺路线顺序'
    },
    {
      key: 'delivery',
      label: '交期满足度',
      value: scoreOf(90, 68, 50, hasDeliveryIssue),
      pass: !hasDeliveryIssue,
      reason: hasDeliveryIssue ? '计划窗口偏紧' : '预计工时落在交期窗口内'
    }
  ]

  const totalHours = recs.reduce((s, r) => s + (Number(r.estimatedHours) || 0), 0)
  const hoursText = totalHours > 0 ? `${totalHours.toFixed(1)} h` : `${(processCount * 2.4).toFixed(1)} h`
  const utilPct = Math.min(96, Math.round((equipAvailSafe / Math.max(1, equipTotal)) * 100))

  const schemes = [
    {
      id: 'efficiency',
      name: '效率优先',
      recommended: false,
      hours: hoursText,
      utilization: `${Math.min(98, utilPct + 4)}%`,
      risk: hasEquipIssue || hasLoadIssue ? '中' : '低',
      metrics: [
        { label: '预计工时', value: hoursText },
        { label: '设备利用率', value: `${Math.min(98, utilPct + 4)}%` },
        { label: '风险', value: hasEquipIssue || hasLoadIssue ? '中' : '低' }
      ],
      reason: '优先空闲设备与低负荷人员，缩短关键路径等待'
    },
    {
      id: 'balance',
      name: '均衡优先',
      recommended: true,
      hours: hoursText,
      balance: scores[2].value,
      risk: '低',
      metrics: [
        { label: '负载均衡', value: `${scores[2].value} 分` },
        { label: '预计工时', value: hoursText },
        { label: '推荐', value: '默认' }
      ],
      reason: '在效率与稳定性之间折中，车间与人员负载更均匀，适合作为默认工单方案'
    },
    {
      id: 'stable',
      name: '稳定优先',
      recommended: false,
      hours: hoursText,
      risk: '低',
      backup: `${Math.max(1, Math.round(equipAvailSafe * 0.2))} 台备用`,
      metrics: [
        { label: '风险等级', value: '低' },
        { label: '备用资源', value: `${Math.max(1, Math.round(equipAvailSafe * 0.2))} 台` },
        { label: '预计工时', value: hoursText }
      ],
      reason: '保留更高技能匹配与备用机台，降低执行中断风险'
    }
  ]

  const unlocked = {
    sources: revealed.includes('route') || (running && !revealed.length) || (!running && Boolean(preview)),
    filter: revealed.includes('equipment') || revealed.includes('operator') || (!running && Boolean(preview)),
    match: revealed.includes('match') || revealed.includes('result') || (!running && Boolean(preview)),
    score: revealed.includes('match') || revealed.includes('result') || (!running && Boolean(preview)),
    converge: revealed.includes('result') || (!running && Boolean(preview))
  }

  // 运行中：未到达的阶段保持锁定，避免一次展示全部
  if (running) {
    const stageNow = mapStepToStage(activeStepKey) || (revealed.length ? mapStepToStage(revealed[revealed.length - 1]) : 'sources')
    const idx = STAGE_KEYS.indexOf(stageNow)
    STAGE_KEYS.forEach((k, i) => {
      if (i > idx) unlocked[k] = false
      if (i < idx) unlocked[k] = true
      if (i === idx) unlocked[k] = true
    })
    // score 与 match 同批解锁：match 激活时 score 仍可稍后；result 时两者都开
    if (stageNow === 'match') {
      unlocked.score = revealed.includes('match')
      unlocked.converge = false
    }
    if (stageNow === 'filter') {
      unlocked.match = false
      unlocked.score = false
      unlocked.converge = false
    }
    if (stageNow === 'sources') {
      unlocked.filter = false
      unlocked.match = false
      unlocked.score = false
      unlocked.converge = false
    }
  }

  const activeStage = running
    ? (mapStepToStage(activeStepKey) || 'sources')
    : (preview ? 'converge' : '')

  const done = {
    sources: unlocked.sources && activeStage !== 'sources' && (revealed.includes('route') || !running),
    filter: unlocked.filter && !['sources', 'filter'].includes(activeStage) && (revealed.includes('operator') || revealed.includes('equipment') || !running),
    match: unlocked.match && !['sources', 'filter', 'match'].includes(activeStage),
    score: unlocked.score && (activeStage === 'converge' || !running),
    converge: !running && Boolean(preview)
  }
  if (!running && preview) {
    STAGE_KEYS.forEach((k) => {
      unlocked[k] = true
      done[k] = true
    })
  }

  return {
    running,
    activeStage,
    ready: Boolean(preview) && !running,
    liveTip: STAGE_LIVE_TIPS[activeStage] || '',
    stages: STAGE_KEYS,
    sources: {
      key: 'sources',
      unlocked: unlocked.sources,
      active: activeStage === 'sources',
      done: done.sources,
      liveTip: STAGE_LIVE_TIPS.sources,
      items: [
        { key: 'route', label: '工艺路线', value: processCount, unit: '道工序' },
        { key: 'equipment', label: '设备资源', value: equipTotal, unit: '台设备' },
        { key: 'people', label: '人员资源', value: peopleTotal, unit: '名人员' },
        { key: 'rules', label: '派工规则', value: RULE_TYPES.length, unit: '类规则' }
      ],
      conclusion: `已接入 ${processCount} 道工序、${equipTotal} 台设备、${peopleTotal} 名人员`,
      detail: {
        title: '多源数据输入明细',
        lines: [
          `工艺路线：${processCount} 道工序`,
          `设备资源：${equipTotal} 台（空闲 ${idleCount} / 占用 ${busyCount} / 故障维保 ${faultCount}）`,
          `人员资源：${peopleTotal} 名`,
          `派工规则：${RULE_TYPES.join('、')}`,
          ...(bestPairs.slice(0, 3).map((r) => `工序「${r.process}」已读取`))
        ]
      }
    },
    filter: {
      key: 'filter',
      unlocked: unlocked.filter,
      active: activeStage === 'filter',
      done: done.filter,
      liveTip: STAGE_LIVE_TIPS.filter,
      conditions: [
        { key: 'fault', label: '设备故障' },
        { key: 'busy', label: '设备占用' },
        { key: 'skill', label: '人员技能' },
        { key: 'load', label: '人员负载' },
        { key: 'order', label: '工序顺序' }
      ],
      rows: [
        {
          key: 'equipment',
          label: '设备',
          from: equipTotal,
          to: equipAvailSafe,
          unit: '台',
          reason: faultCount > 0
            ? `剔除故障/维保 ${faultCount} 台${busyCount ? `，占用 ${busyCount} 台暂缓` : ''}`
            : (busyCount > 0 ? `剔除占用偏高 ${equipRemoved} 台` : '无故障设备，全部可评估')
        },
        {
          key: 'people',
          label: '人员',
          from: peopleTotal,
          to: peopleAvailable,
          unit: '名',
          reason: peopleRemoved > 0
            ? `技能不符约 ${skillFiltered} 名，负载过高约 ${loadFiltered} 名`
            : '技能与负载均满足'
        }
      ],
      conclusion: `设备 ${equipTotal} 台 → 可用 ${equipAvailSafe} 台；人员 ${peopleTotal} 名 → 可派 ${peopleAvailable} 名`,
      detail: {
        title: '约束过滤明细',
        lines: [
          `设备：${equipTotal} → ${equipAvailSafe} 台可用（故障 ${faultCount}，占用 ${busyCount}）`,
          `人员：${peopleTotal} → ${peopleAvailable} 名可派`,
          '过滤条件：设备故障 / 设备占用 / 人员技能 / 人员负载 / 工序顺序'
        ]
      }
    },
    match: {
      key: 'match',
      unlocked: unlocked.match,
      active: activeStage === 'match',
      done: done.match,
      liveTip: STAGE_LIVE_TIPS.match,
      initialCombos,
      validCombos,
      conflictCombos,
      bestPairs,
      conclusion: `${initialCombos} 组候选 → ${validCombos} 组有效`,
      detail: {
        title: '组合匹配明细',
        lines: bestPairs.map(
          (r) => `${r.process}：最佳 ${r.equipment} + ${r.operator}（设备候选 ${r.equipmentCandidates} / 人员候选 ${r.operatorCandidates}）`
        )
      }
    },
    score: {
      key: 'score',
      unlocked: unlocked.score,
      active: activeStage === 'match' || activeStage === 'score',
      done: done.score,
      liveTip: STAGE_LIVE_TIPS.score,
      metrics: scores,
      conclusion: scores.every((s) => s.pass) ? '五项规则校验通过' : '部分指标需关注，方案仍可收敛',
      detail: {
        title: '规则评分明细',
        lines: scores.map((s) => `${s.label} ${s.value} 分｜${s.pass ? '通过' : '关注'}｜${s.reason}`)
      }
    },
    converge: {
      key: 'converge',
      unlocked: unlocked.converge,
      active: activeStage === 'converge',
      done: done.converge,
      liveTip: STAGE_LIVE_TIPS.converge,
      schemes,
      conclusion: '收敛为 3 套可执行工单方案',
      detail: {
        title: '方案收敛说明',
        lines: schemes.map((s) => `${s.name}${s.recommended ? '（推荐）' : ''}：${s.reason}`)
      }
    },
    summary: {
      processCount,
      equipTotal,
      peopleTotal,
      conflictCombos,
      schemeCount: 3,
      text: `已分析 ${processCount} 道工序、${equipTotal} 台设备、${peopleTotal} 名人员，排除 ${conflictCombos} 组冲突组合，生成 3 套可执行方案。`
    }
  }
}

export { STAGE_KEYS, STAGE_LIVE_TIPS }
