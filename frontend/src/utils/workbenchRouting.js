/** Fast path only for explicit read questions. Authorization and intent still belong to the server/Supervisor. */
export function prefersReadOnlyQuery(text, { canQuery = false } = {}) {
  if (!canQuery || typeof text !== 'string') return false
  if (/^(?:(?:WO[\w-]+|\d+)(?:这个订单|这张工单|这个工单)?)?(?:工单)?(?:现在)?(?:派给谁了|派工了吗|哪道工序没做完|同批有没有质检异常)[？?。]?$/.test(text.trim())) return true
  if (/下单|订购|采购|购买|下\s*\d+\s*台|新增|新建|修改|更改|改成|取消|删除|提交|保存|派工|排产|安排.*(?:干|做|生产)|收货|地址|备注/.test(text)) return false
  if (/^(?:你好|您好|hello|hi)(?:[，,。！!\s]|$)|介绍(?:一下)?(?:你的|助手的)?功能|你(?:能|可以)(?:做|帮)/i.test(text.trim())) return true
  // A question can go straight to the read-only Supervisor even when it names no business module.
  if (/分别是|是什么|为什么|有(?:什么|哪些)问题|涉及哪|如何理解/.test(text)) return true
  return /查|看|分析|解释|介绍|汇总|统计|有哪些|多少|进度|其中|这些|上述/.test(text) &&
    /库存|物料|工单|生产|订单|质量|质检|缺陷|设备|维修|报警|售后|追溯|知识库|LCD|Mura|停机|流水/i.test(text)
}
export function entryContext(messages) {
  const previous = messages.at(-1)
  if (!previous || previous.role !== 'assistant') return { context: 'NEW' }
  if (previous.productionFlow) return { context: 'PRODUCTION_ACTION' }
  if (previous.orderFlow && !['SUBMITTED', 'CANCELLED'].includes(previous.orderFlow.orderDraft?.status))
    return { context: 'ORDER' }
  const question = messages.at(-2)
  if (previous.directChat && previous.status === 'COMPLETED' && question?.role === 'user' &&
      !question.orderMessage && question.text && previous.text) {
    return { context: 'GENERAL', history: { user: question.text.slice(0,2000), assistant: previous.text.slice(0,4000) } }
  }
  return { context: 'BUSINESS' }
}

// An explicit action, including a polite request, can go directly to a read-only preview.
// The server still resolves the role, validates inputs and requires a separate confirmation.
export function productionPreviewIntent(text, previous) {
  if (typeof text !== 'string') return false
  if (previous?.productionFlow?.taskManaged &&
      /^(?:那就|选择|选|我要|我选|按)?(?:全部|全量|重新)?(?:新生产|新投产|重新生产)(?:吧|[。！!])?$/.test(text.trim())) return true
  if (previous?.productionFlow?.taskManaged && /^(?:继续|接着|回到刚才|改为|改成|调整|换成|就按|选第|用第|那就|按刚才)/.test(text.trim()) &&
      !/天气|查询|查一下|查看|不要|取消|质检情况|库存多少/.test(text)) return true
  if (/查|看看|进度|状态|情况|记录|明细|有没有|是否|了吗|哪|几|多少|如何|怎么|为什么|是什么|谁|解释|分析|不要|别(?:再)?|暂不|取消排产|取消派工/.test(text)) return false
  if (/排产|排程|智能派工|安排.{0,10}派工|(?:分派|派给|指派给).{1,15}|发布.{0,20}计划|(?:生成|创建|制定|编制|做)(?:一下|一份|个|一版)?生产计划|(?:接收|开始|催办|提醒).{0,18}派工|(?:计划|订单).{0,30}提交.{0,8}(?:计划员|主管)|审核.{0,20}(?:订单|CO[\d-])|领齐物料|(?:我要|帮我|提交|登记|给派工).{0,24}(?:完工|报工)/i.test(text)) return true
  return previous?.productionFlow?.status === 'NEEDS_INPUT' &&
    /^(?:(?:订单|计划|派工|工单)(?:号|编号)?\s*[:：]?\s*)?(?:(?:CO|PP|DT|WO)[\w-]+|\d{4}-\d{2}-\d{2}(?:\s*(?:至|到)\s*\d{4}-\d{2}-\d{2})?|通过|驳回|暂缓|补充|交期优先|负载均衡|成本优先|DELIVERY|BALANCE|COST)$/i.test(text.trim())
}

export function productionPreviewContext(previous, text) {
  const flow = previous?.productionFlow
  // The server checks the detected command/target before merging these scoped inputs.
  if (flow?.status === 'NEEDS_INPUT') {
    return { actionCode: flow.actionCode, fields: flow.fields || {} }
  }
  // A reference can only come from the immediately preceding confirmed transaction.
  // A number explicitly mentioned by the user always takes precedence.
  if (/(?:CO|PP|DT|WO)[\d_-]/i.test(text)) return {}
  const result = flow?.status === 'EXECUTED' ? flow.result : null
  const params = flow?.status === 'EXECUTED' ? flow.confirmation?.params : null
  const submitted = previous?.orderFlow?.orderDraft
  const orderId = result?.orderId || params?.orderId || (submitted?.status === 'SUBMITTED' && submitted.result?.orderNo)
  const planId = result?.planId || params?.planId
  const referent = '(?:这(?:个|张|份|条)?|该|刚(?:刚)?(?:创建|保存|生成|提交)(?:的)?)'
  if (orderId && new RegExp(referent + '订单').test(text)) return { fields: { orderId } }
  if (planId && new RegExp(referent + '(?:生产)?计划').test(text)) return { fields: { planId } }
  const workOrderNo = result?.workOrderId || params?.workOrderNo
  if (workOrderNo && new RegExp(referent + '工单').test(text)) return { fields: { workOrderNo } }
  const dispatchId = result?.dispatchId || params?.dispatchId || (result?.dispatches?.length === 1 && result.dispatches[0]?.id)
  if (dispatchId && new RegExp(referent + '派工').test(text)) return { fields: { dispatchId } }
  return {}
}

