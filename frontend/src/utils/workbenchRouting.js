/** Fast path only for explicit read questions. Authorization and intent still belong to the server/Supervisor. */
export function prefersReadOnlyQuery(text, { canQuery = false } = {}) {
  if (!canQuery || typeof text !== 'string') return false
  if (/下单|订购|采购|购买|下\s*\d+\s*台|新增|新建|修改|更改|改成|取消|删除|提交|保存|派工|排产|安排.*(?:干|做|生产)|收货|地址|备注/.test(text)) return false
  if (/^(?:你好|您好|hello|hi)(?:[，,。！!\s]|$)|介绍(?:一下)?(?:你的|助手的)?功能|你(?:能|可以)(?:做|帮)/i.test(text.trim())) return true
  return /查|看|分析|解释|介绍|汇总|统计|有哪些|多少|进度|其中|这些|上述/.test(text) &&
    /库存|物料|工单|生产|订单|质量|质检|缺陷|设备|维修|报警|售后|追溯|知识库|LCD|Mura|停机|流水/i.test(text)
}
