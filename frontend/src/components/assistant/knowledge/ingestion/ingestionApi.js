const base = '/api/assistant-v2/knowledge/ingestion'
export const maxFileSize = 10 * 1024 * 1024
export const extensions = ['pdf','doc','docx','ppt','pptx','xls','xlsx','csv','txt','md','markdown','html','htm','jpg','png','jpeg']
export function validateFile(file) {
  const name = file.name || ''
  if (!name.trim() || name.length > 200 || /[\\/:\u0000-\u001f]/u.test(name)) return '请使用不含目录或控制字符的有效文件名（最多 200 个字符）'
  const suffix = name.match(/^.+\.([^.]+)$/)?.[1].toLowerCase()
  if (!extensions.includes(suffix)) return '暂不支持此文件类型'
  if (!file.size) return '文件为空'
  if (file.size > maxFileSize) return '文件超过 10 MiB'
  return ''
}
function unwrap(data) {
  if (data?.code !== 200) throw new Error(data?.message || '知识服务请求失败')
  return data.data
}
export async function ingestionRequest(token, path, method = 'GET', signal) {
  const response = await fetch(base + path, { method, signal, headers: { Authorization: 'Bearer ' + token } })
  if (!response.ok) throw new Error('知识服务请求失败（' + response.status + '）')
  return unwrap(await response.json())
}
export async function downloadSource(token, id, filename, signal) {
  const response = await fetch(base + '/jobs/' + encodeURIComponent(id) + '/source', {
    headers: { Authorization: 'Bearer ' + token }, signal,
  })
  if (!response.ok || !response.headers.get('Content-Type')?.startsWith('application/octet-stream'))
    throw new Error('无法下载原文件，请重新验证登录状态。')
  const href = URL.createObjectURL(await response.blob())
  const link = document.createElement('a')
  link.href = href
  link.download = filename || '入库资料'
  link.click()
  setTimeout(() => URL.revokeObjectURL(href), 1500)
}
export function uploadFile(token, file, progress, signal) {
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest()
    const abort = () => xhr.abort()
    if (signal.aborted) { reject(new DOMException('已取消', 'AbortError')); return }
    signal.addEventListener('abort', abort, { once: true })
    xhr.onloadend = () => signal.removeEventListener('abort', abort)
    xhr.open('POST', base + '/jobs')
    xhr.setRequestHeader('Authorization', 'Bearer ' + token)
    xhr.timeout = 90000
    xhr.responseType = 'json'
    xhr.upload.onprogress = event => { if (event.lengthComputable) progress(Math.round(event.loaded / event.total * 100)) }
    xhr.onload = () => {
      try { resolve(unwrap(xhr.response)) } catch (err) { reject(err) }
    }
    xhr.onerror = () => reject(new Error('上传连接失败'))
    xhr.ontimeout = () => reject(new Error('上传请求超时，请先刷新文件列表核对'))
    xhr.onabort = () => reject(new DOMException('已取消', 'AbortError'))
    const form = new FormData()
    form.append('file', file)
    xhr.send(form)
  })
}
