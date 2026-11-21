import test from 'node:test'
import assert from 'node:assert/strict'
import { renderWorkbenchMarkdown } from './workbenchMarkdown.ts'

test('SSR without a DOM safely escapes content instead of returning unsanitized markup', () => {
  const html = renderWorkbenchMarkdown('<img src=x onerror=alert(1)>\n<script>alert(2)</script>')
  assert.doesNotMatch(html, /<img|<script/)
  assert.match(html, /&lt;img/)
})
