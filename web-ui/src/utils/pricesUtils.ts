import { OHLC } from 'websocketMessages'

export function mergeOHLC(draft: OHLC[], incoming: OHLC[]): OHLC[] {
  // update completes of last item before merge
  updateLastItemCompleteness(draft)

  incoming.forEach((item) => {
    // lookup if recent item is being replaced
    const index = draft.findIndex(
      (i) => i.start.getTime() == item.start.getTime()
    )
    if (index == -1) {
      draft.push(item)
    } else {
      draft[index] = item
    }
  })

  // update completes of last item after merge
  updateLastItemCompleteness(draft)
  return draft
}

function updateLastItemCompleteness(ohlc: OHLC[]): OHLC[] {
  if (ohlc.length > 0) {
    const mostRecentItem = ohlc[ohlc.length - 1]
    ohlc[ohlc.length - 1].incomplete =
      mostRecentItem.start.getTime() + mostRecentItem.durationMs > Date.now()
  }
  return ohlc
}
