import DOMPurify from 'dompurify'
import { Marked, Renderer } from 'marked'

const escapeHtml = (text: string): string => text.replace(/[&<>"']/g, (character) =>
  ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[character]!)
const renderer = new Renderer()
// Raw model HTML is text, never an additional rendering language.
renderer.html = ({ text }) => escapeHtml(text)
// Remote images are not evidence and must not make tracking/network requests.
renderer.image = ({ text }) => escapeHtml(text)
renderer.link = function ({ href, title, tokens }) {
  const label = this.parser.parseInline(tokens)
  const trimmed = href.trim()
  if (!/^(https?:\/\/|mailto:|\/[^/]|#)/i.test(trimmed) || /[\u0000-\u0020\u007f]/.test(trimmed)) return label
  const external = /^https?:\/\//i.test(trimmed)
  return `<a href="${escapeHtml(trimmed)}"${title ? ` title="${escapeHtml(title)}"` : ''}${external ? ' target="_blank" rel="noopener noreferrer"' : ''}>${label}</a>`
}
const markdown = new Marked({ renderer, gfm: true, breaks: true, async: false })

/** Only this sanitized result may enter v-html; SSR without a DOM falls back to escaped text. */
export function renderWorkbenchMarkdown(content: unknown): string {
  const text = typeof content === 'string' ? content : ''
  if (typeof DOMPurify.sanitize !== 'function') return escapeHtml(text).replace(/\n/g, '<br>')
  return DOMPurify.sanitize(markdown.parse(text) as string, {
    ALLOWED_TAGS: ['p', 'br', 'hr', 'h1', 'h2', 'h3', 'h4', 'h5', 'h6', 'strong', 'em', 'del', 's',
      'blockquote', 'ul', 'ol', 'li', 'pre', 'code', 'a', 'table', 'thead', 'tbody', 'tr', 'th', 'td'],
    ALLOWED_ATTR: ['href', 'title', 'class', 'target', 'rel', 'start', 'align'],
    ALLOW_DATA_ATTR: false,
    ALLOW_ARIA_ATTR: false,
    RETURN_TRUSTED_TYPE: false,
  })
}
