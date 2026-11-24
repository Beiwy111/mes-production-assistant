/** Visual-only queue: input must be the accumulated native stream, never a fabricated delta. */
export function createTextStreamBuffer({ onChange, request = requestAnimationFrame,
  cancel = cancelAnimationFrame, now = () => performance.now() }) {
  const segmenter = typeof Intl.Segmenter === 'function' ? new Intl.Segmenter(undefined, { granularity: 'grapheme' }) : null
  let source = '', displayed = '', tail = '', queue = [], offset = 0
  let frame = null, last = now(), credit = 0, deadline = null
  let initialized = false, live = false, frozen = false, disposed = false
  const remaining = () => queue.length - offset
  const publish = () => onChange(displayed, live || remaining() > 0)
  const unschedule = () => { if (frame !== null) cancel(frame); frame = null }
  const clear = () => { unschedule(); queue = []; offset = 0; tail = ''; credit = 0; deadline = null }
  const schedule = () => { if (frame === null && remaining()) frame = request(tick) }
  function tick(time) {
    frame = null
    if (disposed || frozen) return
    const elapsed = Math.max(0, Math.min(64, time - last)); last = time
    // Ordinary deltas read at ~23 graphemes/s. Let large bursts catch up and bound the final tail.
    const windowMs = deadline === null ? 2600 : Math.max(0, deadline - time)
    const interval = Math.min(44, windowMs / Math.max(1, remaining()))
    credit += interval ? elapsed / interval : remaining()
    const count = Math.min(remaining(), Math.floor(credit))
    if (count) {
      displayed += queue.slice(offset, offset + count).join(''); offset += count; credit -= count
      if (offset === queue.length) { queue = []; offset = 0; credit = 0 }
      publish()
    }
    schedule()
  }
  return {
    update(text, { live: nextLive = false, reduced = false, stopped = false, replace = false } = {}) {
      if (disposed) return
      text = typeof text === 'string' ? text : ''
      // Stop consumes no unseen characters; keep authoritative source outside this visual leaf.
      if (initialized && stopped && (live || remaining() || frozen)) {
        clear(); source = text; live = false; frozen = true; publish(); return
      }
      if (!segmenter || reduced || replace || (frozen && !stopped) || (!initialized && !nextLive) || !text.startsWith(source)) {
        clear(); source = displayed = text; live = nextLive; frozen = false; initialized = true; publish(); return
      }
      if (frozen) return
      const additions = text.slice(source.length)
      if (additions || (!nextLive && tail)) {
        const units = Array.from(segmenter.segment(tail + additions), value => value.segment)
        // A token boundary need not be a grapheme boundary (ZWJ emoji / combining marks).
        tail = nextLive ? units.pop() || '' : ''
        if (!remaining()) { queue = []; offset = 0; last = now(); credit = displayed ? 0 : 1 }
        queue.push(...units)
      }
      source = text; initialized = true; live = nextLive
      if (!live && remaining() && deadline === null) deadline = now() + 1250
      publish(); schedule()
    },
    dispose() { disposed = true; clear() },
  }
}
