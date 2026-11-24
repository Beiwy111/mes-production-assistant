import { consumeAssistantStream } from '@/utils/assistantStream'
import { consumeTaskEvents } from '../utils/taskEventStream.js'
import { notifySessionExpired } from '../utils/sessionExpiry.js'

const BASE = '/api/assistant-workbench'
const errorMessages = {
  UNAUTHORIZED: '登录已失效，请重新登录。',
  ADMIN_REQUIRED: '此预览接口仅向管理员开放，请使用自然语言查询。',
  BUSINESS_PERMISSION_REQUIRED: '当前账号没有所需业务权限，或客户身份尚未绑定。',
  PERMISSION_CHANGED: '账号权限或客户归属已变更，请新建对话重新查询。',
  ORDER_NOT_ALLOWED: '这张订单尚未加入新助手的可查询范围。',
  INVALID_REQUEST: '请填写问题后重试；如需查询特定订单，可在问题中说明订单号。',
  NATURAL_LANGUAGE_NOT_SUPPORTED: '当前协作服务尚未升级为自然语言查询，请联系维护人员升级后重新连接。',
  NATURAL_LANGUAGE_UNAVAILABLE: '自然语言查询暂不可用，请稍后重新连接。',
  PREVIOUS_RUN_UNAVAILABLE: '上一轮查询已过期或无法访问，请开始新对话并重新说明查询对象。',
  PREVIOUS_RUN_EXPIRED: '上一轮查询已过期，请开始新对话并重新说明查询对象。',
  INVALID_PREVIOUS_RUN: '无法继续上一轮查询，请开始新对话并重新说明查询对象。',
  INVALID_PLAN: '总协调未能确定查询范围，请补充说明后重试。',
  REQUEST_ALREADY_SUBMITTED: '该请求已提交，请查看原运行结果，避免重复执行。',
  CAPACITY_REACHED: '协作服务正在处理其他任务，请稍后再试。',
  WORKBENCH_NOT_CONFIGURED: '请先完成总协调 Agent 的应用密钥配置。',
  WORKBENCH_UNAVAILABLE: '协作服务暂不可用，请检查连接后重试。',
  RUN_UNAVAILABLE: '该运行已过期或无法访问，请发起新查询。',
  RUN_TIMED_OUT: '本次协作超时，已请求停止，请稍后重试。',
  RUN_CANCELLED: '本次运行已取消。',
  WORKFLOW_FAILED: '本次协作未能完成，请查看运行状态或重新查询。',
  DIFY_WORKFLOW_FAILED: '总协调工作流未能完成，请检查工作流运行记录。',
  DIFY_EVENT_LIMIT: '协作返回的数据超出当前接入限额，请联系维护人员。',
  DIFY_STREAM_LIMIT: '协作返回的数据超出当前接入限额，请联系维护人员。',
  DIFY_UNAVAILABLE: '模型编排服务响应中断，请检查服务连接。',
  DEPENDENCY_RESPONSE_LIMIT: '查询结果超出当前传输容量，请缩小时间或关键词范围后重试；未将截断结果当作完整记录返回。',
}
const humanMessage = (message) => errorMessages[message] || message
const headers = (token) => ({
  'Content-Type': 'application/json',
  Authorization: `Bearer ${token}`,
})
async function readResponse(response, token) {
  const body = await response.json().catch(() => null)
  if (!response.ok || (body?.code && body.code !== 200)) {
    const error = new Error(
      humanMessage(body?.message) || `服务暂不可用（${response.status}）`,
    )
    error.code = body?.code || response.status
    notifySessionExpired(token, response.status === 401 ? 401 : error.code)
    throw error
  }
  return body?.code === 200 ? body.data : body
}
export { readResponse as readWorkbenchResponse }
export async function workbenchStatus(token) {
  return readResponse(
    await fetch(`${BASE}/status`, {
      headers: headers(token),
      cache: 'no-store',
      signal: AbortSignal.timeout(6000),
    }),
    token,
  )
}
export async function workbenchRun(token, runId) {
  return readResponse(
    await fetch(`${BASE}/runs/${encodeURIComponent(runId)}`, {
      headers: headers(token),
      cache: 'no-store',
    }),
    token,
  )
}
export async function cancelWorkbenchRun(token, runId) {
  return readResponse(
    await fetch(`${BASE}/runs/${encodeURIComponent(runId)}/cancel`, {
      method: 'POST',
      headers: headers(token),
      body: '{}',
      keepalive: true,
    }),
    token,
  )
}
export async function streamWorkbench(token, input, { onEvent, signal } = {}) {
  let runId = null,
    cancelSent = false
  const cancel = () => {
    if (runId && !cancelSent) {
      cancelSent = true
      cancelWorkbenchRun(token, runId).catch(() => {})
    }
  }
  signal?.addEventListener('abort', cancel, { once: true })
  try {
    const response = await fetch(`${BASE}/runs/stream`, {
      method: 'POST',
      credentials: 'same-origin',
      headers: { ...headers(token), Accept: 'text/event-stream, application/x-ndjson' },
      signal,
      body: JSON.stringify(input),
    })
    if (!response.ok) return await readResponse(response, token)
    const consume = response.headers.get('content-type')?.includes('text/event-stream')
      ? consumeTaskEvents : consumeAssistantStream
    return await consume(response.body, {
      conversationId: input.sessionId,
      signal,
      onEvent: (event) => {
        if (event.type === 'run') {
          runId = event.runId
          if (signal?.aborted) cancel()
        }
        onEvent?.(event)
      },
    })
  } catch (error) {
    notifySessionExpired(token, error.code === 'UNAUTHORIZED' || error.message === 'UNAUTHORIZED' ? 401 : error.code)
    // A broken stream is never retried automatically; best-effort stop of this exact run.
    cancel()
    error.message = humanMessage(error.message)
    throw error
  } finally {
    signal?.removeEventListener('abort', cancel)
  }
}

export async function continueImage(token, input, {signal, onDelta}) {
  const response = await fetch('/api/assistant-v2/images/continue', {
    method: 'POST', headers: headers(token), body: JSON.stringify(input), signal,
  })
  if (!response.ok || !response.headers.get('content-type')?.includes('application/x-ndjson'))
    return readResponse(response, token)
  return consumeAssistantStream(response.body, { signal, onDelta })
}

export async function chatEntry(token, input, {signal, onDelta}) {
  const response = await fetch(`${BASE}/chat-entry`, {
    method: 'POST', headers: headers(token), body: JSON.stringify(input), signal,
  })
  if (!response.ok) return readResponse(response, token)
  return consumeAssistantStream(response.body, {signal, onDelta})
}
