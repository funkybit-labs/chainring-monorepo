import { OHLC } from 'websocketMessages'

export function mergeOHLC(ohlc: OHLC[], durationMs: number): OHLC[] {
  if (ohlc.length > 0) {
    const now = new Date()
    const merged: OHLC[] = []
    let cur: OHLC = { ...ohlc[0] }
    // adjust the start to the beginning of the durationMs boundary
    const originalStart = new Date(cur.start)
    cur.start = new Date(
      originalStart.getTime() - (originalStart.getTime() % durationMs)
    )
    let curEnd = cur.start.getTime() + durationMs
    cur.durationMs = durationMs
    for (let i = 1; i < ohlc.length; i++) {
      const next = ohlc[i]
      const nextStart = new Date(next.start)
      if (nextStart.getTime() < curEnd) {
        cur.low = Math.min(cur.low, next.low)
        cur.high = Math.max(cur.high, next.high)
        cur.close = next.close
      } else {
        merged.push(cur)
        // we need to add empty ohlcs if there are periods without prices
        curEnd += durationMs
        while (curEnd < nextStart.getTime()) {
          merged.push({
            start: new Date(curEnd - durationMs),
            open: cur.close,
            low: cur.close,
            high: cur.close,
            close: cur.close,
            durationMs: durationMs
          })
          curEnd += durationMs
        }
        cur = { ...next }
        cur.start = new Date(curEnd - durationMs)
        cur.durationMs = durationMs
      }
    }
    cur.incomplete = cur.start.getTime() + cur.durationMs > now.getTime()
    return merged.concat([cur])
  } else {
    return []
  }
}
