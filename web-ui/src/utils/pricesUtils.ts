import { OHLC } from 'websocketMessages'

export function mergeOHLC(
  draft: OHLC[],
  incoming: OHLC[],
  durationMs: number
): OHLC[] {
  // update completes of last item before merge
  updateLastItemCompleteness(draft, durationMs)

  // merge new data
  incoming.forEach((newItem) => {
    // lookup if recent item is being replaced
    const index = draft.findIndex(
      (i) => i.start.getTime() == newItem.start.getTime()
    )
    if (index == -1) {
      // fill gaps before pushing new ohlc
      fillGaps(draft, newItem, durationMs)

      draft.push(newItem)
    } else {
      draft[index] = newItem
    }
  })
  // update completes of last item after merge
  updateLastItemCompleteness(draft, durationMs)
  return draft
}

function updateLastItemCompleteness(ohlc: OHLC[], duration: number): OHLC[] {
  if (ohlc.length > 0) {
    const mostRecentItem = ohlc[ohlc.length - 1]
    mostRecentItem.incomplete =
      mostRecentItem.start.getTime() + duration > Date.now()
  }
  return ohlc
}

function fillGaps(draft: OHLC[], newItem: OHLC, duration: number) {
  while (
    draft.length > 0 &&
    newItem.start > draft[draft.length - 1].start &&
    newItem.start.getTime() - draft[draft.length - 1].start.getTime() > duration
  ) {
    const lastItem = draft[draft.length - 1]

    draft.push({
      start: new Date(lastItem.start.getTime() + duration),
      duration: lastItem.duration,
      open: lastItem.close,
      high: lastItem.close,
      low: lastItem.close,
      close: lastItem.close,
      incomplete: false
    })
  }
}
