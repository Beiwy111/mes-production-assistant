import request from '@/utils/request'
import { useUserStore } from '@/stores/user'
import { consumeAssistantStream } from '@/utils/assistantStream'
import { notifySessionExpired } from '../utils/sessionExpiry.js'

/** 语音 → 文本（multipart 上传 16k WAV 录音；mock 模式返回样例文本） */
export function asr(blob) {
  const fd = new FormData()
  fd.append('file', blob, 'cmd.wav')
  return request.post('/assistant/asr', fd)
}

/** 文本 → 意图（读=直接应答；写=返回待确认提议 {type:'confirm',proposalId,...}；module=当前页面模块，供意图偏置） */
export function interpret(data) {
  // 全局助手每轮需分析全厂快照和完整对话；0 表示不由浏览器端中断模型思考。
  return request.post('/assistant/interpret', data, { timeout: 0 })
}

/**
 * 文本 → NDJSON 问答。旧链路可逐段输出；Dify 链路输出状态和成功后的最终回答。
 * onDelta 每收到一段纯文本立即触发；最终返回与 interpret 相同的 answer/confirm 结构。
 */
export async function interpretStream(data, { onDelta, onEvent, signal } = {}) {
  const userStore = useUserStore()
  const token = userStore.token
  const headers = {
    'Content-Type': 'application/json',
    Accept: 'application/x-ndjson',
    ...(token ? { Authorization: `Bearer ${token}` } : {})
  }
  let runId = null
  let cancelSent = false
  const cancelRemote = () => {
    if (!runId || cancelSent) return
    cancelSent = true
    // 独立请求，不继承已 abort 的 signal；仅取消该登录用户自己的运行。
    fetch(`/api/assistant/v2/runs/${encodeURIComponent(runId)}/cancel`, {
      method: 'POST', credentials: 'same-origin', headers, keepalive: true
    }).catch(() => {})
  }
  signal?.addEventListener('abort', cancelRemote, { once: true })
  try {
    const response = await fetch('/api/assistant/interpret/stream', {
      method: 'POST',
      credentials: 'same-origin',
      headers,
      signal,
      body: JSON.stringify({ ...data, requestId: data.requestId || globalThis.crypto?.randomUUID?.() || `${Date.now()}-${Math.random().toString(36).slice(2)}` })
    })

    if (!response.ok) {
      notifySessionExpired(token, response.status)
      let message = `请求失败（HTTP ${response.status}）`
      try {
        const errorBody = await response.json()
        message = errorBody?.message || message
      } catch { /* 非 JSON 错误响应 */ }
      throw new Error(message)
    }
    if (!response.body) throw new Error('当前浏览器不支持流式响应')

    return await consumeAssistantStream(response.body, {
      signal, onDelta,
      onEvent(event) {
        if (event.type === 'run') {
          runId = event.runId
          if (signal?.aborted) cancelRemote()
        }
        onEvent?.(event)
      }
    })
  } catch (error) {
    notifySessionExpired(token, error.code)
    cancelRemote()
    throw error
  } finally {
    signal?.removeEventListener('abort', cancelRemote)
  }
}

export function fetchAssistantRun(runId) {
  return request.get(`/assistant/v2/runs/${encodeURIComponent(runId)}`)
}

/** 人工闸门决策 → 执行（decision: APPROVE / MODIFY / SKIP；多步流时返回 next 自动推进） */
export function execute(data) {
  return request.post('/assistant/execute', data)
}

/** 跨模块协办通知列表（GlobalBusinessMonitor 轮询并入通知中心，按角色过滤） */
export function fetchVoiceNotices() {
  return request.get('/assistant/notices')
}
