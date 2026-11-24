// Only a completed, single-work-order business read can anchor a photo comparison.
export function precedingWorkRun(messages) {
  for (const m of [...messages].reverse()) {
    const scope = m.result?.scope
    const table = m.result?.facts?.production?.overview
    if (!table && !scope?.workOrderNo && !scope?.qualityWorkOrders?.length) continue
    if (m.status !== 'COMPLETED' || m.evidenceInvalidated || m.workflow?.invalidated ||
        m.result?.verdict !== 'EVIDENCE_READY' || !scope?.confirmed) return null
    const single = table ? !table.truncated && table.rows?.length === 1 :
      Boolean(scope.workOrderNo || scope.qualityWorkOrders?.length === 1)
    return single ? m.runId : null
  }
  return undefined
}

export function imageFollowup(messages, text) {
  const upload = [...messages].reverse().find(m => m.imageUpload || m.imageRef)
  if (!upload?.imageRef || upload.status !== 'COMPLETED') return null
  // Explicit identifiers, writes and unrelated business reads keep their normal routing.
  if (/\b(?:WO|QI|SO)[-_]?\d|(?:提交|修改|删除|更新|创建|新增|登记|保存|绑定|安排|下单)/i.test(text)) return null
  const recentImage = messages.findLast(m => m.role === 'assistant')?.imageQuestion
  const mentionsPhoto = /照片|图片|图中|这张图|检测框|视觉|YOLO/i.test(text)
  let action
  if ((mentionsPhoto || (recentImage && !/库存|派工|计划|不同工单|两张工单/.test(text))) &&
      /(?:工单|质检|检验)/.test(text) && /一致|吻合|符合|相同|比较|比对|对比|核对|对得上|匹配/.test(text)) action = 'compare'
  else if ((mentionsPhoto || recentImage) && !/哪道工序|哪个工序|哪一环节|哪个批次/.test(text) &&
      /(?:划痕|划伤|缺陷|检测框|瑕疵).*(?:哪里|哪儿|哪[个位一块边处]|位置|坐标)|(?:哪里|哪儿|哪个位置).*(?:划痕|划伤|缺陷)/.test(text)) action = 'location'
  else if (!/工单|订单|批次|库存|质检记录|售后|排产|派工/.test(text)) {
    if (/(?:这|那|上|刚才|之前)(?:一?张)?(?:的)?(?:图|照片)|(?:图|照片)(?:片)?(?:里|中|上)|画面/.test(text) ||
        (recentImage && /^(?:那么|那)?(?:它|这是什么|能看出|看得出|什么颜色|什么品牌|再仔细看看|能再具体|怎么验证|如何复查|怎么复查|怎么排查|为什么|可能是什么原因|有何影响|意味着什么|说明什么|这说明|这意味着|先查哪|(?:这[些个处]?|那[些个处]?)?(?:斑点|亮斑|污点|浅色颗粒).*(?:位置|在哪|原因|怎么|是什么))/.test(text))) action = 'describe'
  }
  if (!action) return null
  const laterWork = action === 'compare' ? precedingWorkRun(messages.slice(messages.indexOf(upload) + 1)) : undefined
  return { imageRef: upload.imageRef, action, laterWork }
}

export function comparePhoto(count, result) {
  const ids = result?.scope?.qualityWorkOrders
  const rows = result?.facts?.quality?.overview?.rows
  if (result?.verdict !== 'EVIDENCE_READY' || !Array.isArray(ids) || ids.length !== 1 ||
      !Array.isArray(rows) || rows.some(r => String(r.work_order_id) !== String(ids[0])))
    return '没有取得这张工单可核验的质检明细，本次无法判断是否一致。'
  if (!rows.length) return '当前权限范围内没有查到这张工单的检验单，无法与照片核对。'
  const matches = rows.filter(r => String(r.recorded_defect_types || '').split('、')
    .some(type => /^(?:(?:外观|表面|屏幕|面板|边框|玻璃)(?:表面)?)?划[伤痕]$|^scratch(?:ed|es)?$/i.test(type.trim())))
  const records = matches.map(r => r.inspection_no).join('、')
  if (count > 0 && matches.length)
    return `检验单 ${records} 也记录了划伤或划痕，缺陷类别相符。照片尚未绑定这张检验单，无法确认是同一台产品或同一处划痕。`
  if (count === 0 && matches.length)
    return `检验单 ${records} 记录了划伤或划痕，本次照片没有检出。照片与抽检产品的对应关系仍需核实。`
  if (count == null) return '照片缺少可核验的 YOLO 检测结果，无法与检验记录比较划痕。'
  const types = [...new Set(rows.map(r => r.recorded_defect_types).filter(Boolean))]
  const description = types.length ? `关联不合格品登记的类别为「${types.join('、')}」。` : '关联不合格品没有登记缺陷类别。'
  if (count === 0) return `工单 ${rows[0].work_order_no} 的检验记录中，${description}本次照片未检出划痕，记录也未明确登记划痕；两者覆盖的产品和检查范围仍无法对应。`
  return `工单 ${rows[0].work_order_no} 的检验记录中，${description}现有类别和抽检数量不足以确认与照片中的划痕一致。`
}
