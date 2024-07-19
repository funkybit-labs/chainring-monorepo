import { calculateTickSpacing } from 'utils/orderBookUtils'
import { mergeOHLC } from 'utils/pricesUtils'
import { OHLCDuration } from 'websocketMessages'

describe('OrderBookUtils', () => {
  it('should work for different values', () => {
    expect(calculateTickSpacing(0, 20, 7)).toBe(2.5)
    expect(calculateTickSpacing(10, 30, 7)).toBe(2.5)
    expect(calculateTickSpacing(10, 20, 6)).toBe(1)
    expect(calculateTickSpacing(10, 20, 7)).toBe(1)
    expect(calculateTickSpacing(10, 20, 8)).toBe(1)
    expect(calculateTickSpacing(10, 20, 9)).toBe(1)
    expect(calculateTickSpacing(10, 20, 10)).toBe(1)
    expect(calculateTickSpacing(10, 20, 20)).toBe(0.5)
    expect(calculateTickSpacing(1, 2, 20)).toBe(0.05)
    expect(calculateTickSpacing(-0.01, 0.01, 8)).toBe(0.0025)
    expect(calculateTickSpacing(10000, 30000, 6)).toBe(2500)
    expect(calculateTickSpacing(10000, 30000, 5)).toBe(5000)
    expect(calculateTickSpacing(10000, 30000, 4)).toBe(5000)
    expect(calculateTickSpacing(10000, 30000, 3)).toBe(7500)
    expect(calculateTickSpacing(10000, 30000, 2)).toBe(10000)
  })
})

describe('PricesUtils', () => {
  function ohlc(
    startMs: number,
    duration: OHLCDuration,
    open: number,
    high: number,
    low: number,
    close: number
  ) {
    return {
      start: new Date(startMs),
      open,
      high,
      low,
      close,
      duration
    }
  }

  it('should replace ohlc matched by start', () => {
    expect(
      mergeOHLC(
        [ohlc(60000, 'P1M', 2, 4, 1, 3)],
        [ohlc(60000, 'P1M', 2, 5, 1, 5)],
        'P1M',
        60000
      )
    ).toStrictEqual([ohlc(60000, 'P1M', 2, 5, 1, 5)])
  })

  it('should replace and add ohlc matched by start', () => {
    expect(
      mergeOHLC(
        [ohlc(60000, 'P1M', 2, 4, 1, 3), ohlc(120000, 'P1M', 2, 4, 1, 3)],
        [ohlc(120000, 'P1M', 2, 5, 1, 5), ohlc(180000, 'P1M', 3, 3, 3, 3)],
        'P1M',
        180000
      )
    ).toStrictEqual([
      ohlc(60000, 'P1M', 2, 4, 1, 3),
      ohlc(120000, 'P1M', 2, 5, 1, 5),
      ohlc(180000, 'P1M', 3, 3, 3, 3)
    ])
  })

  it('should fill-in any gaps', () => {
    expect(
      mergeOHLC(
        [ohlc(60000, 'P1M', 2, 4, 1, 3), ohlc(120000, 'P1M', 3, 5, 0, 4)],
        [ohlc(300000, 'P1M', 2, 5, 1, 5)],
        'P1M',
        360001
      )
    ).toStrictEqual([
      ohlc(60000, 'P1M', 2, 4, 1, 3),
      ohlc(120000, 'P1M', 3, 5, 0, 4),
      ohlc(180000, 'P1M', 4, 4, 4, 4),
      ohlc(240000, 'P1M', 4, 4, 4, 4),
      ohlc(300000, 'P1M', 2, 5, 1, 5),
      ohlc(360000, 'P1M', 5, 5, 5, 5)
    ])
  })
})
