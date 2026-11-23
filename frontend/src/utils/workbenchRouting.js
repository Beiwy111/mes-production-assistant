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
  if (previous.orderFlow && !['SUBMITTED', 'CANCELLED'].includes(previous.orderFlow.orderDraft?.status))
    return { context: 'ORDER' }
  const question = messages.at(-2)
  if (previous.directChat && previous.status === 'COMPLETED' && question?.role === 'user' &&
      !question.orderMessage && question.text && previous.text) {
    return { context: 'GENERAL', history: { user: question.text.slice(0,2000), assistant: previous.text.slice(0,4000) } }
  }
  return { context: 'BUSINESS' }
}

