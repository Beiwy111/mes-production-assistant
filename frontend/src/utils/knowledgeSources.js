const invalid = (message) => !message?.result || message.evidenceInvalidated || message.workflow?.invalidated ||
  message.result.verdict === 'DATA_CHANGED' || message.result.historyEvidenceOmitted

export function knowledgeSources(message) {
  if (invalid(message)) return []
  const seen = new Set(), sources = []
  for (const bundle of message.result.knowledgeEvidence || []) {
    for (const hit of bundle.hits || []) {
      try {
        const url = new URL(hit.source_url)
        if (!['https:', 'http:'].includes(url.protocol) || url.username || url.password ||
            !hit.evidence_ref || seen.has(hit.evidence_ref) || !hit.title || !Number.isInteger(hit.page_start) || hit.page_start < 1) continue
        seen.add(hit.evidence_ref)
        sources.push({ id: `S${sources.length+1}`, ref: hit.evidence_ref, title: hit.title, page: hit.page_start, url: url.href,
          ...(hit.location_type === 'document' ? { location: '文档段落' } : {}),
          type: ({ PUBLIC_RESEARCH:'论文', PUBLIC_MANUAL:'公开手册', PUBLIC_STANDARD:'公开标准' })[hit.authority_type] || '公开资料' })
      } catch { /* An invalid citation is never made into a clickable link. */ }
    }
  }
  return sources
}

export function approvedSopSources(message) {
  if (invalid(message)) return []
  const byRef = new Map()
  for (const bundle of message.result.approvedSopEvidence || []) {
    if (bundle.status !== 'MATCHES') continue
    for (const hit of bundle.hits || []) {
      if (hit.evidence_ref && hit.title && hit.version && hit.section)
        byRef.set(hit.evidence_ref, { ref:hit.evidence_ref, title:hit.title, version:hit.version, section:hit.section })
    }
  }
  return [...byRef.values()]
}

function factTables(value, path = '', out = []) {
  if (Array.isArray(value)) {
    if (value.some(row => row && typeof row === 'object' && !Array.isArray(row))) out.push({ path, rows: value })
  } else if (value && typeof value === 'object') {
    for (const [key, child] of Object.entries(value)) factTables(child, path ? `${path}.${key}` : key, out)
  }
  return out
}
export function canExportResult(message) {
  return !invalid(message) && message.status !== 'RUNNING' && factTables(message.result.facts).length > 0
}
export function resultCsv(message) {
  if (invalid(message)) return null
  const tables = factTables(message.result.facts)
  if (!tables.length) return null
  const cell = (value) => {
    let text = value == null ? '' : typeof value === 'object' ? JSON.stringify(value) : String(value)
    if (/^\s*[=+@\-\t\r]/.test(text)) text = `'${text}`
    return `"${text.replaceAll('"', '""')}"`
  }
  return '\ufeff' + tables.map(table => {
    const keys = [...new Set(table.rows.flatMap(row => row && typeof row === 'object' ? Object.keys(row) : []))]
    return [[table.path], keys, ...table.rows.map(row => keys.map(key => row?.[key]))].map(row => row.map(cell).join(',')).join('\r\n')
  }).join('\r\n\r\n')
}
