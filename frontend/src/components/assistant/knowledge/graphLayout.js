const goldenAngle = Math.PI * (3 - Math.sqrt(5))

// Give each connected component its own rectangle, proportional to its node count.
function pack(groups, x, y, width, height, place) {
  if (groups.length === 1) {
    place(groups[0], x, y, width, height)
    return
  }
  const total = groups.reduce((sum, group) => sum + group.length, 0)
  let split = 1, weight = groups[0].length
  while (split < groups.length - 1 && weight + groups[split].length / 2 < total / 2) {
    weight += groups[split++].length
  }
  const fraction = weight / total
  if (width > height) {
    pack(groups.slice(0, split), x, y, width * fraction, height, place)
    pack(groups.slice(split), x + width * fraction, y, width * (1 - fraction), height, place)
  } else {
    pack(groups.slice(0, split), x, y, width, height * fraction, place)
    pack(groups.slice(split), x, y + height * fraction, width, height * (1 - fraction), place)
  }
}

// A bounded Fruchterman–Reingold pass starts at deterministic positions. It runs
// on page changes, so selecting a node does not restart an animated simulation.
function relax(ids, adjacent, width, height) {
  const count = ids.length
  const index = new Map(ids.map((id, i) => [id, i]))
  const points = ids.map((_, i) => {
    const radius = Math.sqrt(i / count) * 0.48
    return { x: width * radius * Math.cos(i * goldenAngle), y: height * radius * Math.sin(i * goldenAngle) }
  })
  const edges = []
  ids.forEach((id, i) => {
    for (const neighbor of adjacent.get(id)) {
      const j = index.get(neighbor)
      if (j > i) edges.push([i, j])
    }
  })
  const ideal = Math.sqrt(width * height / count)
  const passes = count > 100 ? 28 : 72
  for (let step = 0; step < passes; step++) {
    const dx = new Float64Array(count), dy = new Float64Array(count)
    for (let i = 0; i < count; i++) {
      for (let j = i + 1; j < count; j++) {
        const x = points[i].x - points[j].x, y = points[i].y - points[j].y
        const force = ideal * ideal / Math.max(0.01, x * x + y * y)
        dx[i] += x * force
        dx[j] -= x * force
        dy[i] += y * force
        dy[j] -= y * force
      }
    }
    for (const [i, j] of edges) {
      const x = points[i].x - points[j].x, y = points[i].y - points[j].y
      const force = Math.hypot(x, y) / ideal
      dx[i] -= x * force
      dx[j] += x * force
      dy[i] -= y * force
      dy[j] += y * force
    }
    const temperature = ideal * 0.55 * (1 - step / passes)
    points.forEach((point, i) => {
      const scale = Math.min(1, temperature / Math.max(0.01, Math.hypot(dx[i], dy[i])))
      point.x += dx[i] * scale
      point.y += dy[i] * scale
    })
  }
  return points
}

// Reserve room for the icon and its caption. Neighbours remain close to the
// spring layout, while nodes near a component boundary can use spare space.
function separateCaptions(positions, width, height) {
  const points = [...positions.values()]
  const area = width * height / points.length
  const spacingX = Math.min(132, Math.sqrt(area * 1.65))
  const spacingY = Math.min(66, Math.sqrt(area / 1.65))
  for (let pass = 0; pass < 42; pass++) {
    let worst = 0
    for (let i = 0; i < points.length; i++) {
      const a = points[i]
      for (let j = i + 1; j < points.length; j++) {
        const b = points[j]
        const x = a[0] - b[0], y = a[1] - b[1]
        const overlapX = spacingX - Math.abs(x), overlapY = spacingY - Math.abs(y)
        if (overlapX <= 0 || overlapY <= 0) continue
        const axis = overlapX / spacingX < overlapY / spacingY ? 0 : 1
        const overlap = axis === 0 ? overlapX : overlapY
        const limit = axis === 0 ? width : height
        const move = (a[axis] < b[axis] ? -1 : 1) * overlap * 0.51
        a[axis] = Math.max(0, Math.min(limit, a[axis] + move))
        b[axis] = Math.max(0, Math.min(limit, b[axis] - move))
        worst = Math.max(worst, overlap)
      }
    }
    if (worst < 0.5) break
  }
}

export function spatialLayout(nodes, edges, width, height) {
  const positions = new Map()
  if (!nodes.length) return positions
  const adjacent = new Map(nodes.map(node => [node.id, new Set()]))
  for (const { from, to } of edges) {
    if (from !== to && adjacent.has(from) && adjacent.has(to)) {
      adjacent.get(from).add(to)
      adjacent.get(to).add(from)
    }
  }
  const groups = [], seen = new Set()
  for (const { id } of nodes) {
    if (seen.has(id)) continue
    const group = [id]
    seen.add(id)
    for (let i = 0; i < group.length; i++) {
      for (const next of adjacent.get(group[i])) {
        if (!seen.has(next)) {
          seen.add(next)
          group.push(next)
        }
      }
    }
    groups.push(group.sort((a, b) => adjacent.get(b).size - adjacent.get(a).size))
  }
  groups.sort((a, b) => b.length - a.length)
  pack(groups, 0, 0, Math.max(1, width), Math.max(1, height), (ids, x, y, w, h) => {
    if (ids.length === 1) {
      positions.set(ids[0], [x + w / 2, y + h / 2])
      return
    }
    const padX = Math.min(w * 0.20, 45), padY = Math.min(h * 0.20, 36)
    // Large uploads keep every node, but avoid quadratic force calculation.
    const local = ids.length > 600 ? ids.map((_, i) => ({
      x:Math.sqrt(i / ids.length) * Math.cos(i * goldenAngle),
      y:Math.sqrt(i / ids.length) * Math.sin(i * goldenAngle),
    })) : relax(ids, adjacent, w, h)
    const xs = local.map(p => p.x), ys = local.map(p => p.y)
    const minX = Math.min(...xs), minY = Math.min(...ys)
    const extentX = Math.max(1, Math.max(...xs) - minX), extentY = Math.max(1, Math.max(...ys) - minY)
    local.forEach((p, i) => positions.set(ids[i], [
      x + padX + (p.x - minX) / extentX * (w - 2 * padX),
      y + padY + (p.y - minY) / extentY * (h - 2 * padY),
    ]))
  })
  if (nodes.length <= 600) separateCaptions(positions, width, height)
  return positions
}

const baseSizes = { document: 40, entity: 32, concept: 31, relation: 27, vector: 26, chunk: 23, tag: 22 }
export function nodeDiameter(type, degree, selected = false) {
  return (baseSizes[type] || 28) + Math.min(12, Math.sqrt(degree) * 2.2) + (selected ? 7 : 0)
}
