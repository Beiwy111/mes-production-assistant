/** 去掉 Markdown 装饰语法，保留列表与表格结构供 ChatBubble 渲染。 */
export function stripAssistantMarkdown(text) {
  if (text == null || text === '') return ''
  let out = String(text).replace(/\r\n/g, '\n').replace(/\r/g, '\n')

  out = out.replace(/^```[^\n]*\n?/gm, '').replace(/^```\s*$/gm, '')
  out = out.replace(/^\s{0,3}#{1,6}\s+/gm, '')
  out = out.replace(/^\s*>\s?/gm, '')
  out = out.replace(/^\s*\|?\s*:?-{3,}:?\s*(?:\|\s*:?-{3,}:?\s*)+\|?\s*$/gm, '')
  out = out.replace(/^\s*([-*_])(?:\s*\1){2,}\s*$/gm, '')

  out = out.split('\n').map(cleanLine).join('\n')
  return out.replace(/\n{3,}/g, '\n\n').trimEnd()
}

function cleanInline(value) {
  return String(value)
    .replace(/\[([^\]]+)]\(([^)]+)\)/g, '$1（$2）')
    .replace(/\*\*(.*?)\*\*/g, '$1')
    .replace(/__(.*?)__/g, '$1')
    .replace(/~~(.*?)~~/g, '$1')
    .replace(/`([^`]+)`/g, '$1')
    .replace(/(?<!\w)\*([^*\n]+)\*(?!\w)/g, '$1')
}

function cleanLine(line) {
  const trimmed = line.trim()
  if (!trimmed) return ''

  const pipes = (trimmed.match(/\|/g) || []).length
  if (pipes >= 2) {
    let body = trimmed
    if (body.startsWith('|')) body = body.slice(1)
    if (body.endsWith('|')) body = body.slice(0, -1)
    return body.split('|').map((c) => cleanInline(c.trim())).join(' | ')
  }

  const numbered = trimmed.match(/^(\d+[.、)\]]\s*)(.+)$/)
  if (numbered) return `${numbered[1]}${cleanInline(numbered[2])}`

  const bullet = trimmed.match(/^[-+•]\s+(.+)$/) || (!trimmed.startsWith('**') && trimmed.match(/^\*\s+(.+)$/))
  if (bullet) return `- ${cleanInline(bullet[1])}`

  return cleanInline(line)
}

/** @deprecated 使用 stripAssistantMarkdown */
export const toAssistantPlainText = stripAssistantMarkdown
