/** SSE transport boundary. Keeps task identity/order separate from the existing view reducer. */
const TYPES = new Set(['task_started', 'task_planned', 'task_completed', 'task_failed',
  'task_cancelled', 'agent_started', 'agent_progress', 'agent_completed', 'agent_failed',
  'tool_started', 'tool_completed', 'tool_failed', 'message_started', 'message_delta',
  'message_completed', 'approval_required', 'heartbeat', 'stream_error'])
const LIMIT = 2_097_152
const LEGACY_TYPES = {
  task_started: ['run'], task_planned: ['workflow'], task_completed: ['done'],
  task_failed: ['error'], task_cancelled: ['error'], stream_error: ['error'],
  agent_started: ['workflow', 'agent'], agent_progress: ['workflow', 'agent'],
  agent_completed: ['workflow', 'agent'], agent_failed: ['workflow', 'agent'],
  tool_started: ['workflow'], tool_completed: ['workflow'], tool_failed: ['workflow'],
  message_started: ['snapshot'], message_delta: ['delta'], message_completed: ['result'],
}

export async function consumeTaskEvents(body, { conversationId, onEvent, signal, idleTimeoutMs = 45_000 } = {}) {
  const reader = body.getReader(), decoder = new TextDecoder('utf-8', { fatal: true })
  let buffer = '', data = [], eventName = '', eventId = '', frameSize = 0
  let taskId, sequence = 0, result, resultEvent, completed = false, ended = false
  const seen = new Map()
  const checkAbort = () => { if (signal?.aborted) throw new DOMException('已停止等待', 'AbortError') }
  const abort = () => { reader.cancel().catch(() => {}) }
  signal?.addEventListener('abort', abort, { once: true })
  const dispatch = () => {
    if (!data.length) { eventName = ''; eventId = ''; frameSize = 0; return }
    const event = JSON.parse(data.join('\n'))
    data = []; frameSize = 0
    if (!TYPES.has(event.type) || !event.event_id || !event.task_id ||
        !Number.isSafeInteger(event.sequence) || event.sequence < 1 ||
        !event.payload || typeof event.payload !== 'object' || Array.isArray(event.payload) ||
        !Number.isFinite(Date.parse(event.timestamp)) ||
        (eventName && eventName !== event.type) || (eventId && eventId !== event.event_id)) {
      throw new Error('执行事件格式不正确')
    }
    eventName = ''; eventId = ''
    if (event.conversation_id !== conversationId) throw new Error('执行事件与当前会话不匹配')
    if (taskId && taskId !== event.task_id) throw new Error('执行事件与当前任务不匹配')
    if (event.sequence <= sequence && seen.get(event.event_id) === event.sequence) return
    if (seen.has(event.event_id) || event.sequence !== sequence + 1 ||
        (!taskId && event.type !== 'task_started') || completed) throw new Error('执行事件顺序不完整，请查询任务状态')
    taskId = event.task_id; sequence = event.sequence; seen.set(event.event_id, sequence)
    const legacy = event.payload.legacy
    if (legacy) {
      if (!LEGACY_TYPES[event.type]?.includes(legacy.type)) throw new Error('执行事件类型与内容格式不一致')
      if ((legacy.runId && legacy.runId !== taskId) ||
          (legacy.data?.runRef && legacy.data.runRef !== taskId)) throw new Error('执行结果与当前任务不匹配')
      const update = { ...legacy, runId: taskId, conversationId, eventId: event.event_id,
        streamType: event.type, eventSequence: sequence }
      if (legacy.type === 'snapshot' || legacy.type === 'delta') update.seq = sequence
      if (event.type === 'message_completed' && legacy.type === 'result') {
        result = legacy.data; resultEvent = update
      } else if (legacy.type !== 'done') onEvent?.(update)
    }
    if (['task_failed', 'task_cancelled', 'stream_error'].includes(event.type)) {
      const error = new Error(event.payload.code || legacy?.message || '执行流中断')
      error.code = legacy?.code || event.payload.code
      error.taskStatus = event.type === 'task_cancelled' ? 'CANCELLED' : 'FAILED'
      throw error
    }
    if (event.type === 'task_completed') {
      if (!result) throw new Error('任务完成但未返回结果')
      onEvent?.(resultEvent)
      onEvent?.({ type: 'done', runId: taskId, conversationId, eventSequence: sequence })
      completed = true
    }
  }
  const line = (value) => {
    if (!value) { dispatch(); return }
    if (value.startsWith(':')) return
    frameSize += value.length
    if (frameSize > LIMIT) throw new Error('执行事件过大，请缩小查询范围')
    const colon = value.indexOf(':'), field = colon < 0 ? value : value.slice(0, colon)
    let content = colon < 0 ? '' : value.slice(colon + 1)
    if (content.startsWith(' ')) content = content.slice(1)
    if (field === 'data') data.push(content)
    if (field === 'event') eventName = content
    if (field === 'id') eventId = content
  }
  try {
    checkAbort()
    while (!completed) {
      let timer
      const idle = new Promise((_, reject) => {
        timer = setTimeout(() => reject(new Error('连接长时间无响应，已保留收到的内容，请查询任务状态')), idleTimeoutMs)
      })
      let chunk
      try { chunk = await Promise.race([reader.read(), idle]) } finally { clearTimeout(timer) }
      const { value, done } = chunk
      checkAbort()
      buffer += decoder.decode(value || new Uint8Array(), { stream: !done })
      if (buffer.length > LIMIT) throw new Error('执行事件过大，请缩小查询范围')
      let index
      while ((index = buffer.search(/[\r\n]/)) >= 0) {
        if (!done && buffer[index] === '\r' && index === buffer.length - 1) break
        const length = buffer[index] === '\r' && buffer[index + 1] === '\n' ? 2 : 1
        line(buffer.slice(0, index)); buffer = buffer.slice(index + length)
      }
      if (done) { ended = true; break }
    }
    if (!completed) throw new Error('连接中断，已保留收到的内容，请查询任务状态')
    return result
  } finally {
    signal?.removeEventListener('abort', abort)
    if (!ended) await reader.cancel().catch(() => {})
    reader.releaseLock()
  }
}
