const rows = (value) => Array.isArray(value) ? value.filter(row => row && typeof row === 'object' && !Array.isArray(row)) : []
const text = (value) => typeof value === 'string' || typeof value === 'number' ? String(value).trim() : ''
const amount = (value, max = Infinity) => {
  if (!/^\d+(?:\.\d+)?$/.test(text(value))) return null
  const n = Number(value)
  return Number.isFinite(n) && n <= max ? n : null
}
const qualityGroup = row => [row.material_name || row.material_id, row.unit, row.inspection_category, row.inspection_type].map(text).filter(Boolean).join(' · ')
const equipmentGroup = row => [row.equipment_name || row.equipment_code || row.equipment_id, row.alarm_type, row.alarm_level, row.status].map(text).filter(Boolean).join(' · ')

export function analyticalQuestion(result) {
  const facts = result?.facts || {}
  if (facts.quality?.presentationMode === 'ANALYSIS') return true
  if (facts.quality?.presentationMode === 'LIST' && Object.keys(facts).length === 1) return false
  const question = text(result?.userQuery)
  if (/列表|列出|清单|明细|逐条/.test(question) && !/分析|趋势|对比|比较|为什么|建议|统计|分布/.test(question)) return false
  if (/分析|为什么|为何|原因|风险|异常点|瓶颈|趋势|分布|统计|概况|总结|诊断|追溯|溯源|改善|改进|建议|比较|对比|环比|同比|良率|合格率|缺陷率|质量怎么样/.test(question)) return true
  return Boolean(facts.inspection_trace || facts.quality?.traceConclusion || facts.quality?.batchComparison?.length ||
    facts.shared_material || facts.capacity_conflicts || facts.order?.crossOrderRisk ||
    facts.maintenance?.maintenanceSummary?.length || facts.alarms?.alarmTrend?.length ||
    facts.quality?.qualityTrend?.length || (!question && facts.quality?.qualityStatistics) ||
    result?.analyses?.some(item => item?.findings?.length || item?.actions?.length || item?.hypotheses?.length))
}

// Keep independent products, units and inspection types on independent series.
// A null day explicitly breaks the curve across gaps in daily records.
function dailySeries(source, dayKey, valueKey, unit, idFields, labelOf) {
  const groups = new Map()
  for (const row of rows(source)) {
    const day = text(row[dayKey])
    if (!/^\d{4}-\d{2}-\d{2}$/.test(day)) continue
    const timestamp = Date.parse(day + 'T00:00:00Z')
    if (!Number.isFinite(timestamp) || new Date(timestamp).toISOString().slice(0, 10) !== day) continue
    const id = JSON.stringify(idFields.map(key => row[key] ?? null))
    if (!groups.has(id)) groups.set(id, { label: labelOf(row), dates: new Map() })
    const dates = groups.get(id).dates
    dates.set(timestamp, dates.has(timestamp) ? null : amount(row[valueKey], unit === '%' ? 100 : Infinity))
  }
  return Array.from(groups.values()).flatMap(({ label, dates }) => {
    const sorted = [...dates].sort(([a], [b]) => a - b)
    if (!label || sorted.filter(([, value]) => value !== null).length < 2) return []
    const points = []
    let last = null
    for (const [at, value] of sorted) {
      if (last !== null && at - last > 86400000) points.push([last + 86400000, null])
      points.push([at, value])
      last = at
    }
    return [{ label, points }]
  })
}

function bars(title, unit, source, key, label) {
  const points = rows(source).flatMap(row => {
    const name = label(row), value = amount(row[key])
    return name && value !== null ? [[name, value]] : []
  }).sort((a, b) => b[1] - a[1])
  if (points.length < 2 || !points.some(([, value]) => value > 0)) return null
  return { kind: 'bar', title: title + (points.length > 8 ? '（前 8 项）' : ''), unit,
    series: [{ points: points.slice(0, 8) }] }
}

export function analysisVisuals(message) {
  const result = message?.result
  const metrics = [], charts = []
  if (!result || message.evidenceInvalidated || message.workflow?.invalidated || result.historyEvidenceOmitted ||
      result.verdict === 'DATA_CHANGED' || !analyticalQuestion(result)) return { metrics, charts }
  const facts = result.facts || {}, quality = facts.quality || {}, stats = quality.qualityStatistics
  for (const [key, label] of [['inspectionCount', '检验记录'], ['validRecordCount', '有效抽检记录'],
    ['pendingCount', '待检记录'], ['conflictCount', '数据冲突记录']]) {
    const value = amount(stats?.[key])
    if (Number.isSafeInteger(value)) metrics.push({ label, value, unit: '条' })
  }
  const sampleGroups = rows(stats?.sampleGroups)
  if (sampleGroups.length === 1) {
    const sample = sampleGroups[0], denominator = amount(sample.valid_sample_quantity)
    const rate = amount(sample.sample_fail_percent, 100)
    if (denominator > 0 && rate !== null) metrics.push({ label: '抽检不合格率', value: text(sample.sample_fail_percent),
      unit: '%', detail: [qualityGroup(sample), '有效样本 ' + text(sample.valid_sample_quantity) + text(sample.unit)].filter(Boolean).join(' · ') })
  }
  const qualityDay = dailySeries(quality.qualityTrend, 'inspection_day', 'sample_fail_percent', '%',
    ['material_id', 'unit', 'inspection_category', 'inspection_type'], qualityGroup)
  if (qualityDay.length) charts.push({ kind: 'line', title: '每日抽检不合格率', unit: '%', series: qualityDay })
  const defects = bars('缺陷记录分布', '条', stats?.defectDistribution, 'record_count', row => text(row.defect_type))
  if (defects) charts.push(defects)
  const alarmDay = dailySeries(facts.alarms?.alarmTrend, 'alarm_day', 'alarm_record_count', '条',
    ['equipment_id', 'alarm_type', 'alarm_level', 'status'], equipmentGroup)
  if (alarmDay.length) charts.push({ kind: 'line', title: '每日报警记录数', unit: '条', series: alarmDay })
  const maintenance = bars('设备登记停机时长', '分钟', facts.maintenance?.maintenanceSummary,
    'recorded_downtime_minutes', row => [row.equipment_name, row.equipment_code || row.equipment_id].map(text).filter(Boolean).join(' · '))
  if (maintenance) charts.push(maintenance)
  for (const [domain, label] of [['production', '工单'], ['order', '订单'], ['equipment', '设备']]) {
    const overview = facts[domain]?.overview
    if (!overview || overview.truncated || overview.complete === false || !Array.isArray(overview.rows)) continue
    const records = rows(overview.rows)
    if (!records.length || amount(overview.totalRecords) !== records.length || amount(overview.returnedRecords) !== records.length) continue
    const ids = records.map(row => text(row.record_id))
    if (ids.includes('') || new Set(ids).size !== records.length) continue
    const counts = new Map()
    for (const row of records) {
      const status = text(row.status) || '未登记状态'
      counts.set(status, (counts.get(status) || 0) + 1)
    }
    metrics.push({ label: label + '记录', value: records.length, unit: '条' })
    const distribution = bars(label + '状态分布', '条', Array.from(counts, ([status, count]) => ({ status, count })), 'count', row => row.status)
    if (distribution) charts.push(distribution)
  }
  return { metrics, charts }
}
