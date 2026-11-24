import { notifySessionExpired } from '../utils/sessionExpiry.js'
const BASE = '/api/assistant-v2/orders'

async function request(token, path, data, { signal } = {}) {
  const response = await fetch(`${BASE}${path}`, {
    method: data === undefined ? 'GET' : 'POST',
    credentials: 'same-origin', cache: 'no-store', signal,
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
    ...(data === undefined ? {} : { body: JSON.stringify(data) }),
  })
  const body = await response.json().catch(() => null)
  if (!response.ok || !body || (body.code && body.code !== 200)) {
    const error = new Error(body?.message || `订单服务暂不可用（${response.status}）`)
    error.code = body?.code || response.status
    notifySessionExpired(token, response.status === 401 ? 401 : error.code)
    throw error
  }
  return body.code === 200 ? body.data : body
}

export const orderStatus = (token) => request(token, '/status', undefined, { signal: AbortSignal.timeout(6000) })
export const orderTurn = (token, data, options) => request(token, '/turn', data, options)
export const getOrderDraft = (token, id) => request(token, `/drafts/${encodeURIComponent(id)}`)
export const reviseOrderDraft = (token, id, data, options) => request(token, `/drafts/${encodeURIComponent(id)}/revise`, data, options)
export const confirmOrderDraft = (token, id, data, options) => request(token, `/drafts/${encodeURIComponent(id)}/confirm`, data, options)
export const cancelOrderDraft = (token, id, data, options) => request(token, `/drafts/${encodeURIComponent(id)}/cancel`, data, options)
