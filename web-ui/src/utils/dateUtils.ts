import {
  DayOfTheMonthRange,
  DayOfTheWeekRange,
  HourRange,
  MonthRange,
  parseExpression,
  SixtyRange
} from 'cron-parser'

export const doesDateMatchCronExpression = (
  expression: string,
  date: Date,
  scope: string = 'second'
): boolean => {
  const scopes = ['second', 'minute', 'hour', 'day', 'month', 'weekday']
  const scopeIndex = scopes.indexOf(scope.toLowerCase())

  if (scopeIndex === -1) {
    return false
  }

  let data
  try {
    data = parseExpression(expression).fields
  } catch (e) {
    return false
  }

  const checks = [
    scopeIndex <= 0 &&
      !data.second.includes(date.getUTCSeconds() as SixtyRange),
    scopeIndex <= 1 &&
      !data.minute.includes(date.getUTCMinutes() as SixtyRange),
    scopeIndex <= 2 && !data.hour.includes(date.getUTCHours() as HourRange),
    scopeIndex <= 3 &&
      !data.dayOfMonth.includes(date.getUTCDate() as DayOfTheMonthRange),
    scopeIndex <= 4 &&
      !data.month.includes((date.getUTCMonth() + 1) as MonthRange),
    scopeIndex <= 5 &&
      !data.dayOfWeek.includes(date.getUTCDay() as DayOfTheWeekRange)
  ]

  return !checks.some((check) => check)
}
