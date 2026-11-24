export const isDirectSource = source => source === 'API' || source === 'LOCAL'

export function modelHistory(messages, mode) {
  const history = []
  let length = 0
  // A change of source or retrieval policy starts a new model context. Never forward order
  // details, previously authorized MES rows, or another provider's answers to a model.
  for (let i = messages.length - 1; i >= 1 && history.length < 8; i -= 2) {
    const assistant = messages[i], user = messages[i - 1]
    const kind = assistant.result?.queryMode
    if (user.role !== 'user' || assistant.role !== 'assistant' || assistant.status !== 'COMPLETED' ||
        !['MODEL_CHAT', 'NATURAL'].includes(kind) || user.orderMessage || assistant.orderMessage ||
        [user, assistant].some(m => m.modelSource !== mode.modelSource || m.ragEnabled !== mode.ragEnabled)) break
    const question = user.text?.trim()
    const answer = kind === 'NATURAL' ? '上轮提出了业务查询。需要实时数据时，请按当前账号权限重新查询。' : assistant.text?.trim()
    if (!question || !answer || question.length > 2000 || answer.length > 2000 ||
        length + question.length + answer.length > 10000) break
    history.unshift({ role: 'user', content: question }, { role: 'assistant', content: answer })
    length += question.length + answer.length
  }
  return history
}

export function needsModelOrderTurn(text, messages) {
  const lastFlow = messages.findLast(m => m.orderFlow?.orderDraft)?.orderFlow.orderDraft
  if (lastFlow && ['COLLECTING', 'READY'].includes(lastFlow.status)) return true
  if (/如何|怎么|流程|步骤|介绍|解释|为什么/.test(text)) return false
  return /下单|订购|购买|帮我订|(?:买|订|下)\s*\d+\s*(?:台|个|件)|创建订单|新建订单|取消草稿/.test(text)
}
