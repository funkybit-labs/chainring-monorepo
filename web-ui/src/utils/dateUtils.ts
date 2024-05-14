export function maxDate(a: Date, b: Date): Date {
  if (a >= b) {
    return a
  } else {
    return b
  }
}

export function addDuration(date: Date, millis: number): Date {
  return new Date(date.getTime() + millis)
}

export function subtractDuration(date: Date, millis: number): Date {
  return new Date(date.getTime() - millis)
}
