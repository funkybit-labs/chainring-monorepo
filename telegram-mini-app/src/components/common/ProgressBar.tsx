import Decimal from 'decimal.js'

export function ProgressBar({
  value,
  min,
  max
}: {
  value: Decimal
  min: Decimal
  max: Decimal
}) {
  const width = value
    .minus(min)
    .div(max.minus(min))
    .times(100)
    .round()
    .toNumber()
  return (
    <div className="relative h-2 w-full rounded bg-white">
      <div
        style={{ width: `${width}%` }}
        className="absolute left-0 top-0 h-2 rounded bg-gradient-to-b from-orangeStart to-orangeStop shadow-[inset_0_-2px_4px_0_rgba(0,0,0,0.25)]"
      />
    </div>
  )
}
