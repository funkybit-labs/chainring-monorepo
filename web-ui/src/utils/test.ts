import { calculateTickSpacing } from 'utils/orderBookUtils'
import { mergeOHLC } from 'utils/pricesUtils'

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
    durationMs: number,
    open: number,
    high: number,
    low: number,
    close: number,
    incomplete: boolean = false
  ) {
    return {
      start: new Date(startMs),
      open,
      high,
      low,
      close,
      durationMs,
      incomplete
    }
  }

  it('should work as a no-op', () => {
    expect(
      mergeOHLC(
        [ohlc(1000, 1000, 2, 4, 1, 3)],
        [ohlc(1000, 1000, 2, 4, 1, 3)],
        1000
      )
    ).toStrictEqual([ohlc(1000, 1000, 2, 4, 1, 3)])
  })
  it('should replace ohlc matched by start', () => {
    expect(
      mergeOHLC(
        [ohlc(1000, 1000, 2, 4, 1, 3)],
        [ohlc(1000, 1000, 2, 5, 1, 5)],
        1000
      )
    ).toStrictEqual([ohlc(1000, 1000, 2, 5, 1, 5)])
  })
  it('should replace and add ohlc matched by start', () => {
    expect(
      mergeOHLC(
        [ohlc(2000, 1000, 2, 4, 1, 3), ohlc(3000, 1000, 2, 4, 1, 3)],
        [ohlc(3000, 1000, 2, 5, 1, 5), ohlc(4000, 1000, 3, 3, 3, 3)],
        1000
      )
    ).toStrictEqual([
      ohlc(2000, 1000, 2, 4, 1, 3),
      ohlc(3000, 1000, 2, 5, 1, 5),
      ohlc(4000, 1000, 3, 3, 3, 3)
    ])
  })
  it('should fill-in any gaps', () => {
    expect(
      mergeOHLC(
        [ohlc(1000, 500, 2, 4, 1, 3), ohlc(1500, 500, 3, 5, 0, 4)],
        [ohlc(3000, 500, 2, 5, 1, 5)],
        500
      )
    ).toStrictEqual([
      ohlc(1000, 500, 2, 4, 1, 3),
      ohlc(1500, 500, 3, 5, 0, 4),
      ohlc(2000, 500, 4, 4, 4, 4),
      ohlc(2500, 500, 4, 4, 4, 4),
      ohlc(3000, 500, 2, 5, 1, 5)
    ])
  })
})
