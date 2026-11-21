/** 与 Vue/登录状态无关的 NDJSON 读取器，兼容原助手事件。 */
export async function consumeAssistantStream(body, { onDelta, onEvent, signal } = {}) {
  const reader = body.getReader()
  const decoder = new TextDecoder('utf-8')
  let buffer = '', result = null, ended = false
  const abort = () => { reader.cancel().catch(() => {}) }
  signal?.addEventListener('abort', abort, { once: true })
  const checkAbort = () => { if (signal?.aborted) throw new DOMException('已停止等待', 'AbortError') }
  const consume = line => {
    if (!line.trim()) return
    const event = JSON.parse(line)
    if (event.code && event.code !== 200) {
      const error = new Error(event.message || '请求失败')
      error.code = event.code
      throw error
    }
    if (event.type === 'error') throw new Error(event.message || '流式请求失败')
    onEvent?.(event)
    if (event.type === 'delta') onDelta?.(event.text || '')
    else if (event.type === 'result') result = event.data
  }
  try {
    checkAbort()
    while (true) {
      const { value, done } = await reader.read()
      checkAbort()
      buffer += decoder.decode(value || new Uint8Array(), { stream: !done })
      if (buffer.length > 2_097_152) throw new Error('流式响应过大，请缩小时间或关键词范围后重试')
      let newline
      while ((newline = buffer.indexOf('\n')) >= 0) {
        consume(buffer.slice(0, newline))
        buffer = buffer.slice(newline + 1)
      }
      if (done) { ended = true; break }
    }
    if (buffer.trim()) consume(buffer)
    if (!result) throw new Error('流式响应未返回最终结果')
    return result
  } finally {
    signal?.removeEventListener('abort', abort)
    if (!ended) await reader.cancel().catch(() => {})
    reader.releaseLock()
  }
}
