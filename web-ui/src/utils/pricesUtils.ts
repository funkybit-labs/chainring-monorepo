import { OHLC, OHLCDuration } from 'websocketMessages'

export const ohlcDurationsMs: Record<OHLCDuration, number> = {
  ['P1M']: 60 * 1000,
  ['P5M']: 5 * 60 * 1000,
  ['P15M']: 15 * 60 * 1000,
  ['P1H']: 60 * 60 * 1000,
  ['P4H']: 4 * 60 * 60 * 1000,
  ['P1D']: 24 * 60 * 60 * 1000
}

export function mergeOHLC(
  draft: OHLC[],
  incoming: OHLC[],
  duration: OHLCDuration
): OHLC[] {
  // update completes of last item before merge
  updateLastItemCompleteness(draft, ohlcDurationsMs[duration])

  // merge new data
  incoming.forEach((newItem) => {
    // lookup if recent item is being replaced
    const index = draft.findIndex(
      (i) => i.start.getTime() == newItem.start.getTime()
    )
    if (index == -1) {
      // fill gaps before pushing new ohlc
      fillGaps(draft, newItem, ohlcDurationsMs[duration])

      draft.push(newItem)
    } else {
      draft[index] = newItem
    }
  })
  // update completes of last item after merge
  updateLastItemCompleteness(draft, ohlcDurationsMs[duration])
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
