import {
  DayOfTheMonthRange,
  DayOfTheWeekRange,
  HourRange,
  MonthRange,
  parseExpression,
  SixtyRange
} from 'cron-parser'

export const isDateMatchesCronExpression = (
  expression: string,
  date: Date,
  scope: string = 'second'
): boolean => {
  const scopes = ['second', 'minute', 'hour', 'day', 'month', 'weekday']
  const scopeIndex = scopes.indexOf(scope.toLowerCase())

  if (scopeIndex === -1) {
    throw new Error(`Invalid scope: ${scope}`)
  }

  try {
    const data = parseExpression(expression).fields

    if (
      scopeIndex <= 0 &&
      !data.second.includes(date.getUTCSeconds() as SixtyRange)
    )
      return false
    if (
      scopeIndex <= 1 &&
      !data.minute.includes(date.getUTCMinutes() as SixtyRange)
    )
      return false
    if (scopeIndex <= 2 && !data.hour.includes(date.getUTCHours() as HourRange))
      return false
    if (
      scopeIndex <= 3 &&
      !data.dayOfMonth.includes(date.getUTCDate() as DayOfTheMonthRange)
    )
      return false
    if (
      scopeIndex <= 4 &&
      !data.month.includes((date.getUTCMonth() + 1) as MonthRange)
    )
      return false
    if (
      scopeIndex <= 5 &&
      !data.dayOfWeek.includes(date.getUTCDay() as DayOfTheWeekRange)
    )
      return false

    return true
  } catch (e) {
    throw new Error(`isDateMatchesCronExpression error: ${e}`)
  }
}
