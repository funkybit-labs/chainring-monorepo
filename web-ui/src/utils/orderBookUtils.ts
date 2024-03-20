export function calculateTickSpacing(
  minValue: number,
  maxValue: number,
  numberOfTicks: number
): number {
  // we want to pick "nice" tick numbers which are round-ish numbers but which mostly fill the available space
  // allowed ticks are of the form 10^x * s for some x with s in {1, 2.5, 5, 7.5}
  const delta = maxValue - minValue
  const rawTickSize = delta / numberOfTicks
  const rawTickX = Math.floor(Math.log10(rawTickSize))
  const rawTickS = rawTickSize / Math.pow(10, rawTickX)
  let roundedTickS: number
  let roundedTickX = rawTickX
  // clamp to the nearest allowed value
  if (rawTickS < (1 + 2.5) / 2) {
    roundedTickS = 1
  } else if (rawTickS < (2.5 + 5) / 2) {
    roundedTickS = 2.5
  } else if (rawTickS < (5 + 7.5) / 2) {
    roundedTickS = 5
  } else if (rawTickS < (7.5 + 10) / 2) {
    roundedTickS = 7.5
  } else {
    // we are clamping up; increase the exponent
    roundedTickS = 1
    roundedTickX += 1
  }
  return Math.pow(10, roundedTickX) * roundedTickS
}
