import { calculateTickSpacing } from 'utils/orderBookUtils'

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
