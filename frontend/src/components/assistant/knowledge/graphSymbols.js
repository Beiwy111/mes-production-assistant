import { nodeTypes } from '@/utils/workbenchGraph'

const drawings = {
  document: '<path d="M6 3h8l4 4v14H6z M14 3v5h4 M9 12h6 M9 16h6"/>',
  chunk: '<path d="M8 5H5v14h3 M16 5h3v14h-3 M9 9h6 M9 13h6"/>',
  vector: '<circle cx="5" cy="16" r="2"/><circle cx="12" cy="6" r="2"/><circle cx="19" cy="13" r="2"/><path d="m6 14 5-6m3 0 4 4"/>',
  entity: '<path d="m12 3 8 5v9l-8 4-8-4V8z M4 8l8 5 8-5 M12 13v8"/>',
  concept: '<path d="M8 14a6 6 0 1 1 8 0c-1.4 1.2-2 2.2-2 3h-4c0-.8-.6-1.8-2-3z M10 20h4"/>',
  relation: '<path d="m10 14 4-4 M8 16l-1 1a3.6 3.6 0 0 1-5-5l4-4a3.6 3.6 0 0 1 5 0 M16 8l1-1a3.6 3.6 0 0 1 5 5l-4 4a3.6 3.6 0 0 1-5 0"/>',
  tag: '<path d="M3 4h9l8 8-8 8-9-9z"/><circle cx="8" cy="8" r="1.3"/>',
}
function shape(symbol) {
  if (symbol === 'circle') return '<circle cx="24" cy="24" r="18"/>'
  if (symbol === 'diamond') return '<path d="m24 4 20 20-20 20L4 24z" stroke-linejoin="round"/>'
  if (symbol === 'triangle') return '<path d="M7 9h19l15 15-15 15H7z" stroke-linejoin="round"/>'
  return '<rect x="6" y="6" width="36" height="36" rx="'+(symbol === 'rect' ? 5 : 9)+'"/>'
}
const symbols = new Map()
export function graphSymbol(type, selected) {
  const key = type + Number(selected)
  if (!symbols.has(key)) {
    const { color, background, symbol } = nodeTypes[type] || nodeTypes.entity
    const solid = type === 'document' || type === 'entity' || selected
    const halo = selected ? '<g transform="translate(-3.6 -3.6) scale(1.15)" fill="white" stroke="#163B85" stroke-width="1.4">'+shape(symbol)+'</g>' : ''
    const svg = '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 48 48" width="144" height="144">'+halo+
      '<g fill="'+(solid ? color : background)+'" stroke="'+color+'" stroke-width="1.8">'+shape(symbol)+'</g>'+
      '<g transform="translate(12 12)" fill="none" stroke="'+(solid ? '#fff' : color)+'" stroke-linecap="round" stroke-linejoin="round" stroke-width="2">'+
      (drawings[type] || drawings.entity)+'</g></svg>'
    symbols.set(key,'image://data:image/svg+xml;charset=utf-8,'+encodeURIComponent(svg))
  }
  return symbols.get(key)
}
