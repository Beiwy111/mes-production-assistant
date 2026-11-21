// Shared by Axios and fetch. The failed request must belong to the current login.
let handler = null
export function configureSessionExpiry({ getToken, clearSession, onExpired }) {
  const current = (requestToken, code) => {
    if (Number(code) !== 401 || !requestToken || getToken() !== requestToken) return false
    clearSession() // Synchronous: parallel 401 responses cannot prompt/redirect twice.
    onExpired()
    return true
  }
  handler = current
  return () => { if (handler === current) handler = null }
}
export function notifySessionExpired(requestToken, code) {
  return handler?.(requestToken, code) || false
}
