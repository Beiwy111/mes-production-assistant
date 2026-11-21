import { notifySessionExpired } from '../utils/sessionExpiry.js'
const MAX_IMAGE_BYTES = 10 * 1024 * 1024
const VARIANTS = ['original', 'input', 'annotated']
const IMAGE_MIMES = ['image/jpeg', 'image/png']
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i
const aborted = () => new DOMException('图片请求已取消', 'AbortError')

export function originalImageNotice(file) {
  if (!IMAGE_MIMES.includes(file?.type)) return '原图未保存：仅支持 JPEG/PNG；检测仍继续。'
  if (!(file.size > 0) || file.size > MAX_IMAGE_BYTES) return '原图未保存：文件为空或超过 10 MiB；检测仍继续。'
  return ''
}

// Never use a URL supplied by an evidence record, or put credentials in URLs.
export async function fetchVisionImage(record, variant, token, { signal } = {}) {
  if (!token) throw new Error('请登录后查看图片。')
  if (!/^[1-9]\d{0,18}$/.test(String(record?.inspectionId)) || !UUID.test(record?.recordId || '') || !VARIANTS.includes(variant)) {
    throw new Error('图片归档标识无效，请重新查询。')
  }
  const controller = new AbortController()
  const cancel = () => controller.abort()
  signal?.addEventListener('abort', cancel, { once: true })
  if (signal?.aborted) cancel()
  let timedOut = false
  const timer = setTimeout(() => { timedOut = true; cancel() }, 12000)
  try {
    if (controller.signal.aborted) throw aborted()
    const response = await fetch(`/api/quality/vision/evidence/${record.inspectionId}/${record.recordId}/${variant}`, {
      headers: { Authorization: `Bearer ${token}`, Accept: 'image/jpeg, image/png' },
      signal: controller.signal, cache: 'no-store', redirect: 'error', credentials: 'omit',
    })
    if (controller.signal.aborted) throw aborted()
    if (!response.ok) {
      notifySessionExpired(token, response.status)
      const message = ({ 401: '登录已失效，请重新登录。', 403: '当前账号无权查看图片，需要质检或管理员权限。',
        404: '图片未保存或已不可读取，请重新查询。', 409: '图片证据已变化，请重新查询。' })[response.status]
      throw new Error(message || '图片暂时无法读取，请稍后重试。')
    }
    const mime = (response.headers.get('Content-Type') || '').split(';')[0].trim().toLowerCase()
    if (!IMAGE_MIMES.includes(mime)) throw new Error('归档图片格式无效，无法预览。')
    if (Number(response.headers.get('Content-Length')) > MAX_IMAGE_BYTES) throw new Error('归档图片超过预览大小限制。')
    const reader = response.body?.getReader()
    if (!reader) throw new Error('归档图片内容为空。')
    const chunks = []
    let size = 0
    try {
      while (true) {
        const { done, value } = await reader.read()
        if (controller.signal.aborted) throw aborted()
        if (done) break
        size += value.byteLength
        if (size > MAX_IMAGE_BYTES) throw new Error('归档图片超过预览大小限制。')
        chunks.push(value)
      }
    } finally {
      await reader.cancel().catch(() => {})
      reader.releaseLock()
    }
    if (!size) throw new Error('归档图片内容为空。')
    return new Blob(chunks, { type: mime })
  } catch (error) {
    if (signal?.aborted) throw aborted()
    if (timedOut) throw new Error('图片读取超时，请稍后重试。')
    // Only our user-facing errors are displayed; never expose response bodies or network diagnostics.
    if (/^(请登录|图片归档|登录已|当前账号|图片未|图片证据|图片暂时|归档图片)/.test(error?.message || '')) throw error
    throw new Error('图片读取失败，请稍后重试。')
  } finally {
    clearTimeout(timer)
    controller.abort()
    signal?.removeEventListener('abort', cancel)
  }
}

// Own object URLs independently of records/history. Closing also invalidates late responses.
export function createVisionImageSession() {
  let generation = 0
  const requests = new Set()
  const urls = new Set()
  return {
    async load(record, variant, token) {
      const current = generation
      const controller = new AbortController()
      requests.add(controller)
      try {
        const blob = await fetchVisionImage(record, variant, token, { signal: controller.signal })
        if (current !== generation || controller.signal.aborted) throw aborted()
        const url = URL.createObjectURL(blob)
        urls.add(url)
        return url
      } finally { requests.delete(controller) }
    },
    close() {
      generation++
      for (const request of requests) request.abort()
      requests.clear()
      for (const url of urls) URL.revokeObjectURL(url)
      urls.clear()
    },
  }
}
