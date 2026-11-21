// Interpret only explicit numeric primary keys; business order numbers need a separate lookup.
export function resolveOrderContext(selected, text, allowed) {
  const matches = [
    ...text.matchAll(/订单\s*(?:ID|主键)?\s*[:：#]?\s*([1-9]\d*)/gi),
  ]
  const ids = [...new Set(matches.map((m) => m[1]))]
  if (ids.length > 1) return { error: 'MULTIPLE_ORDERS' }
  if (
    matches.some((m) =>
      /^\s*(?:[.．]\d|[A-Za-z-]|台|件|个|套|批|(?:和|与|及|、|,|，|\/)\s*\d)/.test(
        text.slice(m.index + m[0].length),
      ),
    )
  ) {
    return { error: 'AMBIGUOUS_ORDER' }
  }
  const current = String(selected || '').trim()
  if (current && ids[0] && current !== ids[0])
    return { error: 'ORDER_CONFLICT' }
  const orderPk = ids[0] || current
  if (!/^[1-9]\d{0,18}$/.test(orderPk)) return { error: 'MISSING_ORDER' }
  if (!allowed.map(String).includes(orderPk))
    return { error: 'ORDER_NOT_ALLOWED' }
  return { orderPk }
}
