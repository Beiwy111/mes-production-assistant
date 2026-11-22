import { readWorkbenchResponse } from './agentWorkbench.js'

async function request(token, route, signal, body) {
  const response = await fetch(`/api/assistant-v2/chat/${route}`, {
    method: body ? 'POST' : 'GET',
    headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
    credentials: 'same-origin', cache: 'no-store', signal,
    ...(body ? { body: JSON.stringify(body) } : {}),
  })
  return readWorkbenchResponse(response, token)
}

export const modelChatStatus = token => request(token, 'status', AbortSignal.timeout(6000))
export const modelChatTurn = (token, body, signal) => request(token, 'turn', signal, body)
