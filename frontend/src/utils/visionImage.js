/** 上传 YOLO 前压缩图片，避免手机原图 3000px+ 导致推理与传输过慢 */
export function compressImageForYolo(file, { maxSide = 1280, quality = 0.85 } = {}) {
  if (!file?.type?.startsWith('image/')) return Promise.resolve(file)
  return new Promise((resolve, reject) => {
    const url = URL.createObjectURL(file)
    const img = new Image()
    img.onload = () => {
      URL.revokeObjectURL(url)
      const { width, height } = img
      const longest = Math.max(width, height)
      let w = width
      let h = height
      if (longest > maxSide) {
        const scale = maxSide / longest
        w = Math.round(width * scale)
        h = Math.round(height * scale)
      }
      const canvas = document.createElement('canvas')
      canvas.width = w
      canvas.height = h
      const ctx = canvas.getContext('2d')
      if (!ctx) {
        resolve(file)
        return
      }
      ctx.drawImage(img, 0, 0, w, h)
      canvas.toBlob(
        (blob) => {
          if (!blob) {
            resolve(file)
            return
          }
          const base = file.name.replace(/\.[^.]+$/, '') || 'image'
          resolve(new File([blob], `${base}.jpg`, { type: 'image/jpeg', lastModified: Date.now() }))
        },
        'image/jpeg',
        quality
      )
    }
    img.onerror = () => {
      URL.revokeObjectURL(url)
      resolve(file)
    }
    img.src = url
  })
}

/** 有限并发执行任务 */
export async function mapWithConcurrency(items, limit, worker) {
  const results = new Array(items.length)
  let cursor = 0
  async function run() {
    while (cursor < items.length) {
      const idx = cursor++
      results[idx] = await worker(items[idx], idx)
    }
  }
  const runners = Array.from({ length: Math.min(limit, items.length) }, () => run())
  await Promise.all(runners)
  return results
}
